#!/usr/bin/env python3
"""Exercise actual IslandWindow receiver paths against modeled Android ownership."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def between(source, start, end):
    for marker in (start, end):
        if source.count(marker) != 1:
            raise ValueError(f"Expected one source marker, found {source.count(marker)}: {marker!r}")
    first, last = source.index(start), source.index(end)
    if last <= first:
        raise ValueError("Source markers are out of order")
    return source[first:last].rstrip()


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-root", type=Path, default=here.parents[1])
    parser.add_argument("--island-source", type=Path, help="Optional source snapshot for proving the regression")
    parser.add_argument("--compiler-lib", type=Path)
    parser.add_argument("--save-generated", type=Path)
    args = parser.parse_args()
    compiler_lib = args.compiler_lib
    if compiler_lib is None:
        roots = [Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")), Path("/workspace/gradle-cache")]
        jars = sorted({p for root in roots for p in root.glob("wrapper/dists/gradle-*-bin/*/gradle-*/lib/kotlin-compiler-embeddable-*.jar")})
        if not jars:
            parser.error("No cached Kotlin compiler; set --compiler-lib or GRADLE_USER_HOME. Nothing is downloaded.")
        compiler_lib = jars[-1].parent
    if not any(compiler_lib.glob("kotlin-compiler-embeddable-*.jar")):
        parser.error(f"No Kotlin compiler in {compiler_lib}")
    java = shutil.which("java")
    if not java:
        parser.error("java is required")
    libraries = []
    for pattern in ("kotlin-stdlib-*.jar", "annotations-*.jar"):
        matches = sorted(compiler_lib.glob(pattern))
        if not matches:
            parser.error(f"Missing {pattern} in {compiler_lib}")
        libraries.extend(matches)

    source_path = args.island_source or args.android_root.resolve() / "app/src/main/java/me/kavishdevar/librepods/presentation/overlays/IslandWindow.kt"
    source = source_path.read_text()
    replacements = {
        "CLASS": between(source, "class IslandWindow(", "    private val windowManager:"),
        "RECEIVER": between(source, "    private val batteryReceiver =", "    val isVisible:"),
        "SHOW_GATE": between(source, "    fun show(", "        val displayMetrics ="),
        "REGISTRATION": between(source, "        val batteryIntentFilter =", "        containerView.removeAllViews()"),
        "CLOSE": between(source, "    fun close()", "    private fun cleanupAndRemoveView()"),
        "CLEANUP": between(source, "    private fun cleanupAndRemoveView()", "    fun forceClose()"),
    }
    force_marker = "    fun forceClose()"
    if source.count(force_marker) != 1 or not source.rstrip().endswith("\n}"):
        raise ValueError("Expected forceClose as the final IslandWindow method")
    replacements["FORCE_CLOSE"] = source[source.index(force_marker):].rstrip()[:-1].rstrip()
    content = (here / "IslandReceiverRegression.kt.in").read_text()
    for key, value in replacements.items():
        marker = f"@@{key}@@"
        if content.count(marker) != 1:
            raise ValueError(f"Expected one template marker: {marker}")
        content = content.replace(marker, value)
    if "@@" in content:
        raise ValueError("Unresolved template marker")
    print(f"SOURCE {source_path} sha256={hashlib.sha256(source_path.read_bytes()).hexdigest()}", flush=True)
    if args.save_generated:
        args.save_generated.parent.mkdir(parents=True, exist_ok=True)
        args.save_generated.write_text(content)
    classpath = os.pathsep.join(map(str, libraries))
    with tempfile.TemporaryDirectory(prefix="librepods-island-receiver-jvm-") as temporary:
        build = Path(temporary)
        generated = build / "IslandReceiverRegression.kt"
        generated.write_text(content)
        classes = build / "classes"
        subprocess.run([java, "-cp", str(compiler_lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-nowarn", "-classpath", classpath,
                        "-d", str(classes), str(generated)], check=True)
        run = subprocess.run([java, "-cp", os.pathsep.join([str(classes), classpath]),
                              "me.kavishdevar.librepods.presentation.overlays.IslandReceiverRegressionKt"])
        raise SystemExit(run.returncode)


if __name__ == "__main__":
    main()
