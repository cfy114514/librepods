#!/usr/bin/env python3
"""Compile and exercise the complete production PopupWindow without Gradle or a device."""
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
    android = args.android_root.resolve()
    root = android / "app/src/main/java/me/kavishdevar/librepods"
    names = ("presentation/overlays/PopupWindow.kt", "data/Packets.kt")
    snapshot = {name: (root / name).read_bytes() for name in names}
    print(f"Android root: {android}", flush=True)
    for command in (["git", "rev-parse", "HEAD"], ["git", "status", "--short"]):
        result = subprocess.run(command, cwd=android, check=True, text=True, stdout=subprocess.PIPE)
        print(" ".join(command) + ": " + (result.stdout.strip() or "clean"), flush=True)
    for name, data in snapshot.items():
        print(f"Source SHA256 {hashlib.sha256(data).hexdigest()} {name}", flush=True)
    cp = os.pathsep.join(map(str, [*lib.glob("kotlin-stdlib-*.jar"), *lib.glob("annotations-*.jar")]))
    harness = {path.name: path.read_bytes() for path in [*sorted((here / "stubs").glob("*.kt")), here / "PopupWindowIntegration.kt"]}

    def run(sources_snapshot, build, case=None, capture=False):
        sources = []
        for name, data in sources_snapshot.items():
            target = build / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            sources.append(target)
        resources = sorted(set(re.findall(r"R\.(\w+)\.(\w+)", b"\n".join(sources_snapshot.values()).decode())))
        resource = build / "R.kt"
        groups = sorted({group for group, _ in resources})
        resource.write_text("package me.kavishdevar.librepods\nobject R {\n" + "\n".join(
            f"object {group} {{\n" + "\n".join(f"const val {name} = {i}" for i, (kind, name) in enumerate(resources, 1) if kind == group) + "\n}"
            for group in groups) + "\n}\n")
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
                   "me.kavishdevar.librepods.presentation.overlays.PopupWindowIntegrationKt"]
        if case:
            command.append(case)
        return subprocess.run(command, text=True, stdout=subprocess.PIPE if capture else None,
                              stderr=subprocess.STDOUT if capture else None)

    with tempfile.TemporaryDirectory(prefix="librepods-popup-window-") as temp:
        build = Path(temp)
        result = run(snapshot, build / "baseline", args.case)
        if result.returncode or not args.negative_controls:
            raise SystemExit(result.returncode)
        mutations = (
            ("skip_failed_open_cleanup", "releaseWindow()\n            return false", "return false",
             "receiver_registration_failure_rolls_back_attachment", "Overlay leaked"),
            ("skip_visibility_restore", "mView.visibility = View.VISIBLE", "// Deliberately leave the view hidden",
             "closed_window_reopens_visible_with_fresh_resources", "Reopened window remains hidden"),
            ("ignore_outside_touch", "if (event.action == MotionEvent.ACTION_OUTSIDE)", "if (false)",
             "outside_touch_dismisses_window", "Outside touch was ignored"),
            ("lose_partial_registration", "batteryUpdateReceiver = receiver", "// Deliberately lose registration ownership",
             "partial_receiver_registration_failure_releases_dispatcher", "Battery receiver leaked"),
        )
        name = names[0]
        for control, original, replacement, case, assertion in mutations:
            source = snapshot[name].decode()
            if source.count(original) != 1:
                parser.error(f"Negative-control anchor changed: {control}; no production sources were edited")
            broken = dict(snapshot)
            broken[name] = source.replace(original, replacement).encode()
            result = run(broken, build / control, case, capture=True)
            expected = f"FAIL {case}: {assertion}"
            if result.returncode == 0 or expected not in result.stdout.splitlines():
                print(result.stdout, flush=True)
                raise SystemExit(f"Negative control did not fail its intended assertion: {control}")
            print(f"PASS negative_control_{control}: {expected}", flush=True)


if __name__ == "__main__":
    main()
