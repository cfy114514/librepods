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
./gradlew :app:testFossDebugUnitTest :app:testPlayDebugUnitTest --tests '*AudioConnectDeadlineTest'
```

The two missing-callback tests compose the real ownership, deadline, request
queue and acquisition helpers with virtual time. After 100 timeout/retry cycles,
exactly one registration remains started and no deadline remains scheduled.
Additional idle time does not cause registration attempts. A delayed delivery
executes only the newest live request, or no action if all requests expired.
Once the caller reports the delivered resource closed, a fresh request can
acquire again. These helper tests do not execute Android, Binder, proxy closure,
or the full service callback wiring.

## Recovery decision

Do not clear the global acquisition merely because ownership expired: another
registration would coexist with the original inaccessible registration. A retry
cap limits that accumulation but does not establish cancellation or safe late
callback ownership. The safe existing paths are eventual callback delivery and
framework recovery of the retained connector; the application does not toggle
Bluetooth automatically.

A future change needs explicit accounting for every outstanding registration,
arbitration of late callbacks, and evidence for the specific framework failure
being addressed. The next useful regression is mixed-profile progress: one
profile delivers while the other never calls back. No physical-device recovery
claim is made here.
