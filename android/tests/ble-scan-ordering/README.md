# BLE scan ordering regression

Run from any directory:

```sh
python3 /path/to/librepods/android/tests/ble-scan-ordering/run.py
```

The script compiles the actual production BLE manager, timestamp gate, batch
deduplicator, lid state, maintenance task, proximity parser, and cryptographic
helpers. Twenty-nine JVM cases exercise the registered scan callbacks with controlled
observation and delivery times. Both rotating addresses pass the production RPA
verifier with the configured IRK; the harness does not replace authentication or
copy scan-processing logic.

Coverage includes older and equal observations, rotating addresses within one
batch, the existing 15-second presence boundary, future/negative timestamps,
invalid advertisements, failed RPA verification, scan restart, timeout cleanup,
IRK changes, newer unchanged observations, and nanosecond ordering when two
addresses have the same millisecond observation time. Accepted delayed batches retain
the existing arrival-based lid grace period.

Identity transition cases also verify that a current disconnected observation on
a new address reaches the status listener, known addresses compare against the
latest accepted identity state, and unchanged rotations do not repeat transitions.
First-observation and new-address callbacks retain their existing semantics.

Scan lifecycle cases verify idempotent startup, an initially disabled adapter,
an unavailable scanner, explicit stop/restart, rejected scan ownership, ownership
invalidated while startup is in flight, callbacks delivered before queued stop,
synchronous scan failure, and partial registration cleanup after a startup
exception. Explicit stop clears cached presence and rearms the lid, while the
observation watermark continues rejecting delayed pre-stop observations. Adapter
broadcast handling and service dispatch are tested by the popup-trigger harness.

Latch-controlled start/stop calls also verify that a failure callback returns
while the simulated framework call is still blocked on its worker, and that
startup does not rearm maintenance after that failure. A retired failure callback
cannot stop a replacement scan. These tests exercise the production manager's
locks with separate worker and callback threads, without substituting lifecycle
logic or assuming Android framework calls return promptly.

Only Android API objects and the monotonic clock are substituted. These tests
establish manager behavior for the supplied callbacks; they do not validate
Android's native scanner or real AirPods radio traffic. No hardware, Gradle, or
network downloads are used.

A JDK and cached Kotlin compiler jars version 2.2 or newer are required. Discovery
checks `GRADLE_USER_HOME` (or `~/.gradle`) and `/workspace/gradle-cache`. Pass
`--compiler-lib /path/to/gradle/lib` for another compiler location, or
`--android-root /path/to/android` to exercise a separate source snapshot, including
the pre-fix source. Use `--manager-source /path/to/BLEManager.kt` to override only
the manager for a negative control. Source SHA256 values are printed for evidence.
Compilation output is temporary and removed on exit.
