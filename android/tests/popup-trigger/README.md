# Popup trigger and service scan lifecycle regression

Run `python3 android/tests/popup-trigger/run.py` from the Android directory's parent, or
`python3 tests/popup-trigger/run.py` from `android`. The runner uses the cached Kotlin
compiler and downloads nothing. `--service-source` replays an immutable old service;
`--save-generated` saves the extracted Kotlin. Every source hash is printed.

The harness extracts the actual service popup fields/helpers, lid callback, BLE scan
owner/worker and adapter-state branch. It runs actual `BleLidState` transition dedup
and `CoalescedWorkSession` on controlled Main/IO queues. Window, preferences, adapter,
and service ownership are bounded collaborators. Scan registration and platform
callback ownership are additionally tested against full production `BLEManager` in
`tests/ble-scan-ordering`; the full window implementation is tested separately.

The 26 cases cover initial lid opening, manual/automatic dismissal, repeated lid
cycles, unchanged-advertisement dedup, obsolete close callbacks, failed attachment,
constructor failure, queued permission/preference/peer/key/source/service retirement,
Bluetooth-disabled cold start, repeated ON, coalesced OFF/ON, and queued shutdown.
The baseline service compiles and fails 15 of the 19 popup cases; scan lifecycle
cases apply only to the new owner. No fake adds a missing popup reset or queue guard.

These checks do not establish Android Binder timing, overlay OEM restrictions, actual
BLE delivery, or physical lid detection. The service still responds to authenticated
BLE lid transitions; an AACP reconnect alone does not force a bottom popup. Missing
permission or disabled settings do not replay the old opening when later enabled.
BLE's existing timeout can infer a closed lid after advertising silence.
