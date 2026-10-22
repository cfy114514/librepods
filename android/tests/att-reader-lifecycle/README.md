# ATT reader lifecycle regression

Run from any directory:

```sh
python3 /path/to/librepods/android/tests/att-reader-lifecycle/run.py
```

The Android Debug CI workflow runs this harness after the JVM unit tests.

The script compiles the current production `ATTManager.kt`, `AttResponseMailbox.kt`,
`BoundPacketWriter.kt`, and `OwnedCallbackSlot.kt` directly, then runs seven JVM
assertion tests. It does not invoke Gradle, download dependencies, or access
Bluetooth hardware. Compilation output is temporary and removed when the script
exits.

A JDK and cached Kotlin compiler jars version 2.2 or newer (for `toHexString`)
from a Gradle distribution are required. Discovery checks `GRADLE_USER_HOME` (or `~/.gradle`) and
`/workspace/gradle-cache`. For another location, pass
`--compiler-lib /path/to/gradle/lib`. To check a separate source tree, pass
`--android-root /path/to/other/android`.

The tests cover EOF, a read exception, EOF with a pending request, repeated stop,
a delayed retired reader, replacement while the old socket close is blocked, and
a detached reader finishing after the same socket receives a new reader.
Latches coordinate the actual production reader thread and request thread;
joining the reader verifies that its `finally` block completed before assertions.
Timeouts bound failures and are not used to establish event ordering.

The small Android API substitutes and fake streams model transport events only.
They do not duplicate manager lifecycle logic. Passing these tests establishes
the JVM lifecycle behavior under those events; it does not validate Android's
Bluetooth implementation or real-device transport behavior.
