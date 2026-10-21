#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Small live QA against the fenced service; two isolated installations, no data reset."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import subprocess
import time
from pathlib import Path

ORIGIN = 'https://newpipe-accept-20261021a.ritorhymes.workers.dev'
PACKAGES = {'a': 'org.schabi.newpipe.debug.qacloudaccept20261021',
            'b': 'org.schabi.newpipe.debug.qacloudaccept20261021.peerb'}

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True, type=Path)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True)
    parser.add_argument('--credentials', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--allow-emulator-network-outage', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial) or not args.allow_emulator_network_outage:
        parser.error('An exact emulator and explicit emulator-network-outage opt-in are required')
    if not re.fullmatch(r'[0-9a-f]{40}', args.source_commit):
        parser.error('An exact source commit is required')
    if args.credentials.stat().st_mode & 0o077:
        parser.error('Credential file must be private')
    config = json.loads(args.credentials.read_text())
    if config.get('syntheticOnly') is not True or config.get('origin') != ORIGIN or not re.fullmatch(r'[a-z0-9-]{1,48}', config.get('run', '')):
        parser.error('Exact fenced synthetic QA configuration required')
    args.output.mkdir(parents=True, exist_ok=False)
    results: list[dict] = []
    network_changed = False
    network = {}
    base = [str(args.adb), '-s', args.serial]

    def adb(*parts: str, data: bytes | None = None, timeout: int = 30, check: bool = True) -> str:
        r = subprocess.run(base + list(parts), input=data, capture_output=True, timeout=timeout)
        if check and r.returncode:
            raise RuntimeError('ADB operation failed: ' + ' '.join(parts[:3]))
        return r.stdout.decode(errors='replace')

    def save(status: str) -> None:
        (args.output / 'results.json').write_text(json.dumps({
            'status': status, 'source_commit': args.source_commit,
            'serial': args.serial, 'avd': args.avd, 'android_user': 0,
            'twoIndependentPackages': True, 'twoPhysicalDevices': False,
            'actualEmulatorNetworkOutage': network_changed, 'syntheticOnly': True,
            'steps': results, 'releaseAcceptance': False}, indent=2) + '\n')

    def stop_all() -> None:
        for p in PACKAGES.values():
            adb('shell', 'am', 'force-stop', '--user', '0', p)

    def set_online(online: bool) -> None:
        nonlocal network_changed
        stop_all()
        network_changed = True
        for service, key in [('wifi', 'wifi_on'), ('data', 'mobile_data')]:
            on = online and network[key] == '1'
            adb('shell', 'svc', service, 'enable' if on else 'disable')
        time.sleep(4)

    def step(key: str, action: str, expected: str | None = None, local: str | None = None) -> dict:
        stop_all()
        package = PACKAGES[key]
        adb('shell', 'run-as', package, 'rm', '-f', 'no_backup/live-qa-result.json')
        command = ['shell', 'am', 'instrument', '--user', '0', '-w', '-r', '-e', 'class',
                   'org.schabi.newpipe.custom.cloud.CloudLiveSyncTest', '-e', 'cloudLiveStep', action]
        if expected:
            command += ['-e', 'expectedFingerprint', expected]
        if local:
            command += ['-e', 'expectedLocalFingerprint', local]
        command += [package + '.test/androidx.test.runner.AndroidJUnitRunner']
        log = adb(*command, timeout=180)
        name = f'{len(results)+1:02d}-{key}-{action}'
        (args.output / (name + '.log')).write_text(log)
        if not re.search(r'OK \(1 tests?\)', log) or 'FAILURES' in log:
            raise RuntimeError('Live step failed: ' + name)
        result = json.loads(adb('exec-out', 'run-as', package, 'cat', 'no_backup/live-qa-result.json'))
        if result.get('package') != package or result.get('step') != action or result.get('syntheticOnly') is not True:
            raise RuntimeError('Result identity mismatch: ' + name)
        result['logSha256'] = hashlib.sha256(log.encode()).hexdigest()
        results.append(result)
        save('RUNNING')
        print('PASS', key, action, result['fingerprint'], flush=True)
        stop_all()
        return result

    if adb('emu', 'avd', 'name').splitlines()[0].strip() != args.avd or adb('shell', 'am', 'get-current-user').strip() != '0':
        raise RuntimeError('Exact emulator/user mismatch; no app or network change made')
    for package in PACKAGES.values():
        if 'package:' not in adb('shell', 'pm', 'path', package):
            raise RuntimeError('Missing QA package')
    for key in ['wifi_on', 'mobile_data']:
        network[key] = adb('shell', 'settings', 'get', 'global', key).strip()
        if network[key] not in ['0', '1']:
            raise RuntimeError('Original network state cannot be restored exactly')
    if network['wifi_on'] != '1' and network['mobile_data'] != '1':
        raise RuntimeError('Emulator must initially have networking enabled')
    (args.output / 'original-network.json').write_text(json.dumps(network) + '\n')
    try:
        stop_all()
        for key, package in PACKAGES.items():
            payload = json.dumps({'syntheticOnly': True, 'origin': ORIGIN, 'run': config['run'],
                                  'generation': config['generation'], 'token': config['tokens'][key]}).encode()
            adb('shell', 'run-as', package, 'mkdir', '-p', 'no_backup')
            adb('shell', '-T', 'run-as', package, 'dd', 'of=no_backup/live-qa.json', data=payload)
            adb('shell', 'run-as', package, 'chmod', '600', 'no_backup/live-qa.json')
            if adb('exec-out', 'run-as', package, 'cat', 'no_backup/live-qa.json').encode() != payload:
                raise RuntimeError('Private credential transfer failed')
        a = step('a', 'continuationJoin')
        b = step('b', 'continuationJoin')
        if a['fingerprint'] != b['fingerprint']:
            raise RuntimeError('Installations did not join the same accepted head')
        seeded = step('a', 'continuationSeed')
        step('b', 'continuationPull', seeded['fingerprint'])
        set_online(False)
        offline = step('b', 'continuationOfflineEdit')
        step('b', 'continuationOfflineRestart', seeded['fingerprint'], offline['fingerprint'])
        set_online(True)
        step('b', 'continuationResume', offline['fingerprint'])
        step('a', 'continuationPull', offline['fingerprint'])
        set_online(False)
        conflict = step('b', 'continuationConflictEdit', offline['fingerprint'])
        set_online(True)
        remote = step('a', 'continuationRemoteEdit')
        step('b', 'continuationConflict', remote['fingerprint'], conflict['fingerprint'])
        step('b', 'continuationReconcile', remote['fingerprint'], conflict['fingerprint'])
        step('b', 'continuationDisable')
        step('b', 'continuationOneTime', remote['fingerprint'])
        step('a', 'stop')
        step('b', 'stop')
        save('PASS')
    except BaseException:
        save('FAIL')
        raise
    finally:
        stop_all()
        if network_changed:
            set_online(True)
        for package in PACKAGES.values():
            adb('shell', 'run-as', package, 'rm', '-f', 'no_backup/live-qa.json', check=False)

if __name__ == '__main__':
    main()
