# Popup window lifecycle integration

```sh
python3 android/tests/popup-window/run.py --negative-controls
```

The runner compiles the complete production `PopupWindow.kt` and `Packets.kt`
with deterministic Android boundary doubles. It uses the cached Kotlin compiler,
without Gradle, downloads, credentials, overlay permissions or a device. Compiler
discovery checks `GRADLE_USER_HOME` (or `~/.gradle`) and
`/workspace/gradle-cache`; `--compiler-lib` overrides discovery.
`--android-root /path/to/android` selects another production checkout and
`--case name` runs a single case. Each run prints Git revision, dirty state and
SHA256 of the exact production bytes copied into its temporary compilation tree.

The 17 cases cover failed/partial overlay attachment, denied/partial receiver
registration, rejected timeout scheduling, media and animation failures, retry,
duplicate opening, the 12-second timeout, close-button and outside-touch
dismissal, close before opening, close during entry, reopening a hidden view,
video/artwork selection, battery receiver updates on both API branches and
constructor inflation failure. Time and animation completion are explicitly
advanced; tests use no sleeps. Failed opens must return false and release the
window, receiver, timer, animation and video listener/playback. Repeated closes
must notify once per successfully opened lifecycle.

Four optional negative controls mutate temporary copies only: omit failed-open
cleanup, omit visibility restoration, ignore outside touch, or discard partial
receiver-registration ownership. Each must compile and fail its exact named
runtime assertion. Compiler errors cannot count as passing negative controls.

The stubs supply method surfaces, resource IDs, window attachment bookkeeping,
registration failures, scheduling and animation completion. They contain no
popup lifecycle policy. This suite does not compile the service caller and does
not establish complete service wiring, real window rendering, animation timing,
Binder behavior, firmware behavior or physical touch delivery. Those boundaries
require service tests, the normal Android build and device validation.
