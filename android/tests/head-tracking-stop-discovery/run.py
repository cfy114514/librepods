#!/usr/bin/env python3
"""Compile current head-tracking service methods with JVM substitutes; no Gradle or downloads."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def between(text, start, end):
    for marker in (start, end):
        if text.count(marker) != 1:
            raise ValueError(f"Expected one source marker, found {text.count(marker)}: {marker!r}")
    first = text.index(start)
    last = text.index(end)
    if last <= first:
        raise ValueError(f"Source markers are out of order: {start!r}, {end!r}")
    return text[first:last].rstrip()


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError(f"Expected one replacement marker, found {text.count(old)}: {old!r}")
    return text.replace(old, new)


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-root", type=Path, default=here.parents[1])
    parser.add_argument("--compiler-lib", type=Path, help="Gradle distribution lib directory containing cached Kotlin compiler jars")
    options = parser.parse_args()
    compiler_lib = options.compiler_lib
    if compiler_lib is None:
        roots = [Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")), Path("/workspace/gradle-cache")]
        compilers = sorted({jar for root in roots for jar in root.glob("wrapper/dists/gradle-*-bin/*/gradle-*/lib/kotlin-compiler-embeddable-*.jar")})
        if not compilers:
            parser.error("No cached Kotlin compiler found; set GRADLE_USER_HOME or --compiler-lib. This script downloads nothing.")
        compiler_lib = compilers[-1].parent
    java = shutil.which("java")
    if not java:
        parser.error("java is required")
    library_jars = []
    for pattern in ("kotlin-stdlib-*.jar", "annotations-*.jar", "kotlinx-coroutines-core-jvm-*.jar"):
        matches = sorted(compiler_lib.glob(pattern))
        if not matches:
            parser.error(f"Missing cached dependency {pattern} in {compiler_lib}")
        library_jars.extend(matches)
    if not list(compiler_lib.glob("kotlin-compiler-embeddable-*.jar")):
        parser.error(f"Missing cached Kotlin compiler in {compiler_lib}")
    source = options.android_root.resolve() / "app/src/main/java/me/kavishdevar/librepods"
    service_path = source / "services/AirPodsService.kt"
    aacp_path = source / "bluetooth/AACPManager.kt"
    identity_path = source / "data/BatteryHistory.kt"
    service, aacp, identity = (path.read_text() for path in (service_path, aacp_path, identity_path))

    # Unique, complete declaration boundaries avoid counting braces in Kotlin strings/comments.
    request = between(service, "    private data class HeadTrackingRequest(", "    @Volatile private var appliedHeadTrackingRequest:")
    request = replace_once(request, "private data class HeadTrackingRequest", "data class HeadTrackingRequest")
    enqueue = between(service, "    private fun enqueueHeadTrackingCommand(", "    private fun headTrackingRequestCurrent(")
    guard = between(service, "    private fun headTrackingRequestCurrent(", "    private fun writeHeadTrackingRequest(")
    write = between(service, "    private fun writeHeadTrackingRequest(", '    @SuppressLint("MissingPermission")\n    fun reconnectFromSavedMac()')
    discovery = between(service, "            override fun onHeadTrackingServiceDiscovered()", "            override fun onProximityKeysReceived(")
    # Only visibility/override modifiers change; the service method bodies are unmodified.
    enqueue = replace_once(enqueue, "private fun enqueueHeadTrackingCommand", "fun enqueueHeadTrackingCommand")
    guard = replace_once(guard, "private fun headTrackingRequestCurrent", "fun headTrackingRequestCurrent")
    discovery = replace_once(discovery, "override fun", "fun")
    receive = between(aacp, "                Opcodes.HEADTRACKING -> {", "                Opcodes.PROXIMITY_KEYS_RSP -> {")
    receive = replace_once(receive, "                Opcodes.HEADTRACKING -> {", "")
    if not receive.endswith("}"):
        raise ValueError("HEADTRACKING branch has no closing brace")
    receive = receive[:-1].rstrip()
    header = between(aacp, "        private val HEADER_BYTES =", "        data class ControlCommandStatus(")
    data_packet = between(aacp, "    fun createDataPacket(", "    fun createControlCommandPacket(")
    packets = between(aacp, "    fun createStartHeadTrackingPacket()", "    fun sendStopHeadTracking()")
    packets += "\n" + between(aacp, "    fun createStopHeadTrackingPacket()", "    fun sendRename(")
    identity = between(identity, "internal fun batteryHistoryIdentity(", "internal fun canRecordBleBattery(")
    substitutions = {"REQUEST": request, "ENQUEUE": enqueue, "GUARD": guard, "WRITE": write,
                     "DISCOVERY": discovery, "RECEIVE": receive, "HEADER": header,
                     "DATA_PACKET": data_packet, "PACKETS": packets, "IDENTITY": identity}
    sources = [source / path for path in (
        "services/HeadTrackingSession.kt", "services/HeadTrackingStartup.kt", "utils/KeyedWorkSession.kt",
        "bluetooth/RtBuddyHeadTracking.kt", "bluetooth/HeadTrackingMotion.kt", "utils/HeadOrientation.kt")]
    for path in (service_path, aacp_path, identity_path, *sources):
        print(f"SOURCE {path} sha256={hashlib.sha256(path.read_bytes()).hexdigest()}", flush=True)
    classpath = os.pathsep.join(map(str, library_jars))
    with tempfile.TemporaryDirectory(prefix="librepods-head-stop-jvm-") as build:
        build = Path(build)
        for template in sorted(here.glob("*.kt.in")):
            content = template.read_text()
            for key, value in substitutions.items():
                marker = f"@@{key}@@"
                if marker in content:
                    content = replace_once(content, marker, value)
            if "@@" in content:
                raise ValueError(f"Unresolved production source placeholder in {template.name}")
            generated = build / template.name.removesuffix(".in")
            generated.write_text(content)
            sources.append(generated)
        if len(sources) != 10:
            raise ValueError("Expected four JVM harness templates")
        classes = build / "classes"
        subprocess.run([java, "-cp", str(compiler_lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-nowarn", "-classpath", classpath,
                        "-d", str(classes), *map(str, sources)], check=True)
        run = subprocess.run([java, "-cp", os.pathsep.join([str(classes), classpath]),
                              "me.kavishdevar.librepods.services.HeadTrackingStopDiscoveryRegressionKt"])
        raise SystemExit(run.returncode)


if __name__ == "__main__":
    main()
