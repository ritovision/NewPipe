#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Explicit live synthetic acceptance for two separately installed Android app packages."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("--adb", required=True)
parser.add_argument("--serial", required=True)
parser.add_argument("--credentials", required=True)
parser.add_argument("--output", required=True)
parser.add_argument("--allow-phone", action="store_true")
parser.add_argument("--scenario", choices=["initial", "conflict"], default="initial")
args = parser.parse_args()
if not args.serial.startswith("emulator-") and not args.allow_phone:
    parser.error("Physical devices require the explicit --allow-phone option.")
credentials = Path(args.credentials).resolve()
if credentials.stat().st_mode & 0o077:
    parser.error("Credential file must be private (0600).")
config = json.loads(credentials.read_text())
if config.get("syntheticOnly") is not True or config.get("origin") != "https://newpipe-accept-20261021a.ritorhymes.workers.dev":
    parser.error("Only the dedicated synthetic NewPipe service is accepted.")
output = Path(args.output).resolve()
output.mkdir(parents=True, exist_ok=False)
packages = {
    "a": "org.schabi.newpipe.debug.qacloudaccept20261021",
    "b": "org.schabi.newpipe.debug.qacloudaccept20261021.peerb",
}
def adb(*parts, data=None, timeout=120):
    result = subprocess.run([args.adb, "-s", args.serial, *parts],
        input=data, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError("ADB operation failed: " + " ".join(parts[:4]) + " " +
            result.stderr.decode(errors="replace")[:500])
    return result.stdout.decode(errors="replace")

if adb("get-state").strip() != "device":
    raise RuntimeError("Requested device is not ready")
for key, package in packages.items():
    if "package:" not in adb("shell", "pm", "path", package):
        raise RuntimeError("Missing isolated QA package: " + package)
    adb("shell", "am", "force-stop", package)
    adb("shell", "run-as", package, "mkdir", "-p", "no_backup")
    payload = json.dumps({"syntheticOnly": True, "origin": config["origin"], "token": config["tokens"][key]}).encode()
    # shell -T forwards stdin on Windows ADB; exec-in can silently create an empty file.
    adb("shell", "-T", "run-as", package, "dd", "of=no_backup/live-qa.json", data=payload)
    adb("shell", "run-as", package, "chmod", "600", "no_backup/live-qa.json")
    received = adb("exec-out", "run-as", package, "cat", "no_backup/live-qa.json")
    if received.encode() != payload:
        raise RuntimeError("Private QA credential transfer did not round-trip")

results = []
def step(key, action, expected=None, local=None):
    package = packages[key]
    other = packages["b" if key == "a" else "a"]
    adb("shell", "am", "force-stop", other)
    adb("shell", "am", "force-stop", package)
    # Android may route println to logcat; a fresh private result is the success witness.
    adb("shell", "run-as", package, "rm", "-f", "no_backup/live-qa-result.json")
    command = ["shell", "am", "instrument", "-w", "-r",
        "-e", "class", "org.schabi.newpipe.custom.cloud.CloudLiveSyncTest",
        "-e", "cloudLiveStep", action]
    if expected:
        command += ["-e", "expectedFingerprint", expected]
    if local:
        command += ["-e", "expectedLocalFingerprint", local]
    command += [package + ".test/androidx.test.runner.AndroidJUnitRunner"]
    started = time.monotonic()
    log = adb(*command, timeout=180)
    name = str(len(results) + 1).zfill(2) + "-" + key + "-" + action
    (output / (name + ".log")).write_text(log)
    if "OK (1 test)" not in log or "FAILURES" in log:
        raise RuntimeError("Live step failed: " + name + "; see scoped log")
    result = json.loads(adb("exec-out", "run-as", package, "cat", "no_backup/live-qa-result.json"))
    if result["package"] != package or result["step"] != action or result["syntheticOnly"] is not True:
        raise RuntimeError("Live result identity mismatch")
    result["seconds"] = round(time.monotonic() - started, 3)
    result["logSha256"] = hashlib.sha256(log.encode()).hexdigest()
    results.append(result)
    (output / "results.json").write_text(json.dumps({"status": "RUNNING", "serial": args.serial, "steps": results}, indent=2))
    print("PASS", key, action, result["fingerprint"], flush=True)
    adb("shell", "am", "force-stop", package)
    return result

def kill_after_durable_edit(key):
    package = packages[key]
    for target in packages.values():
        adb("shell", "am", "force-stop", target)
    marker = "no_backup/live-qa-offline-ready.json"
    adb("shell", "run-as", package, "rm", "-f", marker)
    command = [args.adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", "org.schabi.newpipe.custom.cloud.CloudLiveSyncTest",
        "-e", "cloudLiveStep", "holdOfflineEdit",
        package + ".test/androidx.test.runner.AndroidJUnitRunner"]
    name = str(len(results) + 1).zfill(2) + "-" + key + "-holdOfflineEdit"
    path = output / (name + ".log")
    with path.open("wb") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 60
            ready = None
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("Offline probe exited before its durable marker")
                probe = subprocess.run([args.adb, "-s", args.serial, "exec-out",
                    "run-as", package, "cat", marker], capture_output=True, timeout=10)
                if probe.returncode == 0 and probe.stdout:
                    try:
                        ready = json.loads(probe.stdout)
                    except json.JSONDecodeError:
                        ready = None
                    if ready is not None:
                        break
                time.sleep(0.25)
            if ready is None or ready.get("package") != package or ready.get("syntheticOnly") is not True:
                raise RuntimeError("Missing or invalid durable offline marker")
            if ready.get("enabled") is not True or ready["before"] == ready["after"]:
                raise RuntimeError("Offline probe did not leave a dirty enabled installation")
            adb("shell", "am", "force-stop", package)
            process.wait(timeout=30)
        finally:
            adb("shell", "am", "force-stop", package)
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
    result = {
        "step": "holdOfflineEdit", "package": package, "syntheticOnly": True,
        "fingerprint": ready["after"], "previousFingerprint": ready["before"],
        "actualProcessStop": True, "networkDisabledGlobally": False,
        "logSha256": hashlib.sha256(path.read_bytes()).hexdigest()
    }
    results.append(result)
    (output / "results.json").write_text(json.dumps({"status": "RUNNING", "steps": results}, indent=2))
    print("PASS", key, "durable-local-edit-and-process-stop", ready["after"], flush=True)
    return ready

try:
    if args.scenario == "initial":
        initial = step("a", "initialize")
        step("b", "join", initial["fingerprint"])
        changed = step("a", "automaticEdit")
        step("b", "automaticPull", changed["fingerprint"])
        step("b", "disableAndEdit")
        restored = step("b", "oneTimeRestore")
        if restored["fingerprint"] != changed["fingerprint"]:
            raise RuntimeError("One-time restore did not recover the exact cloud content")
    else:
        initial = step("a", "resumeForConflict")
        peer = step("b", "resumeForConflict")
        if initial["fingerprint"] != peer["fingerprint"]:
            raise RuntimeError("Conflict scenario requires a common accepted baseline")
        dirty = kill_after_durable_edit("b")
        if dirty["before"] != initial["fingerprint"]:
            raise RuntimeError("Offline probe did not start at the accepted baseline")
        changed = step("a", "automaticRemoteEdit", dirty["before"])
        step("b", "verifyConflict", changed["fingerprint"], dirty["after"])
        step("b", "resolveConflictToDevice", changed["fingerprint"], dirty["after"])
    step("a", "stop")
    step("b", "stop")
    (output / "results.json").write_text(json.dumps({
        "status": "PASS", "scenario": args.scenario, "serial": args.serial,
        "twoIndependentPackages": True, "twoPhysicalDevices": False,
        "syntheticOnly": True, "steps": results,
        "releaseAcceptance": False, "r2RecoveryTested": False
    }, indent=2) + "\n")
except Exception:
    (output / "results.json").write_text(json.dumps({
        "status": "FAIL", "scenario": args.scenario, "steps": results,
        "syntheticOnly": True, "releaseAcceptance": False
    }, indent=2) + "\n")
    raise
finally:
    for package in packages.values():
        # Stop only this driver's explicitly named QA packages, even after a failed assertion.
        subprocess.run([args.adb, "-s", args.serial, "shell", "am", "force-stop", package],
            capture_output=True, timeout=20)
