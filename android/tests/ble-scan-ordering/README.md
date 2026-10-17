# BLE scan ordering regression

Run from any directory:

```sh
python3 /path/to/librepods/android/tests/ble-scan-ordering/run.py
```

The script compiles the actual production BLE manager, timestamp gate, batch
deduplicator, lid state, maintenance task, proximity parser, and cryptographic
helpers. Thirteen JVM cases exercise the registered scan callbacks with controlled
observation and delivery times. Both rotating addresses pass the production RPA
verifier with the configured IRK; the harness does not replace authentication or
copy scan-processing logic.

Coverage includes older and equal observations, rotating addresses within one
batch, the existing 15-second presence boundary, future/negative timestamps,
invalid advertisements, failed RPA verification, scan restart, timeout cleanup,
IRK changes, newer unchanged observations, and nanosecond ordering when two
addresses have the same millisecond observation time. Accepted delayed batches retain
the existing arrival-based lid grace period.

Only Android API objects and the monotonic clock are substituted. These tests
establish manager behavior for the supplied callbacks; they do not validate
Android's native scanner or real AirPods radio traffic. No hardware, Gradle, or
network downloads are used.

A JDK and cached Kotlin compiler jars version 2.2 or newer are required. Discovery
checks `GRADLE_USER_HOME` (or `~/.gradle`) and `/workspace/gradle-cache`. Pass
`--compiler-lib /path/to/gradle/lib` for another compiler location, or
`--android-root /path/to/android` to exercise a separate source snapshot, including
the pre-fix source. Compilation output is temporary and removed on exit.
