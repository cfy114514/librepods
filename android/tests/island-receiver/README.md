# Island receiver ownership regression

Run with Java and a cached Kotlin compiler, without Gradle or downloads:

```sh
python3 android/tests/island-receiver/run.py
```

`--island-source /path/to/IslandWindow.kt` tests an old snapshot with the same
harness. `--save-generated /tmp/IslandReceiverRegression.kt` exposes the compiled
test source. The runner prints the input SHA-256 and rejects changed extraction
boundaries.

The harness compiles the actual class header, battery receiver, `show` signature
and open-state gate, registration block, `close`, `forceClose`, and
`cleanupAndRemoveView` bodies. It preserves the constructor/parameter shadowing
that caused the regression. Unrelated show UI setup and rendering collaborators
are omitted or replaced with controlled doubles. Close animation completion and
Main-thread posts are explicitly drained.

The Context double models the Android boundary rather than executing Android:
`LoadedApk.getReceiverDispatcher` and `forgetReceiverDispatcher` index receivers
by their registering Context and receiver identity; `ReceiverDispatcher.Args`
delivers that Context to `onReceive`.
[AOSP LoadedApk.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/LoadedApk.java)
and [ContextImpl.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ContextImpl.java)
define this contract. A different Context cannot unregister a registration, and
the double throws instead of silently removing it. The fixture reproduces the
current service call site: constructor Application context, `show` Service context.

Both API 32 and API 33 branches cover normal and forced close, the disconnect
receiver callback, repeated show/close calls, replacement islands, and close calls
posted from a background thread. Tests assert no retained receiver or battery
delivery after close, and preserve filter actions and exported flags. They do not
test real Binder delivery, overlay permissions, animation appearance, window
attachment, or broader popup trigger policy. Existing caught exceptions from
already-unregistered receivers are printed only when a scenario fails.
