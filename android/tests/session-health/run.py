#!/usr/bin/env python3
"""Compile actual session-health sources with cached Kotlin/JVM tools; no Gradle or downloads."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


BASE_SOURCES = (
    "bluetooth/AACPManager.kt",
    "bluetooth/BoundPacketWriter.kt",
    "bluetooth/AacpPacketValidation.kt",
    "bluetooth/HeadTrackingMotion.kt",
    "bluetooth/RtBuddyHeadTracking.kt",
    "utils/OwnedCallbackSlot.kt",
    "data/CustomEq.kt",
    "data/AirPods.kt",
    "data/AirPodsName.kt",
    "data/BatteryHistory.kt",
    "data/Packets.kt",
    "data/CachedAirPodsInformation.kt",
    "data/ListeningModePolicy.kt",
    "data/BatterySessionPacketRecorder.kt",
)
BATTERY_SOURCES = (
    "data/BatterySession.kt",
    "data/BatterySessionRepository.kt",
    "data/BatterySessionPresentation.kt",
)
HEALTH_SOURCES = (
    "data/ConnectionHealth.kt",
    "services/ConnectionHealthMonitor.kt",
    "presentation/ConnectionHealthObserver.kt",
    "presentation/ConnectionHealthActions.kt",
    "presentation/ConnectionHealthPlatform.kt",
)


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-root", type=Path, default=here.parents[1])
    parser.add_argument("--battery-root", type=Path,
                        help="Optional Android worktree supplying battery domain sources")
    parser.add_argument("--health-root", type=Path,
                        help="Optional Android worktree supplying health domain sources")
    parser.add_argument("--compiler-lib", type=Path,
                        help="Cached Gradle distribution lib directory")
    parser.add_argument("--case", help="Run one named integration case")
    parser.add_argument("--negative-controls", action="store_true",
                        help="Prove two isolated broken snapshots fail their named runtime assertions")
    options = parser.parse_args()
    compiler_lib = options.compiler_lib
    if compiler_lib is None:
        roots = [Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")),
                 Path("/workspace/gradle-cache")]
        compilers = sorted({jar for root in roots for jar in root.glob(
            "wrapper/dists/gradle-*-bin/*/gradle-*/lib/kotlin-compiler-embeddable-*.jar")})
        if not compilers:
            parser.error("No cached Kotlin compiler; set GRADLE_USER_HOME or --compiler-lib. No downloads are performed.")
        compiler_lib = compilers[-1].parent
    if not list(compiler_lib.glob("kotlin-compiler-embeddable-*.jar")):
        parser.error(f"No Kotlin compiler jar in {compiler_lib}")
    java = shutil.which("java")
    if not java:
        parser.error("java is required")
    android_root = options.android_root.resolve()
    battery_root = (options.battery_root or android_root).resolve()
    health_root = (options.health_root or android_root).resolve()
    source_bytes = {}
    for root, relatives in ((android_root, BASE_SOURCES),
                            (battery_root, BATTERY_SOURCES),
                            (health_root, HEALTH_SOURCES)):
        source = root / "app/src/main/java/me/kavishdevar/librepods"
        for relative in relatives:
            path = source / relative
            if not path.is_file():
                parser.error(f"Missing source: {path}")
            source_bytes[relative] = path.read_bytes()
    for root in dict.fromkeys((android_root, battery_root, health_root)):
        revision = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"],
                                  text=True, capture_output=True)
        dirty = subprocess.run(["git", "-C", str(root), "status", "--porcelain", "--", "."],
                               text=True, capture_output=True)
        print(f"Production Android root: {root}", flush=True)
        print(f"Production HEAD: {revision.stdout.strip() or 'unknown'} "
              f"({'working changes present' if dirty.stdout else 'clean'})", flush=True)
    for relative, data in source_bytes.items():
        print(f"Source SHA256 {hashlib.sha256(data).hexdigest()} {relative}", flush=True)
    harness_bytes = {path.relative_to(here): path.read_bytes()
                     for path in [*sorted((here / "stubs").glob("*.kt")),
                                  *sorted(here.glob("*Integration.kt"))]}
    coroutines = list(compiler_lib.glob("kotlinx-coroutines-core-jvm-*.jar"))
    if not coroutines:
        parser.error(f"No cached kotlinx-coroutines-core-jvm jar in {compiler_lib}")
    library_jars = [*compiler_lib.glob("kotlin-stdlib-*.jar"),
                    *compiler_lib.glob("annotations-*.jar"), *coroutines]
    classpath = os.pathsep.join(map(str, library_jars))
    def compile_and_run(snapshot, build, case=None, capture=False):
        sources = []
        for relative, data in snapshot.items():
            target = build / "production" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            sources.append(target)
        resources = sorted(set(re.findall(r"R\.drawable\.(\w+)",
                                          snapshot["data/AirPods.kt"].decode())))
        resource_stub = build / "R.kt"
        resource_stub.write_text("package me.kavishdevar.librepods\nobject R { object drawable {\n" +
                                 "\n".join(f"const val {name} = {index}" for index, name in enumerate(resources, 1)) +
                                 "\n} }\n")
        sources.append(resource_stub)
        for relative, data in harness_bytes.items():
            target = build / "harness" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            sources.append(target)
        classes = build / "classes"
        subprocess.run([java, "-cp", str(compiler_lib / "*"),
                        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-nowarn", "-classpath", classpath,
                        "-d", str(classes), *map(str, sources)], check=True)
        command = [java, "-cp", os.pathsep.join([str(classes), classpath]),
                   "me.kavishdevar.librepods.integration.SessionHealthIntegrationKt"]
        if case:
            command.append(case)
        return subprocess.run(command, text=True, stdout=subprocess.PIPE if capture else None,
                              stderr=subprocess.STDOUT if capture else None)

    with tempfile.TemporaryDirectory(prefix="librepods-session-health-jvm-") as temporary:
        build = Path(temporary)
        result = compile_and_run(source_bytes, build / "baseline", options.case)
        if result.returncode or not options.negative_controls:
            raise SystemExit(result.returncode)
        mutations = (
            ("retired_battery_connection_accepted", "data/BatterySessionRepository.kt",
             "if (owner !== expectedOwner || connection !== token) return false",
             "if (owner !== expectedOwner) return false",
             "battery_inflight_callback_is_fenced_by_same_peer_reconnect"),
            ("first_profile_query_mixed_number_types", "services/ConnectionHealthMonitor.kt",
             "ticket.version != (profileVersions[ticket.profile] ?: 0L)",
             "ticket.version != (profileVersions[ticket.profile] ?: 0)",
             "health_newer_broadcast_wins_over_old_query"),
        )
        for name, relative, original, replacement, case in mutations:
            content = source_bytes[relative].decode()
            if content.count(original) != 1:
                parser.error(f"Negative-control anchor changed in {relative}; review {name}. No production files were edited.")
            snapshot = dict(source_bytes)
            snapshot[relative] = content.replace(original, replacement, 1).encode()
            print(f"Checking isolated negative control: {name}", flush=True)
            result = compile_and_run(snapshot, build / name, case, capture=True)
            failures = [line for line in result.stdout.splitlines() if line.startswith(f"FAIL {case}:")]
            if result.returncode == 0 or not failures:
                print(result.stdout, flush=True)
                raise SystemExit(f"Negative control did not fail its intended runtime assertion: {name}")
            print(f"PASS negative_control_{name}: {failures[0]}", flush=True)


if __name__ == "__main__":
    main()
