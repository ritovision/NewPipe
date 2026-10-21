#!/usr/bin/env python3
"""Explicitly selected emulator + isolated Cloud QA package only. No phone/app-data reset."""
# SPDX-License-Identifier: GPL-3.0-or-later
from __future__ import annotations
import argparse
import hashlib
import json
import re
import subprocess
import time
import uuid
from pathlib import Path

QA_PACKAGE = "org.schabi.newpipe.debug.featurecloudsyncv120261019"
CLASSES = {"identity": "CloudIdentityProcessDeathTest", "enrollment": "CloudEnrollmentProcessDeathTest",
           "apply-before-room": "CloudApplyProcessDeathTest", "apply-after-room": "CloudApplyProcessDeathTest",
           "apply-before-tags": "CloudApplyProcessDeathTest"}
APPLY_PHASES = {"apply-before-room": "BEFORE_ROOM_COMMIT", "apply-after-room": "ROOM_COMMITTED", "apply-before-tags": "BEFORE_TAG_COMMIT"}
RECONNECT_PHASES = {"reconnect-" + p: p for p in ("journal", "archived-outbox", "witness", "metadata", "connection")}
CLASSES.update({name: "CloudReconnectProcessDeathTest" for name in RECONNECT_PHASES})
FORGET_PHASES = {"forget-" + p: p for p in ("off", "before-witness-commit", "witness", "metadata", "binding", "connection")}
CLASSES.update({name: "CloudForgetProcessDeathTest" for name in FORGET_PHASES})
HISTORICAL_PHASES = {"historical-" + name.removeprefix("apply-"): phase for name, phase in APPLY_PHASES.items()}
CLASSES.update({name: "CloudApplyProcessDeathTest" for name in HISTORICAL_PHASES})
DETACH_PHASES = {"detach-" + p: p for p in ("journal", "archived-outbox", "metadata", "connection")}
CLASSES.update({name: "CloudDetachProcessDeathTest" for name in DETACH_PHASES})
CLASSES["staging"] = "CloudStagingProcessDeathTest"
PHASES = {**APPLY_PHASES, **RECONNECT_PHASES, **FORGET_PHASES, **HISTORICAL_PHASES, **DETACH_PHASES}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True, type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd", required=True, help="Expected emulator name; never inferred")
    parser.add_argument("--user", required=True, type=int)
    parser.add_argument("--package", required=True, choices=[QA_PACKAGE])
    parser.add_argument("--suite", required=True, choices=list(CLASSES))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--source-commit", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial) or args.user < 0:
        parser.error("Only an explicitly selected Android emulator and nonnegative user are allowed")
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_commit):
        parser.error("Provide the exact tested source commit")
    if not args.adb.is_file():
        parser.error("ADB executable does not exist")
    args.output.mkdir(parents=True, exist_ok=False)
    adb = [str(args.adb), "-s", args.serial]

    def run(*command: str, timeout: int = 15, check: bool = True) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(adb + list(command), capture_output=True, text=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(f"ADB failed ({command[0]}): {result.stderr[:1000]}")
        return result

    if run("emu", "avd", "name").stdout.splitlines()[0].strip() != args.avd:
        raise RuntimeError("Emulator name mismatch; no test was started")
    if run("shell", "am", "get-current-user").stdout.strip() != str(args.user):
        raise RuntimeError("Android foreground user mismatch; no user switch was performed")
    instrumentation = run("shell", "pm", "list", "instrumentation").stdout
    runner = args.package + ".test/androidx.test.runner.AndroidJUnitRunner"
    if runner not in instrumentation:
        raise RuntimeError("Expected isolated QA instrumentation is not installed")
    run("shell", "run-as", args.package, "--user", str(args.user), "pwd")
    token = "probe-" + uuid.uuid4().hex[:20]
    kind = "detach" if args.suite in DETACH_PHASES else "forget" if args.suite in FORGET_PHASES else "reconnect" if args.suite in RECONNECT_PHASES else "apply" if args.suite in (APPLY_PHASES | HISTORICAL_PHASES) else args.suite
    storage = "no_backup" if args.suite in (FORGET_PHASES | HISTORICAL_PHASES | DETACH_PHASES) or args.suite == "staging" else "cache"
    directory = f"{storage}/cloud-{kind}-probe-{token}"
    klass = "org.schabi.newpipe.custom.cloud." + CLASSES[args.suite]
    base_command = adb + ["shell", "am", "instrument", "--user", str(args.user), "-w", "-r"]

    def command(method: str) -> list[str]:
        extra = ["-e", "cloudPhase", PHASES[args.suite]] if args.suite in PHASES else []
        if args.suite in HISTORICAL_PHASES:
            extra += ["-e", "cloudHistorical", "true"]
        return base_command + ["-e", "class", klass + "#" + method, "-e", "cloudProbe", token] + extra + [runner]

    def phase(method: str, name: str) -> None:
        result = subprocess.run(command(method), capture_output=True, text=True, timeout=100)
        text = result.stdout + result.stderr
        (args.output / name).write_text(text)
        if result.returncode or not re.search(r"OK \(1 tests?\)", text) or "FAILURES" in text:
            raise RuntimeError(f"Probe phase failed: {method}; see {name}")
        print("PHASE_PASS", method, flush=True)

    phase("prepareCommittedIdentities", "prepare.log")
    killed_pid: str | None = None
    with (args.output / "interrupted.log").open("w") as output:
        hold = subprocess.Popen(command("holdChangedIdentityTransactionForHostKill"), stdout=output, stderr=subprocess.STDOUT, text=True)
        try:
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                marker = run("shell", "run-as", args.package, "--user", str(args.user), "cat", directory + "/inside-transaction", timeout=5, check=False)
                if marker.returncode == 0 and marker.stdout.strip() == "ready-for-host-force-stop":
                    killed_pid = run("shell", "pidof", args.package).stdout.strip()
                    if not killed_pid or not all(part.isdecimal() for part in killed_pid.split()):
                        raise RuntimeError("No verifiable QA process at the marker")
                    # Marker proves the intended transaction is open. Never stop any other package/user.
                    run("shell", "am", "force-stop", "--user", str(args.user), args.package)
                    hold.wait(timeout=15)
                    break
                if hold.poll() is not None:
                    raise RuntimeError("Probe exited before its durable ready marker")
                time.sleep(0.5)
            if killed_pid is None:
                raise RuntimeError("Ready marker absent; no force-stop performed")
        finally:
            if hold.poll() is None:
                hold.terminate()  # Stops only this host adb invocation; device test times out itself.
                hold.wait(timeout=10)
    if run("shell", "pidof", args.package, check=False).stdout.strip():
        raise RuntimeError("Old QA process still alive after force-stop")
    phase("verifyRollbackAfterActualProcessDeath", "verify.log")
    verified = run("shell", "run-as", args.package, "--user", str(args.user), "cat", directory + "/verified").stdout.strip()
    expected_marker = "staging-orphans-cleaned-active-recovery-preserved" if args.suite == "staging" else "detach-preserved-native-and-pending-evidence" if args.suite in DETACH_PHASES else "forget-preserved-local-content" if args.suite in FORGET_PHASES else "reconnect-preserved-local-content" if args.suite in RECONNECT_PHASES else "rollback-restored-original-identities"
    if verified != expected_marker:
        raise RuntimeError("Fresh-process verification marker mismatch")
    result = {
        "status": "PASS", "source_commit": args.source_commit, "suite": args.suite,
        "serial": args.serial, "avd": args.avd, "android_user": args.user,
        "package": args.package, "probe": token, "killed_pid": killed_pid,
        "actual_host_force_stop": True, "verified_marker": verified,
        "scope": PHASES.get(args.suite, "single tag-map transaction" if args.suite == "identity" else "binding durable before tag enrollment commit"),
        "native_apply_recovery_tested": args.suite in (APPLY_PHASES | HISTORICAL_PHASES),
        "native_historical_recovery_tested": args.suite in HISTORICAL_PHASES,
        "native_detach_recovery_tested": args.suite in DETACH_PHASES,
        "native_staging_cleanup_tested": args.suite == "staging",
        "native_forget_recovery_tested": args.suite in FORGET_PHASES,
        "native_reconnection_recovery_tested": args.suite in RECONNECT_PHASES,
        "not_network_or_power_loss_recovery": True,
        "logs": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in args.output.glob("*.log")},
    }
    (args.output / "results.json").write_text(json.dumps(result, indent=2) + "\n")
    print("PROCESS_DEATH_PASS", json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
