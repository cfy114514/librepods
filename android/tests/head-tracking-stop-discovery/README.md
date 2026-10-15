# Head-tracking STOP and service discovery regression

Run from any directory:

```sh
python3 /path/to/librepods/android/tests/head-tracking-stop-discovery/run.py
```

The runner uses Java and cached Kotlin compiler jars from a Gradle distribution.
It checks `GRADLE_USER_HOME` (or `~/.gradle`) and `/workspace/gradle-cache`, as the
ATT reader harness does. Override discovery with `--compiler-lib /path/to/gradle/lib`.
Use `--android-root /path/to/other/android` to check another source tree. Missing
sources, compiler dependencies, or unique extraction boundaries fail explicitly.
Compilation output is temporary and removed on exit. No Gradle invocation,
downloads, Android service, Bluetooth socket, or hardware are involved.

Each run mechanically inserts the current production `HeadTrackingRequest`,
`enqueueHeadTrackingCommand`, `headTrackingRequestCurrent`, complete
`writeHeadTrackingRequest`, and service-discovery callback into the JVM fixture.
It also inserts the AACP head-tracking dispatch branch, header check, original
packet builders, and peer-identity function. Only method/class visibility and
the discovery callback's `override` modifier change. Unique declaration
boundaries are required; unresolved placeholders or changed APIs fail extraction
or compilation instead of using a saved copy of the behavior. Source SHA-256
values are printed with the results.

The production `HeadTrackingSession`, `HeadTrackingStartup`, `KeyedWorkSession`,
`RtBuddyHeadTracking`, `HeadTrackingMotion`, and `HeadTracking` implementations
compile directly. A thin queue observer records the actual offered/consumed
request objects while forwarding scheduling and replacement to `KeyedWorkSession`.
An admission hook can insert a newer opposing request just before an older
producer reaches the queue. It clears before reentry, and both `offer` and
`offerIf` forward to the production queue; rejected admissions are not recorded
as accepted offers. These cases verify that an invalidated producer cannot evict
the newer command before execution.
The Android service owner, preferences, socket identity/connection check, AACP
session storage, logging, and output stream are test substitutes. The fake send
operation invokes the real service's final `canSend` callback before recording
packet bytes. Command/discovery ordering is explicit through manual dispatchers;
startup timeout jobs remain queued until a test explicitly resumes their delay,
and outstanding jobs are cancelled at cleanup. The retry admission case runs the
production timeout coroutine and inserts discovery before its queue admission.
Other fallback cases submit the existing retry request shape and run the real
write method. These tests do not validate elapsed wall time or physical fallback
behavior.

Every case prints `PASS` or `FAIL`. Any failed assertion returns a nonzero exit
status, including failures collected from the command and startup coroutines.
The tests cover service discovery before and during a queued STOP's
send, both legacy packet variants, an applied fallback variant, unchanged IDs,
stale request/peer/socket/session/service/closed-state rejection, a new START
replacing or interrupting a STOP, discovery rescheduling START, and shared
preview/gesture ownership. A completed STOP is not retargeted when a descriptor
changes; a subsequent START uses the newly discovered service. Fixed byte
fixtures preserve existing STOP packets, including the primary legacy `0x4e`
field, without proposing protocol changes.

The `null -> 16`, `null -> 15`, and `15 -> 16` descriptor transitions are
controlled software inputs. Repository acceptance evidence for A3441 confirms
service 15; legacy requests for 14/16 only returned acknowledgements without
motion. Passing this harness proves request/packet handling under those inputs,
not sustained AirPods 5 sampling, service-ID changes on real firmware, or the
hardware meaning of an old service ID after rediscovery.
