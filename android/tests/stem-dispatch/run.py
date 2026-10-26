#!/usr/bin/env python3
"""Run the real stem dispatcher/Activity with deterministic Android boundary doubles, without Gradle."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-root", type=Path, default=here.parents[1])
    parser.add_argument("--compiler-lib", type=Path)
    parser.add_argument("--case")
    parser.add_argument("--negative-controls", action="store_true")
    args = parser.parse_args()
    roots = [Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")), Path("/workspace/gradle-cache")]
    compilers = sorted({jar for root in roots for jar in root.glob(
        "wrapper/dists/gradle-*-bin/*/gradle-*/lib/kotlin-compiler-embeddable-*.jar")})
    lib = args.compiler_lib or (compilers[-1].parent if compilers else None)
    if lib is None:
        parser.error("A cached Gradle distribution Kotlin compiler is required; no downloads are performed")
    java = shutil.which("java")
    if not java:
        parser.error("java is required")
    root = args.android_root.resolve() / "app/src/main/java/me/kavishdevar/librepods"
    names = ("data/StemAction.kt", "data/StemShortcut.kt", "data/AndroidStemShortcuts.kt", "shortcuts/StemShortcutDispatchActivity.kt")
    snapshot = {name: (root / name).read_bytes() for name in names}
    for name, data in snapshot.items():
        print(f"Source SHA256 {hashlib.sha256(data).hexdigest()} {name}", flush=True)
    jars = [*lib.glob("kotlin-stdlib-*.jar"), *lib.glob("annotations-*.jar")]
    cp = os.pathsep.join(map(str, jars))
    harness = {path.name: path.read_bytes() for path in [*sorted((here / "stubs").glob("*.kt")), here / "StemDispatchIntegration.kt"]}

    def run(sources_snapshot, build, case=None, capture=False):
        sources = []
        for name, data in sources_snapshot.items():
            target = build / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            sources.append(target)
        resources = sorted(set(re.findall(r"R\.string\.(\w+)", b"\n".join(sources_snapshot.values()).decode())))
        resource = build / "R.kt"
        resource.write_text("package me.kavishdevar.librepods\nobject R { object string {\n" +
                            "\n".join(f"const val {name} = {i}" for i, name in enumerate(resources, 1)) +
                            "\n}; object drawable { const val airpods = 100 } }\n")
        sources.append(resource)
        for name, data in harness.items():
            target = build / "harness" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            sources.append(target)
        classes = build / "classes"
        subprocess.run([java, "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-nowarn", "-classpath", cp,
                        "-d", str(classes), *map(str, sources)], check=True)
        command = [java, "-cp", os.pathsep.join([str(classes), cp]),
                   "me.kavishdevar.librepods.shortcuts.StemDispatchIntegrationKt"]
        if case:
            command.append(case)
        return subprocess.run(command, text=True, stdout=subprocess.PIPE if capture else None,
                              stderr=subprocess.STDOUT if capture else None)

    with tempfile.TemporaryDirectory(prefix="librepods-stem-dispatch-") as temp:
        build = Path(temp)
        result = run(snapshot, build / "baseline", args.case)
        if result.returncode or not args.negative_controls:
            raise SystemExit(result.returncode)
        mutations = (
            ("missing_launch_revalidation", "data/AndroidStemShortcuts.kt",
             "if (!canLaunch()) return StemLaunchAttempt.FAILED", "// Deliberately skip launch revalidation",
             "source_or_age_change_during_resolution_cannot_launch_or_fallback"),
            ("lost_notification_owner", "data/StemShortcut.kt", " && isSourceCurrent()", "",
             "source_ownership_survives_notification_and_foreground_handoff"),
            ("activity_request_replay", "data/StemShortcut.kt", "pending.remove(request.binding.side)", "// Deliberately retain consumed request",
             "replayed_activity_intent_does_not_launch_twice"),
        )
        for name, path, original, replacement, case in mutations:
            source = snapshot[path].decode()
            if source.count(original) != (2 if name == "missing_launch_revalidation" else 1):
                parser.error(f"Negative-control anchor changed: {name}; no production sources were edited")
            broken = dict(snapshot)
            broken[path] = source.replace(original, replacement).encode()
            result = run(broken, build / name, case, capture=True)
            failures = [line for line in result.stdout.splitlines() if line.startswith(f"FAIL {case}:")]
            if result.returncode == 0 or not failures:
                print(result.stdout, flush=True)
                raise SystemExit(f"Negative control did not fail its intended assertion: {name}")
            print(f"PASS negative_control_{name}: {failures[0]}", flush=True)


if __name__ == "__main__":
    main()
