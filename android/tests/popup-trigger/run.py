#!/usr/bin/env python3
"""Exercise extracted popup and BLE scan lifecycle service code with controlled queues."""
import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-root', type=Path, default=here.parents[1])
    parser.add_argument('--service-source', type=Path)
    parser.add_argument('--compiler-lib', type=Path)
    parser.add_argument('--save-generated', type=Path)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location('source_extraction', here.parent / 'ble-presence-lifecycle/run.py')
    extraction = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(extraction)
    between, function = extraction.between, extraction.function
    source = args.android_root.resolve() / 'app/src/main/java/me/kavishdevar/librepods'
    service_path = args.service_source or source / 'services/AirPodsService.kt'
    service = service_path.read_text()
    current = 'private val bleScanUpdates' in service
    replacements = {
        'POPUP': between(service, '    var popupShown = false', '    var islandOpen = false'),
        'LID': function(service, '        override fun onLidStateChanged(').replace('override fun', 'fun', 1),
        'SCAN': between(service, '    @Volatile private var bleScanningRequested = false', '    companion object {\n        private val pendingAudioProfiles') if current else '',
        'START': 'updateBleScanning(true)' if current else 'bleManager.startScanning()',
        'PREF': 'updatePopupPreference(sharedPreferences, key)' if 'private fun updatePopupPreference(' in service else '',
        'CLOSE': 'updateBleScanning(false)' if current else '',
        'SCAN_TESTS': 'true' if current else 'false',
        'ADAPTER': between(service, '            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {', '            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)'),
    }
    if current:
        assert 'serviceResources.track(Closeable { bleManager.stopScanning() })' in service
        assert '        updateBleScanning(true)\n    }' in service
        assert '        updateBleScanning(false)\n        serviceResources.close()' in service
        assert '        updatePopupPreference(preferences, key)' in service
    compiler = args.compiler_lib
    if compiler is None:
        roots = [Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')), Path('/workspace/gradle-cache')]
        jars = sorted({p for root in roots for p in root.glob('wrapper/dists/gradle-*-bin/*/gradle-*/lib/kotlin-compiler-embeddable-*.jar')})
        if not jars: parser.error('No cached Kotlin compiler found; nothing is downloaded.')
        compiler = jars[-1].parent
    libraries = []
    for pattern in ('kotlin-stdlib-*.jar', 'annotations-*.jar', 'kotlinx-coroutines-core*.jar'):
        matches = sorted(compiler.glob(pattern))
        if not matches: parser.error(f'Missing {pattern} in {compiler}')
        libraries.extend(matches)
    java = shutil.which('java')
    if not java: parser.error('java is required')
    sources = [source / 'utils/CoalescedWorkSession.kt', source / 'bluetooth/BleLidState.kt']
    content = (here / 'PopupTriggerRegression.kt.in').read_text()
    for key, value in replacements.items():
        marker = f'@@{key}@@'
        if content.count(marker) != 1: raise ValueError(f'Expected one marker: {marker}')
        content = content.replace(marker, value)
    if '@@' in content: raise ValueError('Unresolved source marker')
    for path in [service_path, *sources]:
        print(f'SOURCE {path} sha256={hashlib.sha256(path.read_bytes()).hexdigest()}', flush=True)
    if args.save_generated:
        args.save_generated.parent.mkdir(parents=True, exist_ok=True)
        args.save_generated.write_text(content)
    classpath = os.pathsep.join(map(str, libraries))
    with tempfile.TemporaryDirectory(prefix='librepods-popup-trigger-') as temporary:
        build = Path(temporary)
        generated = build / 'PopupTriggerRegression.kt'
        generated.write_text(content)
        classes = build / 'classes'
        subprocess.run([java, '-cp', str(compiler / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                        '-no-stdlib', '-no-reflect', '-nowarn', '-classpath', classpath, '-d', str(classes),
                        *map(str, sources), str(generated)], check=True)
        run = subprocess.run([java, '-cp', os.pathsep.join([str(classes), classpath]), 'regression.PopupTriggerRegressionKt'])
        raise SystemExit(run.returncode)

if __name__ == '__main__': main()
