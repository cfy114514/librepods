# Disconnect notification regression

`run.py` compiles the current service's receiver, registration filter, both SDK
registration branches, EOF sender notification block, optional shared disconnection
helper, popup method, A2DP watch
guards, user-revocation method, profile request class and profile-dispatch loop with controlled JVM
collaborators. It compiles the actual `AudioProfileOwnership.kt` and
`PendingProfileRequests.kt` unchanged.

Run with a cached Kotlin compiler and Java, without Gradle or downloads:

```sh
python3 run.py --android-root /path/to/librepods/android
```

`--service-source` accepts an old source snapshot to demonstrate the regression.
The script prints input hashes and fails if a production extraction boundary changes.
Only the revocation method's visibility is changed for test access; extracted method
bodies are unchanged. The actual registration calls bind the receiver to the tested
filter; cases run both API 32 and API 33 registration paths.
The request class is extracted from either its current class declaration or an older
data-class declaration. This harness uses its default completion callback; explicit
audio completion and deadlines are covered by `../ble-presence-lifecycle`.

The harness controls Main/IO execution and Intent delivery. It verifies actual
production subscription/receiver wiring rather than a test-only boolean deciding
whether duplicate cleanup should occur. The Android framework, JNI, native socket
I/O, real Binder/profile behavior and notification rendering are not exercised.
The stub notification update performs the production generation/ownership reset,
while its platform, history, widget and rendering effects are intentionally absent.
Old ownership and stale-watch retirement are also checked before testing newer work.
The popup window's `open` hook injects IO retirement before the actual service method
assigns `popupShown`, so an unsafe IO reset fails deterministically.

Static review must also verify that explicit disconnect and changed-peer retirement
call the same cleanup under their existing socket-ownership lock and preserve the
saved explicit-disconnect target; the executable sender fragment covers EOF cleanup.
