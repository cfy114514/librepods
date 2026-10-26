#!/usr/bin/env python3
"""Compile the production BLE manager and run timestamp-ordering JVM regressions."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-root', type=Path, default=here.parents[1])
    parser.add_argument('--compiler-lib', type=Path)
    parser.add_argument('--manager-source', type=Path, help='Immutable BLEManager source snapshot for a negative control')
    args = parser.parse_args()
    compiler_lib = args.compiler_lib
    if compiler_lib is None:
        roots = [Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')), Path('/workspace/gradle-cache')]
        jars = sorted({jar for root in roots for jar in root.glob('wrapper/dists/gradle-*-bin/*/gradle-*/lib/kotlin-compiler-embeddable-*.jar')})
        if not jars:
            parser.error('No cached Kotlin compiler found; set GRADLE_USER_HOME or --compiler-lib. No downloads are performed.')
        compiler_lib = jars[-1].parent
    java = shutil.which('java')
    if not java:
        parser.error('java is required')
    source = args.android_root.resolve() / 'app/src/main/java/me/kavishdevar/librepods'
    sources = [source / ('bluetooth/' + name + '.kt') for name in (
        'BLEManager', 'BleAdvertisementDeduplicator', 'BleLidState', 'BleMaintenanceTask',
        'AirPodsProximityModels', 'CachedRpaVerifier', 'CachedProximityDecryptor')]
    if args.manager_source:
        sources[0] = args.manager_source.resolve()
    # The harness can also verify the pre-fix tree, which has no observation helper.
    sources += sorted((source / 'bluetooth').glob('BleObservationOrder.kt'))
    sources += [source / 'utils/BluetoothCryptography.kt', *sorted((here / 'stubs').glob('*.kt')),
                here / 'BleScanOrderingRegression.kt']
    for path in sources:
        if not path.is_file():
            parser.error(f'Missing source: {path}')
        print(f'SOURCE {path} sha256={hashlib.sha256(path.read_bytes()).hexdigest()}', flush=True)
    classpath = os.pathsep.join(map(str, [*compiler_lib.glob('kotlin-stdlib-*.jar'), *compiler_lib.glob('annotations-*.jar')]))
    with tempfile.TemporaryDirectory(prefix='librepods-ble-scan-jvm-') as build:
        print(f'Compiling production source: {sources[0]}', flush=True)
        subprocess.run([java, '-cp', str(compiler_lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                        '-no-stdlib', '-no-reflect', '-nowarn', '-classpath', classpath,
                        '-d', build, *map(str, sources)], check=True)
        result = subprocess.run([java, '-cp', os.pathsep.join([build, classpath]),
                                 'me.kavishdevar.librepods.bluetooth.BleScanOrderingRegressionKt'])
        raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
