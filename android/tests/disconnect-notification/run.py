#!/usr/bin/env python3
"""Run current disconnect receiver/publisher code on a controlled JVM event queue."""
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
    first, last = text.index(start), text.index(end)
    if last <= first:
        raise ValueError("Source markers are out of order")
    return text[first:last].rstrip()


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-root", type=Path, default=here.parents[1])
    parser.add_argument("--service-source", type=Path, help="Optional service snapshot for proving an old regression")
    parser.add_argument("--compiler-lib", type=Path)
    args = parser.parse_args()
    compiler_lib = args.compiler_lib
    if compiler_lib is None:
        roots = [Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")), Path("/workspace/gradle-cache")]
        jars = sorted({p for root in roots for p in root.glob("wrapper/dists/gradle-*-bin/*/gradle-*/lib/kotlin-compiler-embeddable-*.jar")})
        if not jars:
            parser.error("No cached Kotlin compiler found; set GRADLE_USER_HOME or --compiler-lib. Nothing is downloaded.")
        compiler_lib = jars[-1].parent
    if not any(compiler_lib.glob("kotlin-compiler-embeddable-*.jar")):
        parser.error(f"Missing kotlin-compiler-embeddable-*.jar in {compiler_lib}")
    java = shutil.which("java")
    if not java:
        parser.error("java is required")
    libraries = []
    for pattern in ("kotlin-stdlib-*.jar", "annotations-*.jar", "kotlinx-coroutines-core*.jar"):
        matches = sorted(compiler_lib.glob(pattern))
        if not matches:
            parser.error(f"Missing {pattern} in {compiler_lib}")
        libraries.extend(matches)
    source = args.android_root.resolve() / "app/src/main/java/me/kavishdevar/librepods"
    service_path = args.service_source or source / "services/AirPodsService.kt"
    service = service_path.read_text()
    request_prefixes = ("    private class AudioProfileRequest(", "    private data class AudioProfileRequest(")
    if sum(service.count(prefix) for prefix in request_prefixes) != 1:
        raise ValueError("Expected exactly one AudioProfileRequest declaration")
    request_prefix = next(prefix for prefix in request_prefixes if prefix in service)
    request = between(service, request_prefix, "    private val profileOwnerToken = Any()")
    receiver = between(service, "        connectionReceiver = object : BroadcastReceiver() {", "        showIslandReceiver = object : BroadcastReceiver() {")
    intent_filter = between(service, "        val deviceIntentFilter = IntentFilter().apply {", "        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {\n            registerReceiver(connectionReceiver")
    registration = between(service, "        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {\n            registerReceiver(connectionReceiver", "        // Query connected profiles; discovery alone must not attach control or claim audio.")
    sender = between(service, "                    if (notifyDisconnect && !serviceResources.isClosed) {", "\n                }\n                if (pendingAttSocket.get()")
    stale_watch = between(service, "    private fun clearStaleA2dpConnectionReceiver()", "    private fun dispatchProfileCleanup(")
    popup = between(service, "    var popupShown = false", "    var islandOpen = false")
    dispatch = between(service, "                for (request in requests) {", "\n            }\n        } catch (_: java.util.concurrent.CancellationException)")
    revoke = between(service, "    private fun revokeAudioRestore(", "    private fun restoreAudio(")
    revoke = revoke.replace("private fun revokeAudioRestore", "fun revokeAudioRestore", 1)
    finish = ""
    if "    private fun finishControlDisconnection()" in service:
        finish = between(service, "    private fun finishControlDisconnection()", "    fun disconnectForCD()")
    replacements = dict(RECEIVER=receiver, FILTER=intent_filter, REGISTRATION=registration, SENDER=sender, WATCH=stale_watch,
                        POPUP=popup, DISPATCH=dispatch, REVOKE=revoke, FINISH=finish, REQUEST=request)
    sources = [source / "services/AudioProfileOwnership.kt", source / "services/PendingProfileRequests.kt"]
    for path in (service_path, *sources):
        print(f"SOURCE {path} sha256={hashlib.sha256(path.read_bytes()).hexdigest()}", flush=True)
    classpath = os.pathsep.join(map(str, libraries))
    with tempfile.TemporaryDirectory(prefix="librepods-disconnect-jvm-") as temporary:
        build = Path(temporary)
        content = (here / "DisconnectNotificationRegression.kt.in").read_text()
        for key, value in replacements.items():
            marker = f"@@{key}@@"
            if content.count(marker) != 1:
                raise ValueError(f"Expected one template marker: {marker}")
            content = content.replace(marker, value)
        if "@@" in content:
            raise ValueError("Unresolved template marker")
        generated = build / "DisconnectNotificationRegression.kt"
        generated.write_text(content)
        classes = build / "classes"
        subprocess.run([java, "-cp", str(compiler_lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-nowarn", "-classpath", classpath,
                        "-d", str(classes), *map(str, sources), str(generated)], check=True)
        run = subprocess.run([java, "-cp", os.pathsep.join([str(classes), classpath]),
                              "me.kavishdevar.librepods.services.DisconnectNotificationRegressionKt"])
        raise SystemExit(run.returncode)


if __name__ == "__main__":
    main()
