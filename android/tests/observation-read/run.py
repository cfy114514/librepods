#!/usr/bin/env python3
"""Compile extracted service observation methods and actual repositories without Gradle/downloads."""
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
    parser.add_argument("--service-source", type=Path, help="Optional immutable old service snapshot")
    parser.add_argument("--compiler-lib", type=Path)
    parser.add_argument("--case")
    parser.add_argument("--negative-controls", action="store_true")
    args = parser.parse_args()
    compiler_lib = args.compiler_lib
    if compiler_lib is None:
        roots = [Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")), Path("/workspace/gradle-cache")]
        compilers = sorted({jar for root in roots for jar in root.glob("wrapper/dists/gradle-*-bin/*/gradle-*/lib/kotlin-compiler-embeddable-*.jar")})
        if not compilers:
            parser.error("No cached Kotlin compiler; set --compiler-lib. Nothing is downloaded.")
        compiler_lib = compilers[-1].parent
    java = shutil.which("java")
    if not java:
        parser.error("java is required")
    if not any(compiler_lib.glob("kotlin-compiler-embeddable-*.jar")):
        parser.error(f"No Kotlin compiler in {compiler_lib}")
    libraries = []
    for pattern in ("kotlin-stdlib-*.jar", "annotations-*.jar", "kotlinx-coroutines-core-jvm-*.jar"):
        matches = sorted(compiler_lib.glob(pattern))
        if not matches:
            parser.error(f"Missing {pattern} in {compiler_lib}")
        libraries.extend(matches)
    classpath = os.pathsep.join(map(str, libraries))
    root = args.android_root.resolve()
    source = root / "app/src/main/java/me/kavishdevar/librepods"
    service_path = args.service_source or source / "services/AirPodsService.kt"
    production = {relative: (source / relative).read_bytes() for relative in (
        "data/BatterySession.kt", "data/BatterySessionRepository.kt", "data/BatteryHistory.kt",
        "data/ConnectionHealth.kt", "services/ConnectionHealthMonitor.kt", "data/Packets.kt")}
    service_bytes = service_path.read_bytes()
    print(f"Production Android root: {root}", flush=True)
    revision = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, text=True, check=True)
    dirty = subprocess.run(["git", "-C", str(root), "status", "--porcelain", "--", "."], capture_output=True, text=True, check=True)
    print(f"Production HEAD: {revision.stdout.strip()} ({'working changes present' if dirty.stdout else 'clean'})", flush=True)
    for name, content in [(str(service_path), service_bytes), *production.items()]:
        print(f"Source SHA256 {hashlib.sha256(content).hexdigest()} {name}", flush=True)
    service = service_bytes.decode()
    replacements = {
        "CONTROL_TYPES": between(service, "    private inner class ControlObservations(", "    private var lastBleBatteryStatus:"),
        "OBSERVATION_METHODS": between(service, "    /** No profile acquisition or reconnect: only a response from this live bound service. */", "    @SuppressLint(\"MissingPermission\")\n    internal fun isCurrentControlSocket("),
        "PUBLISH": between(service, "                    val healthSelection = connectionHealth.selection()?.takeIf { it.peer.equals(device.address, true) }", "                    this@AirPodsService.device = device"),
    }
    battery_types = between(production.pop("data/Packets.kt").decode(), "object BatteryComponent {", "enum class NoiseControlMode {")
    production["data/BatteryTypes.kt"] = ("package me.kavishdevar.librepods.data\nimport android.os.Parcelable\nimport kotlinx.parcelize.Parcelize\n" + battery_types).encode()
    template = (here / "ObservationReadRegression.kt.in").read_text()

    def compile_and_run(methods, build, case=None, capture=False):
        content = template
        for key, value in dict(replacements, OBSERVATION_METHODS=methods).items():
            marker = f"@@{key}@@"
            if content.count(marker) != 1:
                raise ValueError(f"Expected one template marker: {marker}")
            content = content.replace(marker, value)
        if "@@" in content:
            raise ValueError("Unresolved template marker")
        files = dict(production)
        files["ObservationReadRegression.kt"] = content.encode()
        files["Parcelable.kt"] = b"package android.os\ninterface Parcelable\n"
        files["Parcelize.kt"] = b"package kotlinx.parcelize\nannotation class Parcelize\n"
        sources = []
        for name, data in files.items():
            target = build / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            sources.append(target)
        classes = build / "classes"
        subprocess.run([java, "-cp", str(compiler_lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-nowarn", "-classpath", classpath,
                        "-d", str(classes), *map(str, sources)], check=True)
        command = [java, "-cp", os.pathsep.join([str(classes), classpath]),
                   "me.kavishdevar.librepods.services.ObservationReadRegressionKt"]
        if case:
            command.append(case)
        return subprocess.run(command, text=True, stdout=subprocess.PIPE if capture else None,
                              stderr=subprocess.STDOUT if capture else None)

    methods = replacements["OBSERVATION_METHODS"]
    with tempfile.TemporaryDirectory(prefix="librepods-observation-read-jvm-") as temporary:
        build = Path(temporary)
        result = compile_and_run(methods, build / "baseline", args.case)
        if result.returncode or not args.negative_controls:
            raise SystemExit(result.returncode)
        mutations = (
            ("unconditional_adapter_invalidation", "if (!isCurrentConnectionObservationRead(read)) return@synchronized false",
             "// Old behavior: apply this adapter result to whichever connection is current now.",
             "stale_adapter_read_cannot_retire_reconnect", "Delayed adapter result retired replacement battery connection"),
            ("unconditional_profile_denial", "read?.let(::invalidateConnectionObservationRead)",
             "invalidateConnectionObservations()", "stale_profile_denial_cannot_retire_reconnect",
             "Delayed profile denial retired replacement battery connection"),
        )
        for name, original, replacement, case, failure in mutations:
            if methods.count(original) != 1:
                parser.error(f"Negative-control anchor changed: {name}; review required.")
            result = compile_and_run(methods.replace(original, replacement, 1), build / name, case, capture=True)
            failures = [line for line in result.stdout.splitlines() if line == f"FAIL {case}: {failure}"]
            if result.returncode == 0 or not failures:
                print(result.stdout, flush=True)
                raise SystemExit(f"Negative control did not fail its intended assertion: {name}")
            print(f"PASS negative_control_{name}: {failures[0]}", flush=True)


if __name__ == "__main__":
    main()
