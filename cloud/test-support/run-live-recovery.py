#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Explicit small synthetic R2 acceptance; never clears app data or touches an owner package."""
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
p.add_argument("--resume-from-backups", action="store_true", help="Resume after the recorded connect/initialization steps; resolve the same failed reservation first.")
args = p.parse_args()
if not args.serial.startswith("emulator-") and not args.allow_phone:
    p.error("A physical device requires --allow-phone.")
secret = Path(args.credentials)
if secret.stat().st_mode & 0o077:
    p.error("Use a private credential file.")
config = json.loads(secret.read_text())
if config.get("syntheticOnly") is not True or config.get("origin") != "https://newpipe-accept-20261021a.ritorhymes.workers.dev":
    p.error("Dedicated synthetic endpoint required.")
out = Path(args.output)
out.mkdir(parents=True, exist_ok=False)
packages = {"a": "org.schabi.newpipe.debug.qacloudaccept20261021",
            "b": "org.schabi.newpipe.debug.qacloudaccept20261021.peerb"}
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
        raise RuntimeError("Live recovery step failed: " + name)
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
try:
    if args.resume_from_backups:
        step("a", "resolve")
    else:
        step("a", "connect")
        step("a", "initialize")
    first = step("a", "deviceBackup")
    archived = step("a", "catalogBackup")
    changed = step("a", "changeCloud")
    step("b", "connect")
    step("b", "join")
    restored = step("b", "restoreCatalog", archiveId=archived["archiveId"], expectedFingerprint=archived["fingerprint"])
    if restored["cloudFingerprint"] != changed["fingerprint"]:
        raise RuntimeError("Historical restore changed the live cloud")
    step("b", "deviceBackup")
    rotated = step("a", "deviceBackup")
    if rotated["evicted"] != first["archiveId"] or len(rotated["retained"]) != 3:
        raise RuntimeError("Three-slot rotation did not evict the approved oldest archive")
    native = step("b", "restoreNative", archiveId=rotated["archiveId"], expectedContent=rotated["content"])
    restarted = step("b", "verifyNativeAfterRestart")
    if native["marker"] != restarted["marker"] or restarted["content"] != rotated["content"]:
        raise RuntimeError("Native restore restart witness mismatch")
    step("a", "stop")
    step("b", "stop")
    (out / "results.json").write_text(json.dumps({"status": "PASS", "serial": args.serial,
        "scope": "Two isolated packages on one physical device", "steps": results}, indent=2))
except Exception as error:
    (out / "results.json").write_text(json.dumps({"status": "FAIL", "steps": results, "error": str(error)}, indent=2))
    raise
finally:
    for package in packages.values():
        adb("shell", "am", "force-stop", package)
        adb("shell", "run-as", package, "rm", "-f", "no_backup/live-qa.json")
