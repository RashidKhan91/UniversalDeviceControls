# Architecture

`DeviceControlManager` is the public façade; `DeviceControlKey` selects a control and the sealed `DeviceControlValue` prevents callers from sending arbitrary values. Each key is routed to a focused private operation. The result always separates requested value, observed value, status, and attempted strategy.

```text
App UI
  -> DeviceControlManager (type validation, result mapping)
     -> window APIs       SCREENSHOT, SCREEN_RECORDING, KEEP_SCREEN_ON
     -> Settings.System   TIMEOUT, BRIGHTNESS, AUTO_ROTATE
     -> DevicePolicy      AUTO_DATE_TIME, AUTO_TIME_ZONE, TIME_ZONE
     -> RootAccessManager fixed commands only; timeout + output + read-back
     -> Settings intents  radios, special settings grants, battery, DND
```

## Result and execution invariants

- `SUCCESS` is returned only after the post-operation value matches the requested value.
- A settings screen launch is a user-interaction step, not success. The app should query state after returning.
- Root is considered only for controls without an ordinary supported mutation path. Each shell string is formed internally from a fixed verb and typed value; time-zone IDs are validated against `ZoneId`.
- Root authorization is tested per operation; `su` presence is not treated as authorization.
- Device Owner and Profile Owner checks are separate from ordinary active-admin status. `ComponentName` is caller-supplied because the library cannot provision the app as an owner.
- Window flags scope to the supplied Activity and do not modify other applications.
- Reads are best-effort and may return `NOT_AVAILABLE` when hardware or runtime permission is missing.

## API compatibility baseline

Minimum SDK is 26 (Android 8.0). `compileSdk` is 36. Runtime branches guard modern API behavior; Android framework/OEM policy can still prevent changes. See the per-key table in the README for the safe mutation path and known limits.
