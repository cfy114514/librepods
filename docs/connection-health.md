# Connection health

The connection card reports four independent observations for the selected AirPods:

| Row | Positive evidence | What it does not establish |
| --- | --- | --- |
| Media audio | A recent A2DP connection-state broadcast, or a result from an already-owned profile query | Current media output or playback |
| Call audio | A recent HEADSET connection-state broadcast, or a result from an already-owned profile query | An active call, SCO audio, or the call output device |
| Control channel | Incoming bytes on the current, selected-peer control socket | Media or call routing, or confirmation of an individual command |
| Background service | A response from the current bound service after a successful foreground start | Uninterrupted execution or exemption from manufacturer battery limits |

Profile and control observations expire after two minutes using `SystemClock.elapsedRealtime`. Broadcasts and incoming packets retain their receipt timestamps; reused profile queries retain their start timestamps. Delayed processing or query completion cannot make an old observation fresh. A socket opened without inbound data displays **Waiting for data**. An idle socket with old data displays **Out of date**, with text explaining that silence does not prove failure. Missing evidence is **Unknown**, never disconnected. Android permission, adapter, and background-policy reads have a 15-second freshness limit. No health evidence is persisted.

The card samples every five seconds while its lifecycle is STARTED and it remains in composition. Returning from settings clears the previous display until a new read finishes. Manual refresh is a single conflated read request, with a one-second admission limit; it does not bind Bluetooth profiles or reconnect. Disposal closes the observer and its refresh channel. Read tickets reject results from a stopped, closed, replaced, or superseded observer.

The only actions are ordinary Android Bluetooth, app-details, and battery-optimization settings, plus existing diagnostics when available. A failed navigation displays an actionable error. The card requests no new permissions, sends no Bluetooth packets, changes no hidden profile policy, and adds no reconnect loop.

## Integration contract

`AirPodsService` owns a `ConnectionHealthMonitor(SystemClock::elapsedRealtime)` through its existing resource scope. The monitor must be selected from the authoritative saved peer after initialization and each selection change. `started()` belongs after a successful foreground start; `close()` retires all remaining tickets.

Feed all four connection states from the existing A2DP and HEADSET receiver. Capture monotonic receipt time and the selection token at receiver entry, then pass them to `profileChanged(selection, profile, state, receivedAt)`. Compare the broadcast device address with that captured selection's peer, and check current permission and service ownership before publishing. These broadcasts describe profile connections, not `ServiceListener` proxy binding events. Handle adapter-state changes before extracting `EXTRA_DEVICE`: adapter broadcasts have no device extra. On permission loss or adapter shutdown, call `invalidateBluetooth()` to retire even same-peer evidence and pending work. Repeated invalidation is idempotent until new evidence arrives.

When the current owned control socket is published, capture the current selection after the existing connection-attempt and selected-peer guards, use `controlOpened(selection)`, and retain the returned control ticket with that socket. Capture monotonic receipt time immediately after the socket read and feed incoming bytes through `controlPacket(ticket, receivedAt)` only after the existing socket, selection, and session checks pass. Close that exact ticket on teardown. The monitor rejects old selection, service, and control tickets, impossible timestamps, and receipts older than its current evidence. Timestamp publication is limited to once per second of receipt time to avoid allocations for every head-tracking packet.

Permission or adapter invalidation retires the current control observation handle and battery segment. Restoring permission alone does not revive either: a newly accepted physical control connection starts fresh observations. Existing profile broadcasts or already-required queries can independently provide new profile evidence. The feature does not reconnect automatically, and `isConnected` alone cannot restore a previous positive observation.

Availability probes capture the current control handle and health selection before calling Android. After the read, both owners and the live service must still match under the socket publication lock. A delayed adapter result or profile-query permission failure cannot retire observations belonging to a newer connection. Android calls remain outside that lock.

If an existing, already-owned profile query is reused, capture `beginProfileObservation(selection, profile)` before the query and publish with `profileObserved(ticket, result)`. The ticket keeps the query start time, so a result delayed past the evidence window is displayed as out of date. A newer broadcast or query result retires the older query ticket. Do not make a new `getProfileProxy` request for diagnostics: a framework acquisition with no callback cannot safely be canceled by this feature. Broadcast-only initial state is intentionally unknown.

The ViewModel provides a synchronous `() -> ConnectionHealthSnapshot?` reader guarded by its current binding and demo state. Check service ownership both before and after taking the snapshot. Read permission before returning and invalidate evidence on revocation; the UI independently displays platform permission status. `ObservedConnectionHealthCard` takes that reader and `selectedPeer`, `selectionVersion`, and `serviceBindingId`. Those three keys isolate UI observers across selection and service changes. A disconnected screen can use `connection_health_open` to open the same card in a scrollable dialog. Diagnostics are optional, so unsupported flavors need not display a dead destination.

## Verification

`ConnectionHealthMonitorTest` covers selection ABA changes, control replacement, no fabricated initial evidence, shutdown and late callbacks, permission/adapter invalidation, immutable snapshots, service replacement, and old queries losing to newer broadcasts. It also checks delayed receipt/query timestamps, impossible or older receipts, and packet timestamp coalescing. The source integration suite checks that a query or packet delayed past two minutes stays out of date after a fresh service/platform read. `ConnectionHealthTest` covers all four independent rows, stale evidence, missing permission, adapter off, unknown platform checks, background restrictions, peer mismatch, and clock boundaries. Run these with the existing FOSS and Play unit suites, then build and lint both flavors.

Device follow-up remains manual: open the card with Bluetooth off, revoke and restore Nearby devices, switch the selected AirPods, leave and return from settings, and let observations expire. Verify that media and call profile states never claim active routing, that no screen exit leaves diagnostic work running, and that manufacturer settings screens either open or show the failure message. No phone actions are required by the automated tests.
