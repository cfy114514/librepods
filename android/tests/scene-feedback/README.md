# Scene feedback integration

Run from any directory after the scene feature sources are present:

```sh
python3 /path/to/librepods/android/tests/scene-feedback/run.py
```

To test another worktree or run the two optional negative controls:

```sh
python3 android/tests/scene-feedback/run.py --android-root /path/to/other/android --negative-controls
```

The runner compiles the actual production `AACPManager`, `BoundPacketWriter`,
`ScenePresetController`, scene plans, model capability table and their small
dependencies. It invokes the cached Kotlin compiler directly, without Gradle,
downloads, a device or Bluetooth hardware. A JDK and a cached Gradle distribution
with Kotlin 2.2 or newer and `kotlinx-coroutines-core-jvm` are required. Compiler
discovery checks `GRADLE_USER_HOME` (or `~/.gradle`) and `/workspace/gradle-cache`;
`--compiler-lib /path/to/gradle/lib` overrides it.

Each run prints the source root, Git HEAD, dirty state and SHA256 of every
production source, then compiles temporary copies of the bytes read. The hashes
identify the tested source when a development worktree has uncommitted changes.
All compilation output and mutated copies are removed on exit. Use
`--case case_name` to run one named case.

The 32 integration cases check these contracts:

- Outgoing writes and ordinary optimistic cache/listener notifications cannot
  produce incoming device reports or advance the receive watermark.
- Incoming payloads are independently owned, malformed and retired reports are
  rejected, observers survive device sessions, and sequence numbers stay
  monotonic across a reset.
- Receive stamps precede ordinary callbacks. A callback that synchronously
  dispatches another command cannot make an earlier report look fresh.
- Dispatch occurs inside the actual output stream monitor after waiting and the
  final connection check. Watermark reads do not acquire the AACP monitor.
- Socket replacement, session reset, disconnect and cancellation while waiting
  for the stream prevent dispatch, writes and optimistic cache publication.
  Retirement during the dispatch callback is checked again before physical
  output. A superseding or expired attempt cannot revive confirmation through
  that callback, and observed retired dispatches retain conservative retry
  uncertainty.
- Reports arriving during `write` or from another thread during a blocked
  `flush` remain pending until successful write completion. A failed write or
  flush cannot confirm the setting. After dispatch, transport failure remains
  **unconfirmed** because some bytes may already have reached the device.
- A matching report received before dispatch cannot confirm, even if delivered
  after dispatch. A newer mismatching report wins over a delayed older match.
- A grouped preset can independently report applied, failed-before-dispatch and
  unconfirmed settings. Missing, unrelated and late reports cannot invent
  confirmation.
- Connection replacement, session reset, manual edits and superseding attempts
  invalidate earlier work and callbacks. An unresolved same-setting retry stays
  unconfirmed in the same connection while the same controller lives because AACP
  has no transaction IDs. Temporary context loss and selection-only changes do
  not clear that history. A fresh settings ViewModel creates a new controller and
  loses this history even if the service transport survives; these tests do not
  establish a durable connection-wide ledger or causal command acknowledgement.
- Real model capability and premium policies omit unsupported settings and hide
  unsupported scenes.

Latches and held stream monitors establish event order; timeouts only bound a
failure. The Android stubs provide socket properties, logging, resource IDs and
parcel annotations. They contain no parser, writer, confirmation or session
logic. The controller fixture supplies a small callback adapter and controlled
transport events. It does **not** compile `AirPodsService`, the ViewModel or UI,
so this harness cannot prove their callback wiring, lifecycle ownership,
rendering, firmware behavior or real Bluetooth transport behavior. Observer
close tests cover future packets, not revocation of an already captured callback.
Those boundaries need the normal Android build/tests, code review and device
checks. `APPLIED` means the target value was observed after dispatch and successful
write; it does not prove the scene caused that report.

With `--negative-controls`, a passing normal run is followed by two compilable
mutations made only in temporary source copies:

1. Publish an outgoing optimistic write through the receive path. The
   `outgoing_cache_and_listeners_never_become_reports` assertion must fail.
2. Remove the successful-write prerequisite for confirmation. The
   `matching_reply_during_failed_flush_cannot_apply` assertion must fail.

A compiler error does not count as a successful negative control. Each mutation
must fail its named runtime assertion. If its source anchor changes, the runner
stops and asks for mutation review instead of silently skipping the control.
