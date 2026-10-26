#!/usr/bin/env python3
"""Run actual BLE expiry, audio takeover and profile dispatch code without Android."""
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


def function(text, marker):
    """Extract a complete block declaration, rejecting ambiguous source boundaries."""
    if text.count(marker) != 1:
        raise ValueError(f"Expected one declaration: {marker!r}")
    start = text.index(marker)
    annotation = "    @Synchronized\n"
    if text[:start].endswith(annotation): start -= len(annotation)
    depth, parentheses, pos, state, body = 0, 0, start, "code", False
    while pos < len(text):
        char, pair = text[pos], text[pos:pos + 2]
        if state == "line":
            if char == "\n": state = "code"
        elif state == "comment":
            if pair == "*/": state, pos = "code", pos + 1
        elif state in ('"', "'"):
            if char == "\\": pos += 1
            elif char == state: state = "code"
        elif pair == "//": state, pos = "line", pos + 1
        elif pair == "/*": state, pos = "comment", pos + 1
        elif char in ('"', "'"): state = char
        elif not body and char == "(": parentheses += 1
        elif not body and char == ")": parentheses -= 1
        elif char == "{" and (body or parentheses == 0):
            body = True
            depth += 1
        elif body and char == "}":
            depth -= 1
            if depth == 0: return text[start:pos + 1]
        pos += 1
    raise ValueError(f"Unterminated declaration: {marker!r}")


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-root", type=Path, default=here.parents[1])
    parser.add_argument("--service-source", type=Path, help="Immutable service snapshot for regression proof")
    parser.add_argument("--ownership-source", type=Path, help="Matching ownership snapshot, when replaying an old service")
    parser.add_argument("--queue-source", type=Path, help="Matching profile queue snapshot, when replaying an old service")
    parser.add_argument("--deadline-source", type=Path, help="Matching deadline snapshot, when replaying a service with explicit deadlines")
    parser.add_argument("--compiler-lib", type=Path)
    parser.add_argument("--save-generated", type=Path)
    args = parser.parse_args()
    compiler_lib = args.compiler_lib
    if compiler_lib is None:
        roots = [Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")), Path("/workspace/gradle-cache")]
        jars = sorted({p for root in roots for p in root.glob("wrapper/dists/gradle-*-bin/*/gradle-*/lib/kotlin-compiler-embeddable-*.jar")})
        if not jars: parser.error("No cached Kotlin compiler; set --compiler-lib or GRADLE_USER_HOME. Nothing is downloaded.")
        compiler_lib = jars[-1].parent
    if not any(compiler_lib.glob("kotlin-compiler-embeddable-*.jar")):
        parser.error(f"No Kotlin compiler in {compiler_lib}")
    java = shutil.which("java")
    if not java: parser.error("java is required")
    libraries = []
    for pattern in ("kotlin-stdlib-*.jar", "annotations-*.jar", "kotlinx-coroutines-core*.jar"):
        matches = sorted(compiler_lib.glob(pattern))
        if not matches: parser.error(f"Missing {pattern} in {compiler_lib}")
        libraries.extend(matches)
    source = args.android_root.resolve() / "app/src/main/java/me/kavishdevar/librepods/services"
    service_path = args.service_source or source / "AirPodsService.kt"
    ownership_path = args.ownership_source or source / "AudioProfileOwnership.kt"
    sources = [ownership_path, args.queue_source or source / "PendingProfileRequests.kt",
               source / "ConnectionAttempts.kt", source / "CloseableResourceScope.kt"]
    service = service_path.read_text()
    deadline_marker = "    private val audioConnectDeadline = serviceResources.track(AudioConnectDeadline(audioProfileOwnership, serviceScope))"
    if "private val audioConnectDeadline" in service:
        if service.count(deadline_marker) != 1: raise ValueError("Audio deadline initializer changed")
        sources.append(args.deadline_source or source / "AudioConnectDeadline.kt")
    replacements = {
        "DEADLINE_FIELD": deadline_marker if "private val audioConnectDeadline" in service else "",
        "PROFILE_TYPE": between(service, "    private enum class AudioProfileAction", "    private val profileOwnerToken = Any()"),
        "PROFILE_QUEUE": between(service, "    private val profileRequests = serviceResources.track(", "    private val batteryWidgetUpdates ="),
        "LISTENER": function(service, "    private val bleStatusListener = object : BLEManager.AirPodsStatusListener {"),
        "NOTIFICATION": function(service, "    fun updateNotificationContent("),
        "ADAPTER": between(service, "            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {",
                           "            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)"),
        "TAKEOVER_TYPE": between(service, "    private enum class TakeoverAction", "    private val takeoverGeneration = AtomicLong()"),
        "TAKEOVER": between(service, "    @RequiresApi(Build.VERSION_CODES.R)\n    fun takeOver(", "    @SuppressLint(\"MissingPermission\")\n    private fun performTakeover("),
        "CONNECT_TAKEOVER": function(service, "    private fun connectAudioForTakeover("),
        "SAVED_RECONNECT": function(service, "    fun reconnectFromSavedMac()"),
        "CONNECT": between(service, "    fun connectAudio(context: Context, device: BluetoothDevice?)", "    fun setName(name: String)"),
        "PROFILE_OFFER": between(service, "    private fun withAudioProfile(", "    private fun acquireAudioProfile("),
        "PROFILE_DISPATCH": between(service, "                for (request in requests) {", "\n            }\n        } catch (_: java.util.concurrent.CancellationException)"),
        "PROFILE_FINISH": function(service, "            onFinished = {").replace("onFinished =", "val onFinished =", 1),
        "AUDIO_VALID": between(service, "    @SuppressLint(\"MissingPermission\")\n    private fun audioOperationValid(", "    fun disconnectAudio("),
        "DISCONNECT_AUDIO": function(service, "    fun disconnectAudio("),
        "REVOKE": function(service, "    private fun revokeAudioRestore("),
        "RETIRE": function(service, "    private fun finishControlDisconnection()"),
        "WATCH": between(service, "    private fun clearStaleA2dpConnectionReceiver()", "    private fun dispatchProfileCleanup("),
    }
    # These helpers are absent in the regression snapshot. Their callers are always
    # extracted from that same snapshot; no test-side replacement fixes old behavior.
    replacements["PRESENCE_HELPERS"] = "\n".join(
        function(service, marker) for marker in (
            "    private fun expireBlePresence()", "    private fun clearConnectionNotification()"
        ) if marker in service
    )
    for path in (service_path, *sources):
        print(f"SOURCE {path} sha256={hashlib.sha256(path.read_bytes()).hexdigest()}", flush=True)
    content = (here / "BlePresenceLifecycleRegression.kt.in").read_text()
    for key, value in replacements.items():
        marker = f"@@{key}@@"
        if content.count(marker) != 1: raise ValueError(f"Expected one template marker: {marker}")
        content = content.replace(marker, value)
    if "@@" in content: raise ValueError("Unresolved template marker")
    publication = (here / "PublicationRace.kt.in").read_text()
    if publication.count("@@PUBLICATION@@") != 1: raise ValueError("Expected one publication template marker")
    publication = publication.replace("@@PUBLICATION@@", between(service,
        "            // Publish AACP without waiting for the optional hearing-feature channel.",
        "            serviceResources.ensureOpen()"))
    if "@@" in publication: raise ValueError("Unresolved publication template marker")
    if args.save_generated:
        args.save_generated.parent.mkdir(parents=True, exist_ok=True)
        args.save_generated.write_text(content)
        args.save_generated.with_name(args.save_generated.stem + ".publication.kt").write_text(publication)
    classpath = os.pathsep.join(map(str, libraries))
    with tempfile.TemporaryDirectory(prefix="librepods-ble-presence-jvm-") as temporary:
        build = Path(temporary)
        generated = build / "BlePresenceLifecycleRegression.kt"
        generated.write_text(content)
        publication_file = build / "PublicationRace.kt"
        publication_file.write_text(publication)
        identity = build / "Identity.kt"
        identity.write_text('package me.kavishdevar.librepods.data\nfun batteryHistoryIdentity(value: String?): String? = value?.takeIf { it.isNotBlank() }?.uppercase()\n')
        classes = build / "classes"
        subprocess.run([java, "-cp", str(compiler_lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-nowarn", "-classpath", classpath, "-d", str(classes),
                        *map(str, sources), str(identity), str(generated), str(publication_file)], check=True)
        run = subprocess.run([java, "-cp", os.pathsep.join([str(classes), classpath]),
                              "me.kavishdevar.librepods.services.BlePresenceLifecycleRegressionKt"])
        raise SystemExit(run.returncode)


if __name__ == "__main__":
    main()
