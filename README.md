# UniversalDeviceControls

A Kotlin Android library for inspecting and changing a deliberately limited set of app-window and system settings. Each request returns a structured status, the strategy attempted, and the state Android reports afterward. It does not bypass Android permissions or device policy.

> **Version:** `0.1.0`. Available through JitPack as `com.github.RashidKhan91.UniversalDeviceControls:devicecontrols:0.1.0`. Validate on your supported OEMs and management modes before production use.

## Modules

- `devicecontrols`: reusable Android library (`dev.universal.devicecontrols`)
- `sample`: small interactive app that displays current values and outcomes

## Install in a new Android app

Add JitPack to the repositories in your project-level `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
```

Add the library to the app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.RashidKhan91.UniversalDeviceControls:devicecontrols:0.1.0")
}
```

Sync Gradle. The library requires `minSdk 26` or higher. JitPack uses the multi-module coordinate format `group:module:version`; `devicecontrols` is the library module. See the [JitPack Android guide](https://docs.jitpack.io/android/) for repository setup details.

Initialize it with an Activity and call its suspend API from a coroutine, for example from `lifecycleScope`:

```kotlin
import androidx.lifecycle.lifecycleScope
import dev.universal.devicecontrols.DeviceControlManager
import dev.universal.devicecontrols.model.DeviceControlKey
import dev.universal.devicecontrols.model.DeviceControlValue
import kotlinx.coroutines.launch

private val controls by lazy { DeviceControlManager(this) }

