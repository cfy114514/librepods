#!/usr/bin/env python3
"""Run battery observer regressions and compiled mutations using the cached source harness."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile

from run import BATTERY_SOURCES


def main():
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-root", type=Path, default=here.parents[1])
    parser.add_argument("--health-root", type=Path)
    parser.add_argument("--compiler-lib", type=Path)
    options = parser.parse_args()
    android_root = options.android_root.resolve()
    command = [sys.executable, str(here / "run.py"), "--android-root", str(android_root)]
    for name in ("health_root", "compiler_lib"):
        value = getattr(options, name)
        if value is not None:
            command.extend(["--" + name.replace("_", "-"), str(value.resolve())])
    # Prove the unmodified production snapshot passes before testing isolated broken copies.
    subprocess.run(command, check=True, timeout=180)
    relative = "data/BatterySessionObserver.kt"
    source_dir = android_root / "app/src/main/java/me/kavishdevar/librepods"
    sources = {name: (source_dir / name).read_bytes() for name in BATTERY_SOURCES}
    print(f"Observer baseline SHA256 {hashlib.sha256(sources[relative]).hexdigest()}", flush=True)
    blocked_case = "battery_blocked_availability_cannot_freeze_display_flow"
    mutations = (
        ("display_clock_not_collected", blocked_case, (
            ("combine(snapshots, clock, availability) { snapshot, _, _ ->",
             "combine(snapshots, availability) { snapshot, _ ->"),
        )),
        ("slow_read_retimestamps_its_result", blocked_case, (
            ("private class Read(val generation: Any, val startedAt: Long)",
             "private class Read(val generation: Any, var startedAt: Long)"),
            ("publish(read, available)", "read.startedAt = elapsedRealtime()\n            publish(read, available)"),
        )),
        ("retired_lifecycle_read_can_publish", "battery_lifecycle_stop_fences_blocked_reads_and_recovery", (
            (" && generation === read.generation", ""),
        )),
    )
    with tempfile.TemporaryDirectory(prefix="librepods-battery-negative-") as temporary:
        for name, case, replacements in mutations:
            content = sources[relative].decode()
            for original, replacement in replacements:
                if content.count(original) != 1:
                    parser.error(f"Negative-control anchor changed; review {name}. No production files were edited.")
                content = content.replace(original, replacement, 1)
            root = Path(temporary) / name
            for path, data in sources.items():
                destination = root / "app/src/main/java/me/kavishdevar/librepods" / path
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(content.encode() if path == relative else data)
            print(f"Checking isolated battery negative control: {name}", flush=True)
            result = subprocess.run(command + ["--battery-root", str(root), "--case", case],
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
            failures = [line for line in result.stdout.splitlines() if line.startswith(f"FAIL {case}:")]
            if result.returncode == 0 or not failures:
                print(result.stdout, flush=True)
                raise SystemExit(f"Mutation did not fail its intended runtime assertion: {name}")
            print(f"PASS negative_control_{name}: {failures[0]}", flush=True)


if __name__ == "__main__":
    main()
