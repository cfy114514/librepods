# Service observation read ownership

Run against the integrated checkout:

```sh
python3 android/tests/observation-read/run.py --negative-controls
```

The runner uses a cached Kotlin compiler directly, without Gradle, downloads,
Bluetooth access or an Android device. Compiler discovery checks
`GRADLE_USER_HOME` and `/workspace/gradle-cache`; `--compiler-lib` overrides it.
Use `--android-root /path/to/android` for another checkout, `--service-source`
for an immutable old service snapshot, or `--case` for one named case.

The ten cases execute the actual extracted `AirPodsService` observation reads,
ownership helpers, invalidation, profile-query wrapper, `ControlObservations`,
and observation-publication block. They compile the actual battery repository,
battery history validation, battery value types, and connection health monitor.
Each source is read once, hashed, and compiled from a temporary snapshot.

Latches reproduce a framework read that has captured a result but has not yet
returned. A same-peer reconnect must retain its new battery connection and
control ticket when the old adapter result or profile permission failure resumes.
The cases also cover a first connection replacing an empty probe, an A-to-B-to-A
selection change without a control-owner change, service replacement and shutdown,
current adapter/permission loss, and ordinary successful profile observations.
Current losses must still retire both consumers; exception propagation is retained.

The two negative controls compile isolated temporary changes restoring the old
unconditional invalidation behavior for the adapter and profile paths. Each must
fail its named reconnect assertion. Compilation failure does not count as proof.
No production source is changed by the runner.

Framework stubs provide only permission values, adapter/profile query callbacks,
service identity and simple device/socket values. The publication block executes
under the same manager-lock boundary used by production. This harness does not
compile the full Android service, reproduce Android Binder scheduling, or validate
real hardware behavior. It deliberately preserves the existing policy that a
current failed/unknown adapter read conservatively retires observation segments.
