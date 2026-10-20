#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Explicit synthetic protected replacement acceptance. No owner package or app data clearing."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument("--adb", required=True)
p.add_argument("--serial", required=True)
p.add_argument("--credentials", required=True)
p.add_argument("--output", required=True)
p.add_argument("--allow-phone", action="store_true")
args = p.parse_args()
if not args.serial.startswith("emulator-") and not args.allow_phone:
    p.error("A physical device requires --allow-phone.")
secret = Path(args.credentials)
if secret.stat().st_mode & 0o077:
    p.error("Use a private credential file.")
config = json.loads(secret.read_text())
if config.get("syntheticOnly") is not True or config.get("origin") != "https://newpipe-cloud-sync-v1.ritorhymes.workers.dev":
    p.error("Dedicated synthetic endpoint required.")
out = Path(args.output)
out.mkdir(parents=True, exist_ok=False)
packages = {"a": "org.schabi.newpipe.debug.featurecloudsyncv120261019",
            "b": "org.schabi.newpipe.debug.featurecloudsyncv120261019.peerb"}
def adb(*parts, data=None, timeout=180):
    r = subprocess.run([args.adb, "-s", args.serial, *parts], input=data, capture_output=True, timeout=timeout)
    if r.returncode:
        raise RuntimeError("ADB failed: " + " ".join(parts[:4]))
    return r.stdout.decode(errors="replace")
if adb("get-state").strip() != "device":
    raise RuntimeError("Selected device is unavailable")
for key, package in packages.items():
    if "package:" not in adb("shell", "pm", "path", package):
        raise RuntimeError("Missing isolated QA package")
    adb("shell", "am", "force-stop", package)
    adb("shell", "run-as", package, "mkdir", "-p", "no_backup")
    payload = json.dumps({"syntheticOnly": True, "origin": config["origin"], "token": config["tokens"][key]}).encode()
    adb("shell", "-T", "run-as", package, "dd", "of=no_backup/live-qa.json", data=payload)
    adb("shell", "run-as", package, "chmod", "600", "no_backup/live-qa.json")
    if adb("exec-out", "run-as", package, "cat", "no_backup/live-qa.json").encode() != payload:
        raise RuntimeError("Private credential transfer did not round-trip")
results = []
def step(key, action, **parameters):
    package = packages[key]
    for target in packages.values():
        adb("shell", "am", "force-stop", target)
    adb("shell", "run-as", package, "rm", "-f", "no_backup/live-qa-result.json")
    command = ["shell", "am", "instrument", "-w", "-r", "-e", "class",
               "org.schabi.newpipe.custom.cloud.CloudLiveRecoveryTest", "-e", "recoveryStep", action]
    for name, value in parameters.items():
        command += ["-e", name, value]
    command += [package + ".test/androidx.test.runner.AndroidJUnitRunner"]
    started = time.monotonic()
    log = adb(*command)
    name = f"{len(results)+1:02}-{key}-{action}"
    (out / (name + ".log")).write_text(log)
    if "OK (1 test)" not in log or "FAILURES" in log:
        raise RuntimeError("Live replacement step failed: " + name)
    value = json.loads(adb("exec-out", "run-as", package, "cat", "no_backup/live-qa-result.json"))
    if value["step"] != action or value["package"] != package or value["syntheticOnly"] is not True:
        raise RuntimeError("Live recovery witness identity mismatch")
    value["seconds"] = round(time.monotonic() - started, 3)
    value["logSha256"] = hashlib.sha256(log.encode()).hexdigest()
    results.append(value)
    (out / "results.json").write_text(json.dumps({"status": "RUNNING", "steps": results}, indent=2))
    print("PASS", key, action, flush=True)
    adb("shell", "am", "force-stop", package)
    time.sleep(6)  # Bound this small live scenario below the dedicated per-IP request limit.
    return value
def hold_dirty():
    package = packages["b"]
    adb("shell", "run-as", package, "rm", "-f", "no_backup/live-replacement-dirty.json")
    command = [args.adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class",
               "org.schabi.newpipe.custom.cloud.CloudLiveRecoveryTest", "-e", "recoveryStep", "holdDirtyReplacement",
               package + ".test/androidx.test.runner.AndroidJUnitRunner"]
    log_path = out / "dirty-process-hold.log"
    with log_path.open("wb") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 25
            marker = None
            while time.monotonic() < deadline:
                try:
                    candidate = adb("exec-out", "run-as", package, "cat", "no_backup/live-replacement-dirty.json")
                    marker = json.loads(candidate)
                    break
                except (json.JSONDecodeError, RuntimeError):
                    time.sleep(0.5)
            if marker is None or marker["package"] != package or marker["syntheticOnly"] is not True or marker["before"] == marker["after"]:
                raise RuntimeError("Dirty process-hold witness missing or invalid")
        finally:
            adb("shell", "am", "force-stop", package)
            process.wait(timeout=20)
    marker["step"] = "holdDirtyReplacement"
    results.append(marker)
    (out / "results.json").write_text(json.dumps({"status": "RUNNING", "steps": results}, indent=2))
    print("PASS b durable dirty branch and process stop", flush=True)
    return marker
try:
    step("a", "connect")
    step("a", "initialize")
    backups = [step("a", "deviceBackup") for _ in range(3)]
    step("b", "connect")
    step("b", "enable")
    dirty = hold_dirty()
    replaced = step("a", "replaceCloud")
    if replaced["evicted"] != backups[0]["archiveId"] or set(replaced["retained"]) != {backups[1]["archiveId"], backups[2]["archiveId"], replaced["archiveId"]}:
        raise RuntimeError("Replacement did not retain the exact two historical survivors")
    step("b", "verifyDirtyEpoch", expectedLocalFingerprint=dirty["after"], expectedFingerprint=replaced["fingerprint"], expectedEpoch=replaced["epoch"])
    restored = step("b", "restoreCatalog", archiveId=replaced["archiveId"], expectedFingerprint=replaced["outgoingFingerprint"])
    if restored["cloudFingerprint"] != replaced["fingerprint"]:
        raise RuntimeError("Outgoing recovery changed the published replacement")
    step("a", "stop")
    step("b", "stop")
    (out / "results.json").write_text(json.dumps({"status": "PASS", "serial": args.serial,
        "scope": "Two isolated packages on one physical device; dirty branch held behind native writer barrier and process-stopped, not an OS network outage", "steps": results}, indent=2))
except Exception as error:
    (out / "results.json").write_text(json.dumps({"status": "FAIL", "steps": results, "error": str(error)}, indent=2))
    raise
finally:
    for package in packages.values():
        adb("shell", "am", "force-stop", package)
        adb("shell", "run-as", package, "rm", "-f", "no_backup/live-qa.json")
