# Battery sessions and connection health integration

Run against an integrated checkout:

```sh
python3 android/tests/session-health/run.py --negative-controls
```

The runner invokes a cached Kotlin compiler directly. It does not use Gradle,
download dependencies, access Bluetooth hardware or require an Android device.
It needs Java and a cached Gradle distribution containing Kotlin 2.2 or newer and
`kotlinx-coroutines-core-jvm`. Compiler discovery checks `GRADLE_USER_HOME` (or
`~/.gradle`) and `/workspace/gradle-cache`; `--compiler-lib /path/to/gradle/lib`
overrides that search.

To run these tests against another checkout:

```sh
python3 android/tests/session-health/run.py --android-root /path/to/other/android
```

During parallel development, `--battery-root` and `--health-root` can separately
select Android roots for those feature sources. A normal integrated run needs
neither override. `--case case_name` runs one case. Every run prints the production
roots, Git revisions, dirty state and SHA256 of each source. The compiler receives
temporary copies of the exact bytes that were hashed. Compilation output and
mutations are deleted on exit.

The 32 cases execute actual production implementations of `AACPManager`,
`BatterySessionPacketRecorder`, `BatterySessionRepository`, battery presentation,
`BatterySessionObserver`, `ConnectionHealthMonitor`, `ConnectionHealthObserver`, the public Android platform
reader and manual action functions. Their relevant dependencies, including the
ordinary `BatteryNotification` cache, are also actual sources.

The battery cases cover:

- Raw incoming frames are the only source of new samples. Outgoing writes,
  notification-cache changes, repeated flow subscriptions, reads and advancing
  display time cannot create observations.
- Invalid lengths, header, opcode, subopcode, component count and absent connection
  are rejected. Retired AACP generations never invoke the battery consumer.
- A callback already admitted by the parser cannot publish after a same-peer
  reconnect or service recreation. A delayed older callback cannot overwrite a
  newer report. Latches establish ordering without timing-dependent sleeps.
- Literal wire frames permute case/left/right component order. Left charging,
  discharge resumption, unknown percentage and duplicate component IDs cannot
  alter the right bud's independent segment or import cached percentages.
- Delivery age, future stamps, silence, receive gaps and monotonic-clock rollback
  cannot bridge a discharge window. Published samples do not alias wire buffers.
- A 600-report session stays within 240 points per bud and four hours of retained
  data. Every retained or presented point maps to an actual receipt. A new
  same-peer connection starts empty, and estimates remain bounded. Known sustained
  drain fixtures check estimates against independent constant-drain projections.
- A blocked availability read cannot block display aging, charging or disconnect
  updates. Stop/start fences its late result and admits only one query at a time;
  availability keeps its query-start timestamp. Cancellation releases its slot.

The health cases cover:

- Media, calls, control transport and service response remain distinct. An open
  socket alone says waiting for data; only an incoming event says receiving data.
  A connected audio profile does not claim active playback or a working call.
- Absent, partial, expired or failed platform reads stay unknown. Permission
  denial and adapter shutdown require explicit evidence. Loss and recovery retire
  prior profile queries, selections and control tokens.
- Repeated refresh cannot refresh old profile events or incoming-packet evidence.
  Service and platform samples expire independently, including when a newer read
  stalls. Lifecycle stop immediately clears cached evidence, and an in-flight read
  cannot restore it afterward.
- Newer broadcasts beat already-running queries. A first query on a previously
  unobserved profile can succeed. Peer/socket replacement, service recreation,
  observer restart and out-of-order read completion reject late evidence.
- The real public platform reader skips adapter access without permission, keeps
  unrelated signals independent and preserves the start timestamp of a slow read.
- Manual settings navigation launches one requested destination. Missing handlers,
  rejected launches and exceptions return failure without retries or fallback
  navigation. Diagnostics uses its own callback.

Android stubs supply framework method surfaces and controlled query/launch
results. They contain no packet parsing, estimation, freshness, session or
observer policy. The battery fixture supplies the callback routing and the
connection token/receipt timestamp captured by its simulated reader. It does
**not** compile `AirPodsService`, the ViewModel, the Compose card or its lifecycle
coroutines. Therefore these tests do not establish their complete wiring,
rendering, Android Binder behavior, real profile broadcasts, firmware accuracy or
physical battery runtime. Those boundaries require code review, the normal
Android build/tests and device validation. The legacy `disconnect-notification`
and `ble-presence-lifecycle` harnesses separately execute their extracted service
paths; their diagnostic collaborators are stubs, while this harness uses the real
diagnostic implementations.

With `--negative-controls`, a passing normal run is followed by two compilable
mutations confined to temporary source copies:

1. Remove the repository's connection-token check. The blocked callback across a
   same-peer reconnect must fail its runtime assertion.
2. Reintroduce the mixed `Long`/`Int` fallback in the profile version check. The
   first current query on an unobserved profile must fail its runtime assertion.

A compiler error is not a passing negative control. Each mutation must fail its
named assertion. Changed source anchors stop the runner for review rather than
silently skipping a control.

`python3 android/tests/session-health/battery-negative-controls.py` separately
checks three compilable observer mutations: omitting display-clock collection,
renewing availability at query completion, and accepting a retired lifecycle
read. Each must fail its intended runtime assertion.
