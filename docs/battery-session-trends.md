# Battery session trends

The battery session card shows separate left and right trends from actual battery
reports received during the current live control connection. It does not sample
the displayed battery percentage, widget state, BLE proximity cache, optimistic
state, or saved `BatteryHistory`. The existing last-reported battery feature and
its persistence remain separate.

The chart shows the current uninterrupted discharge segment, with elapsed time on
the horizontal axis and a fixed 0–100% vertical scale. The text also reports its
first and last percentages and observed duration. Charging and interruptions hide
the old segment from the chart and estimate. Points from earlier segments may
remain in the bounded memory snapshot until eviction or connection replacement;
they cannot enter the current estimate.

## Ownership and integration

`BatterySessionStore` is a process-wide, memory-only repository. The service claims
one `BatterySessionRepository.Lease` on creation and closes it on destruction.
For each accepted physical control connection, call `lease.connect(peer)` once
and retain the returned `Connection` alongside that connection's socket/session.
A reconnect, including to the same address, starts an empty series. Invalid peer
selection also retires the prior connection. A new service lease invalidates all
handles from its predecessor.

Capture that exact `Connection` and `SystemClock.elapsedRealtime()` at receive
time. After validating the current socket/session and selected peer, pass only
the raw packet's component readings to
`connection.recordReceived(readings, receivedAtElapsedMs)`. Do not obtain a new
connection handle for a delayed callback. Cached/UI refreshes must never call
this method. The integration's packet adapter validates the battery packet and
decodes the raw fields, bypassing `BatteryNotification`'s retained percentages.
Only left/right components are collected; case battery is not extrapolated.

Close the captured `Connection` at its disconnect or retirement. Permission loss,
Bluetooth becoming unavailable, peer selection changes, and service shutdown must
retire the relevant connection. Permission recovery alone cannot revive its old
segment. Old connection and lease closures cannot clear a successor's state.

All repository mutation and publication use one internal lock. Public operations
are safe from different threads; callers need no additional repository lock. The
clock callback must be nonblocking. The repository has no IO, worker, application
callback, background timer, or persistence. It does not acquire service, AACP,
socket, or UI locks.

`BatterySessionCard(address)` reads this shared repository and independently checks
the selected address and `AirPodsService.deviceObservationAvailable()`. That
service helper must retire observations when their physical availability is lost.
The card's display clock runs only while its lifecycle is started and reevaluates
expiry at least every 15 seconds. It never inserts observations. The pure
`batterySessionPresentation(address, snapshot, elapsedNowMs)` function also rejects
stale or future evidence on every call, even without new packets.

## Data and estimation rules

- Each earbud has its own receipt time, charging state, segment and series. An
  omitted component does not refresh that earbud. Duplicate components, sentinel
  levels, unknown statuses and disconnected reports interrupt the affected side.
  A valid 0% report is preserved.
- Receipt timestamps must be monotonic, at or after connection start, not in the
  future, and no more than 15 seconds old when delivered. Duplicate or reordered
  reports do not refresh the series. An elapsed-clock rollback discards both
  series and requires new observations.
- Charging (including optimized charging), a gap over five minutes, any increase,
  or a decrease larger than ten percentage points interrupts the affected
  discharge segment. UI evidence older than five minutes becomes stale.
- Each side retains at most 240 actual observations, and observations more than
  four hours old are evicted on the next accepted receive-path call. Unchanged
  percentages are retained at most once a minute; changed percentages are retained
  immediately. The exact latest receipt time is retained separately, so the chart
  can end on a genuine unretained report without inventing periodic samples.
- Runtime requires at least four points spanning 15 minutes, a decline of at least
  three percentage points and three distinct downward steps. A flat or short
  series shows an explicit collecting/insufficient-data message.
- The estimate uses the fastest qualifying suffix of the current segment, adds
  two percentage points of drain to account conservatively for coarse percentage
  reporting, reserves one percentage point, subtracts time since the last report,
  and rounds down to five-minute units. Results over 12 hours are suppressed as
  insufficient evidence. Every displayed result is explicitly labelled rough;
  a result below five minutes uses an under-five-minutes label.

This is an extrapolation from reported charge, not a statistical confidence bound,
a guarantee of playback duration, or a measure of active listening. It cannot
measure battery health, cycle counts or true capacity. Workload, microphone use,
settings, temperature and device reporting can change the actual remaining time.
No real-device validation is claimed.

## Verification

`BatterySessionRepositoryTest` and `BatterySessionPresentationTest` use a manually
advanced monotonic clock. They cover independent components, missing/duplicate and
invalid readings, valid zero, charging transitions, level jumps, exact freshness
boundaries, callback ordering, owner/session replacement, close fencing, rollback,
retention, insufficient-data gates, rounded estimates, recent faster drain, and
timestamps near `Long.MAX_VALUE`.

These JVM tests exercise the production repository and estimator. They do not
prove Android lifecycle wiring, UI rendering, firmware reporting or real Bluetooth
behavior. The normal Android build/lint and the separate receive-path integration
harness provide additional checks of those software boundaries.
