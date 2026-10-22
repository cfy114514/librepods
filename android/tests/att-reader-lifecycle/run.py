#!/usr/bin/env python3
"""Run ATT JVM tests with Java and cached Kotlin >= 2.2; no Gradle or downloads."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-root", type=Path, default=here.parents[1])
    parser.add_argument("--compiler-lib", type=Path, help="Gradle distribution lib directory containing Kotlin compiler jars")
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
    source = options.android_root.resolve() / "app/src/main/java/me/kavishdevar/librepods"
    sources = [source / "bluetooth/ATTManager.kt", source / "bluetooth/AttResponseMailbox.kt",
               source / "bluetooth/BoundPacketWriter.kt", source / "utils/OwnedCallbackSlot.kt"]
    sources += sorted((here / "stubs").glob("*.kt")) + [here / "AttReaderLifecycleRegression.kt"]
    for path in sources:
        if not path.is_file():
            parser.error(f"Missing source: {path}")
    library_jars = [*compiler_lib.glob("kotlin-stdlib-*.jar"), *compiler_lib.glob("annotations-*.jar")]
    classpath = os.pathsep.join(map(str, library_jars))
    with tempfile.TemporaryDirectory(prefix="librepods-att-jvm-") as build:
        classes = Path(build) / "classes"
        compile_command = [java, "-cp", str(compiler_lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                           "-no-stdlib", "-no-reflect", "-nowarn", "-classpath", classpath,
                           "-d", str(classes), *map(str, sources)]
        print(f"Compiling production source: {sources[0]}", flush=True)
        subprocess.run(compile_command, check=True)
        run = subprocess.run([java, "-cp", os.pathsep.join([str(classes), classpath]),
                              "me.kavishdevar.librepods.bluetooth.AttReaderLifecycleRegressionKt"])
        raise SystemExit(run.returncode)


if __name__ == "__main__":
    main()
