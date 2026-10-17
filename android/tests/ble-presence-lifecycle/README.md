# BLE presence lifecycle regression

Run without Gradle, Android, network access, or downloaded dependencies:

```sh
python3 android/tests/ble-presence-lifecycle/run.py
```

The runner uses Java and the Kotlin compiler cached in a Gradle distribution. Set
`GRADLE_USER_HOME` or `--compiler-lib /path/to/gradle/lib` if needed. It prints input
SHA-256 hashes and fails when extraction markers are missing or ambiguous. Use
`--service-source` and `--ownership-source` to replay immutable older sources;
`--save-generated /path/to/Regression.kt` retains both extracted Kotlin files.

The main harness compiles the actual service BLE listener, presence/presentation
helpers, notification update, takeover admission/guards, explicit audio connection
methods, profile offer/dispatch, revocation, stale-watch cleanup, and control
retirement. Method bodies and synchronization annotations are unchanged. It also
compiles the actual `AudioProfileOwnership` and `PendingProfileRequests` classes.
Android collaborators are small JVM fakes; profile policy and connect calls still
go through the production reflection path and real pending-request dispatch loop.

Cases place BLE disappearance before selection, between selection and ticket,
between ticket and profile offer, after offer, and during policy execution. They
check that explicit requests survive while real disconnect, user revoke, changed
selection, and service close still reject stale work. Further cases cover live
control immunity, presentation cleanup, automatic socket/takeover cancellation,
fresh automatic work, accepted release grants, and expired speculative claims.

The publication harness compiles the real `ConnectionAttempts` and
`CloseableResourceScope`, plus the service's exact socket publication block. It
holds the socket-manager monitor and observes the worker blocked on that specific
monitor after passing the outer guard, then invalidates the source before allowing
publication. It verifies the inner guard rejects automatic work and permits manual
work. Thread waits are bounded. Source invalidation is controlled in this focused
harness; the main harness separately verifies the real BLE callback/guard wiring.

This does not exercise Android scan timing, Binder, native socket I/O, profile
acquisition by the framework, coroutine scheduling, or notification rendering.
Fresh BLE status claiming ownership over a pending explicit request is a separate
behavior and is outside this expiry regression.
