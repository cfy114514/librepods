#!/usr/bin/env python3
"""Require specific runtime failures from service-source mutants; compile errors never pass."""
import argparse
from pathlib import Path
import subprocess
import sys


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--android-root', type=Path, default=here.parents[1])
    args = parser.parse_args()
    source = (args.android_root / 'app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt').read_text()
    cases = [
        ('close-reset', '                        popupShown = false\n                    }',
         '                        // negative control: retain stale shown state\n                    }',
         'manual dismissal clears visible state'),
        ('window-identity', '                    if (popupWindow === window) {',
         '                    if (true) {', 'late old close callback cannot clear newer popup'),
        ('failed-attach', '                    popupShown = opened',
         '                    popupShown = true', 'failed window attach does not consume future lid opening'),
        ('permission-queue', '                !Settings.canDrawOverlays(service) || bleManager.getMostRecentStatus()?.lidOpen != true)',
         '                bleManager.getMostRecentStatus()?.lidOpen != true)', 'queued popup rechecks overlay permission'),
        ('scan-generation', '        if (!enabled) bleScanGeneration.incrementAndGet()',
         '        if (!enabled) Unit', 'coalesced OFF ON replaces retired platform scan'),
        ('adapter-recovery', '                updateBleScanning(enabled)',
         '                // negative control: no adapter-driven scan recovery',
         'cold service with adapter disabled starts scan after STATE_ON'),
    ]
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name, before, after, failure in cases:
        if source.count(before) != 1: raise ValueError(f'{name}: expected unique source boundary')
        snapshot = args.output_dir / f'{name}.kt'
        snapshot.write_text(source.replace(before, after, 1))
        run = subprocess.run([sys.executable, str(here / 'run.py'), '--android-root', str(args.android_root),
                              '--service-source', str(snapshot)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (args.output_dir / f'{name}.log').write_text(run.stdout)
        if run.returncode == 0 or f'FAIL {failure}:' not in run.stdout or 'RESULT ' not in run.stdout:
            raise RuntimeError(f'{name} failed to prove expected runtime regression; inspect {name}.log')
        print(f'PASS {name}: expected runtime failure: {failure}', flush=True)
    print(f'RESULT {len(cases)} negative controls, 0 failures', flush=True)

if __name__ == '__main__': main()
