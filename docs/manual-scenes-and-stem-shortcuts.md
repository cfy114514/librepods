# Manual scenes and stem shortcuts

This iteration adds manual controls to the existing AirPods settings screen. It
does not add automatic scene switching, new firmware capabilities, or a way to
start ChatGPT voice mode directly.

## Scene presets

Open **Scene presets** in the connected AirPods settings. Choose Focus, Travel,
or Conversation, review the listed settings, and tap the named Apply button.

| Preset | Noise control | Conversation Awareness | Personalized Volume |
| --- | --- | --- | --- |
| Deep focus | Noise cancellation | Off | Off |
| Balanced focus | Adaptive | Off | Off |
| Quiet journey | Noise cancellation | Off | On |
| Aware journey | Transparency | On | On |
| Talk nearby | Transparency | On | Off |

The adaptive preset is hidden on models without adaptive listening support.
Unsupported or locked optional settings are listed as not included and remain
unchanged. The existing entitlement rules still apply. Model support does not
prove that every firmware accepts or reports a setting.

Each attempted setting has its own result:

- **Target value observed**: AirPods reported the requested value after dispatch.
  AACP has no per-command transaction identifier; this is not a causal command
  acknowledgement. The ordinary optimistic application cache cannot produce
  this result.
- **Failed**: the setting could not be sent.
- **Unconfirmed**: the outcome remains unknown, including a missing or different
  report or a write failure after dispatch began. The setting may have changed.

The attempt is bounded to eight seconds, including queue time. Changes can apply
partly and are not rolled back. Settings stay changed until changed again. A
retry after an unresolved write remains conservative on the same connection
within the current settings session because a delayed report could belong to the
earlier attempt. A fresh settings ViewModel starts a new history even if the
foreground service still holds the connection; the history is not durable across
that recreation. Every observed result remains a value observation, never a
command acknowledgement. Connection and selection changes retire pending work;
results from the previous connection are labeled. Demo mode sends no changes.

## Stem shortcuts

Open either side's press-and-hold settings and choose **App or shortcut**. Select
a launchable installed app or an available public Android action: **Open camera**
or **Show alarms**. Left and right targets are saved independently. The picker
does not enumerate arbitrary launcher-only dynamic or pinned app shortcuts.

With LibrePods visible and the phone unlocked, a press requests the action. In
the background, it posts a normal notification; unlock and tap it to continue.
Allow notifications, including the Stem shortcuts notification channel, for this
background path. If notifications are blocked, a message explains how to proceed
through the app or notification settings. No overlay, full-screen notification,
lock-screen dismissal, or background-launch exemption is used.

If the selected target disappears or refuses the launch, LibrePods requests the
configured default assistant through the public foreground assist API, then the
ASSIST and VOICE_COMMAND intents. If none is available, it prompts the user to
configure a default assistant. A nonthrowing launch is only a request; Android or
the target app may still prevent opening, and voice startup is not guaranteed.
Notification requests expire and are rechecked against the saved binding before
execution. Existing assistant assignments for other press types remain supported.

## Verification and platform limits

Run both flavor suites from `android/`:

```sh
./gradlew :app:testFossDebugUnitTest :app:testPlayDebugUnitTest
python3 tests/scene-feedback/run.py --negative-controls
```

The source harness compiles production AACP, transport and scene logic with small
Android surface stubs. Its README specifies the boundary; it does not emulate
Bluetooth firmware, Android activity management, real Binder, or a physical
device. Both FOSS and Play builds and lint must also pass before delivery.

Public Android contracts used here:
[Activity.showAssist](https://developer.android.com/reference/android/app/Activity#showAssist(android.os.Bundle)),
[background activity launch restrictions](https://developer.android.com/guide/components/activities/background-starts),
and [shortcut host permission](https://developer.android.com/reference/android/content/pm/LauncherApps#hasShortcutHostPermission()).