private fun blockScreenshots() {
    lifecycleScope.launch {
        val result = controls.disable(DeviceControlKey.SCREENSHOT)
        statusText.text = "${result.status}: ${result.message}"
        if (result.isSuccess) {
            // Screenshot blocking was verified for this Activity's window.
        }
    }
}
```

To inspect current state or configure a typed setting:

```kotlin
lifecycleScope.launch {
    val wifi = controls.get(DeviceControlKey.WIFI)
    statusText.text = "Wi-Fi: ${wifi.actualValue ?: wifi.status}"

    val brightness = controls.set(
        DeviceControlKey.SCREEN_BRIGHTNESS,
        DeviceControlValue.Brightness(level = 160, automatic = false)
    )
    statusText.text = "${brightness.status}: ${brightness.message}"

    if (brightness.requiresSettings) {
        controls.openSettings(DeviceControlKey.SCREEN_BRIGHTNESS)
        // When the user returns, call get() again to verify the applied state.
    }
}
```

`set`, `get`, `enable`, `disable`, `isEnabled`, and `getCapabilities` are suspend APIs. Boolean operations use `BooleanValue`; timeout uses milliseconds in `IntValue`; time zones use an IANA identifier in `TextValue`; brightness uses `Brightness(level, automatic)`. A mismatched value is rejected. `enable`/`disable` for a non-Boolean numeric control uses documented sample defaults; prefer `set` when configuring those values.

Inspect `DeviceControlResult.status` and `actualValue`; only `SUCCESS` means the resulting state matched the request. `REQUIRES_SETTINGS` means call `openSettings(key)`, return to the app, and re-read. Android may clamp or override settings.

## Control coverage and restrictions

| Key | Read | Change path / limitation |
|---|---:|---|
| `SCREENSHOT` | Yes | `FLAG_SECURE` on this host Activity window only; `true` means screenshots allowed. |
| `SCREEN_RECORDING` | Yes | Same window flag; best effort, not a device-wide screen-recording policy. |
| `SCREEN_TIMEOUT` | Yes | `Settings.System`; user must grant special WRITE_SETTINGS access. System/admin may clamp value. |
| `SCREEN_BRIGHTNESS` | Yes | System brightness and auto/manual mode; requires WRITE_SETTINGS. Automatic mode ignores the requested manual level. |
| `AUTO_DATE_TIME` | Yes | Device/Profile Owner `DevicePolicyManager` (Android 11+) or authorized root; ordinary app cannot write global setting. |
| `AUTO_TIME_ZONE` | Yes | Device/Profile Owner `DevicePolicyManager` (Android 11+) or authorized root. |
| `TIME_ZONE` | Yes | Owner policy (Android 9+) or authorized root; validates IANA zone IDs. |
| `KEEP_SCREEN_ON` | Yes | Window flag for the visible host Activity only. |
| `AUTO_ROTATE` | Yes | Global system setting; requires WRITE_SETTINGS. Does not set Activity orientation. |
| `WIFI` | Yes | Legacy API only when target/platform allow it; modern apps use system settings or compatible authorized root. |
| `BLUETOOTH` | Yes* | Legacy API only where allowed; apps targeting Android 13+ cannot use adapter enable/disable unless qualified owner/system. Others use system settings or compatible authorized root. Android 12+ read requires `BLUETOOTH_CONNECT` runtime grant. |
| `BATTERY_OPTIMIZATION` | Yes | Opens the system battery optimization screen; exemption is user-reviewed and may be restricted by distribution policy. |
| `DO_NOT_DISTURB` | Yes | Requires Notification Policy Access granted by the user in Settings. Boolean true selects interruption filter NONE; false selects ALL. |
| `AIRPLANE_MODE` | Yes | Read-only for ordinary third-party apps; direct toggling is deliberately unsupported. |

No API can guarantee consistent outcomes across every OEM, custom ROM, administrator policy, or Android release. Root commands are fixed library-generated allowlisted commands, use `su`, time out, and still require read-back verification. Detecting root does not prove authorization or command support.

For apps targeting Android 15/API 35 or later, Android associates `setInterruptionFilter` behavior with an app-specific automatic Zen rule rather than granting unrestricted control of the device-wide notification policy. The result is based on observable interruption-filter read-back and can therefore report verification failure when a device or policy does not expose the requested state.

## Device/Profile Owner

Owner mode is not equivalent to ordinary device-admin activation. Provision the host app as a Device Owner/Profile Owner using Android's supported enterprise provisioning process, provide the app's active `DeviceAdminReceiver` `ComponentName` to `DeviceControlManager(activity, adminComponent)`, and test the supported policy on the target Android version. This library does not provision a device or install/activate an admin receiver on behalf of the app.

## Permissions and Settings

The library manifest declares the system permissions it uses, including `WRITE_SETTINGS`, `ACCESS_NOTIFICATION_POLICY`, Wi-Fi state, and Bluetooth. The host app must request runtime `BLUETOOTH_CONNECT` on Android 12+ before reading/toggling Bluetooth through legacy-compatible paths. For WRITE_SETTINGS and Notification Policy Access, call `openSettings(key)` and have the user grant access. Capability detection probes `su` and can trigger a root manager authorization prompt. Battery exemption request flows are subject to Android and Play policy; this sample opens the general battery-optimization settings page rather than silently requesting an exemption.

## Build, sample, tests

Requires JDK 17+ and Android SDK platform 36 for the checked-in configuration.

```shell
./gradlew :devicecontrols:testDebugUnitTest
./gradlew :devicecontrols:assembleRelease :sample:assembleDebug
```

The sample is `sample/`; local unit tests cover result/model invariants, and Android instrumentation tests cover the Activity window flags. Instrumentation tests require an emulator/device. Hardware, rooted Magisk/KernelSU, Device Owner/Profile Owner, and OEM-specific integration still require physical/device-farm validation. CI cannot prove those privileged runtime cases.

## Publishing configuration

For maintainers publishing to a Maven host other than JitPack, the library has a Maven Publish publication named `release`. It defaults to a local repository under `devicecontrols/build/maven-repo`. Configure `publishingUrl`, and optionally `publishingUser` / `publishingPassword` Gradle properties for an authorized Maven repository. No credentials or remote publication endpoint are checked in. Run `:devicecontrols:publishReleasePublicationToConfiguredRepository` only when the endpoint and release are ready.

## License and contribution

MIT. See [LICENSE](LICENSE), [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md), and [CHANGELOG.md](CHANGELOG.md).
