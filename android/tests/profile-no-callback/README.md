# Profile registration without a callback

This regression records a recovery limit and the bounds that a future recovery
change must preserve. It does not change automatic retry behavior.

## Framework evidence

In the tagged [Android 13 connector](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/android-13.0.0_r1/framework/java/android/bluetooth/BluetoothProfileConnector.java),
`connect()` registers the Bluetooth state callback and calls `doBind()` without
propagating its false result. The A2DP construction path can therefore return an
accepted profile registration while no proxy is delivered. A later Bluetooth-on
event can retry the same connector's bind. This describes a conditional framework
path; it does not establish its frequency on supported phones.

The application's ten-second deadline retires explicit request ownership. It
retains the pending framework registration, for which the public API has not yet
provided a proxy to close. A fresh user request can replace the pending action,
but cannot force that registration to produce a callback.

## Deterministic coverage

Run from `android/`:

```sh
./gradlew :app:testFossDebugUnitTest --tests '*AudioConnectDeadlineTest' \
  :app:testPlayDebugUnitTest --tests '*AudioConnectDeadlineTest'
```

The missing-callback tests compose the real ownership, deadline, request
queue and acquisition helpers with virtual time. After 100 timeout/retry cycles,
exactly one registration remains started and no deadline remains scheduled.
Additional idle time does not cause registration attempts. A delayed delivery
executes only the newest live request, or no action if all requests expired.
Once the caller reports completion of its proxy cleanup attempt, a fresh request
can acquire again. These helper tests do not execute Android, Binder, proxy
closure, or the full service callback wiring.

The mixed-profile tests exercise both A2DP/HEADSET orientations, assuming both
required permissions are granted. A healthy profile completes while the other
never delivers; the request remains active until its ten-second deadline. The
next request reacquires the completed profile while retaining the exact original
acquisition for the missing profile. Delivery at 19,999 ms executes only the new
request; delivery at 20,000 ms after expiry executes no stale action. Replaying
the old acquisition or completing old ownership cannot consume or expire the
third request. Each profile can then complete independently.

The test's completion input represents the caller's cleanup attempt having
finished. It does not establish that Android acknowledged successful closure.
Service identity checks, takeover generations, duplicate proxy identity handling,
Binder scheduling, and the actual `closeProfileProxy()` implementation are outside
this helper composition. In production, missing HEADSET permission skips that
acquisition and completes its part of the request instead.

## Recovery decision

Do not clear the global acquisition merely because ownership expired: another
registration would coexist with the original inaccessible registration. A retry
cap limits that accumulation but does not establish cancellation or safe late
callback ownership. The safe existing paths are eventual callback delivery and
framework recovery of the retained connector; the application does not toggle
Bluetooth automatically.

A future change needs explicit accounting for every outstanding registration,
arbitration of late callbacks, and evidence for the specific framework failure
being addressed. Neither service replacement nor Bluetooth-off acknowledges
cancellation: the Android 13 connector retains its state callback, and the
[Android 16 adapter](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/android-16.0.0_r1/framework/java/android/bluetooth/BluetoothAdapter.java)
retains profile entries for reconnection on Bluetooth-on.

The registration bound tested here assumes an accepted call without a callback.
It is not an unconditional bound across every framework failure. In Android 13,
`connect()` registers a state callback before `doBind()`; resolving multiple
system services can then throw `IllegalStateException`. Thus an exception alone
does not prove that no registration exists. The current application exception
handler rejects its acquisition slot, so an automatic retry policy must not use
that handler as evidence of successful cancellation. Likewise, advancing after
a close attempt is not proof of platform cleanup when close or unregister fails.

A controlled reproduction using the unchanged Android 13 connector, two injected
matching system services, and a mirror of the application's exception handler
observed three retained framework state callbacks after three fresh requests,
with zero delivered proxy handles. This establishes the conditional accounting
risk, not its reachability on supported phones or real Binder behavior.

These constraints do not establish a safe forced-reacquisition change. Retain
the accepted pending registration, retire expired ownership, replace desired
work, and permit independent-profile progress. No physical-device recovery or
universal resource-cleanup claim is made here.
