# Popup behavior and verification

The bottom popup responds to an observed lid opening from the selected AirPods'
authenticated BLE advertisements. It requires the existing overlay permission and
the bottom-popup setting. An ordinary control-channel reconnect alone does not
force it open. The top island keeps its existing connection, takeover and
ear-detection conditions.

Closing a bottom popup releases that window's display state. Closing and reopening
the lid can therefore show another popup while the control connection stays alive.
Repeated advertisements for one lid state remain deduplicated. A late dismissal
from an older window cannot clear the replacement window's state. Queued work is
rechecked against the current service, selected peer, identity key, lid state,
setting and permission before attaching a view.

Window attachment reports success explicitly. A failed attachment or setup step
cleans up the receiver, timer, animation and video resources and does not mark the
popup as shown. Normal dismissal, timeout and outside touch close the window.
The island's battery receiver is registered and unregistered through the same
Context.

Bluetooth becoming enabled can restart an idle scan after a disabled cold start.
Duplicate enabled events do not replace an active scan. Scan work is serialized on
IO, with lifecycle generations rejecting obsolete requests and callbacks. Turning
Bluetooth off also retires the existing BLE-only automatic-connection eligibility;
the existing safeguards for explicit requests and a live control link remain.
There is no timer-based retry loop, new permission, overlay bypass or forced
background activity launch.

The source regression harnesses are `popup-trigger`, `popup-window`,
`island-receiver`, `ble-scan-ordering`, `ble-presence-lifecycle` and
`disconnect-notification`. They use production code with controlled Android
surfaces, including failed attachment, late callbacks, blocked scanner calls and
repeated lifecycle events. They do not establish physical lid detection, Android
Binder timing or Samsung overlay behavior.

Manual follow-up should cover repeated lid openings while connected, startup with
Bluetooth disabled followed by enabling it, overlay denial and a later fresh lid
transition, dismissal while reconnecting, and service recreation. Missing identity
keys, absent BLE advertisements, disabled permissions/settings and manufacturer
background restrictions can still prevent a popup. Restoring a permission or
setting does not replay an old lid opening, and a scan failure does not trigger an
unbounded automatic retry.
