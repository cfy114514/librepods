# BLE presence lifecycle regression

Run without Gradle, Android, network access, or downloaded dependencies:

```sh
python3 android/tests/ble-presence-lifecycle/run.py
```

The runner uses Java, the Kotlin compiler, and coroutines core cached in a Gradle distribution. Set
`GRADLE_USER_HOME` or `--compiler-lib /path/to/gradle/lib` if needed. It prints input
SHA-256 hashes and fails when extraction markers are missing or ambiguous. Use
`--service-source`, `--ownership-source`, `--queue-source`, and (when applicable)
`--deadline-source` to replay immutable older sources;
`--save-generated /path/to/Regression.kt` retains both extracted Kotlin files.

The main harness compiles the actual service BLE listener, presence/presentation
helpers, notification update, takeover admission/guards, explicit audio connection
methods, saved-device reconnect continuation, profile offer/dispatch and operation
completion, revocation, stale-watch cleanup, and control retirement. Method bodies
and synchronization annotations are unchanged; the extracted operation completion
callback is assigned to a local variable for invocation. It also compiles the actual
`AudioProfileOwnership`, `PendingProfileRequests`, and `AudioConnectDeadline` classes.
Android collaborators are small JVM fakes; profile policy and connect calls still
go through the production reflection path and real pending-request dispatch loop.

Cases place BLE disappearance before selection, between selection and ticket,
between ticket and profile offer, after offer, and during policy execution. They
check that explicit requests survive while real disconnect, user revoke, changed
selection, and service close still reject stale work. Further cases cover live
control immunity, presentation cleanup, automatic socket/takeover cancellation,
fresh automatic work, accepted release grants, and expired speculative claims.

Fresh BLE status is also delivered while direct, takeover, and saved reconnect
audio requests wait for profiles. Completion cases cover sibling profiles finishing
separately, rejected queue entries, rejected or throwing platform actions, skipped
permissions, replacement, producer failure, service closure, and delivered actions
cancelled before execution. The actual completion callback and queue discard hooks
settle these requests. Framework profile rejection is simulated at the queue boundary.
Permission-lookup hooks also pause older connect and disconnect producers while a
new manual request queues its profiles, checking that stale producers cannot replace it.

A virtual coroutine dispatcher and delay clock execute the production deadline.
Cases exercise its ten-second boundary, missing callbacks, partial completion,
timeout during a policy action, late callbacks, and newer-request isolation. Stale
callback checks occur before admitting a fresh passive claim, so the latter cannot
mask a broken timeout. Event and profile-delivery loops are bounded.

The publication harness compiles the real `ConnectionAttempts` and
`CloseableResourceScope`, plus the service's exact socket publication block. It
holds the socket-manager monitor and observes the worker blocked on that specific
monitor after passing the outer guard, then invalidates the source before allowing
publication. It verifies the inner guard rejects automatic work and permits manual
work. Thread waits are bounded. Source invalidation is controlled in this focused
harness; the main harness separately verifies the real BLE callback/guard wiring.

This does not exercise Android scan timing, Binder, native socket I/O, profile
acquisition by the framework, real coroutine thread scheduling, or notification
rendering. The saved reconnect's control-attempt completion is supplied by the
harness. Connection-attempt and pending-acquisition bookkeeping have separate unit tests.

Adapter-stop integration also extracts the real `ACTION_STATE_CHANGED` branch.
Stopping scanning retires socketless BLE automatic sources and speculative claims;
explicit audio requests and a live control source retain their existing semantics.
Four cases cover those boundaries. The pre-integration service snapshot compiles
and fails the two automatic-source cases, proving the adapter path is exercised.
