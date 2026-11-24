package dev.universal.devicecontrols

import android.app.Activity
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.core.net.toUri
import dev.universal.devicecontrols.model.*
import dev.universal.devicecontrols.security.RootAccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Unified controls scoped to [activity]'s window and the host app's authorized system capabilities.
 * Calls that need privileged APIs are reported precisely; the manager never silently substitutes
 * a weaker operation and never exposes arbitrary shell execution.
 */
class DeviceControlManager(
    private val activity: Activity,
    private val adminComponent: ComponentName? = null
) {
    private val rootAccessManager = RootAccessManager()
    private val app: Context get() = activity.applicationContext
    private val dpm: DevicePolicyManager get() = app.getSystemService(DevicePolicyManager::class.java)

    suspend fun set(key: DeviceControlKey, value: DeviceControlValue): DeviceControlResult = withContext(Dispatchers.IO) {
        try {
            if (!ControlRegistry.accepts(key, value)) return@withContext invalid(key, value, "Value type does not match $key.")
            when (key) {
                DeviceControlKey.SCREENSHOT, DeviceControlKey.SCREEN_RECORDING -> setSecureFlag(key, bool(value))
                DeviceControlKey.KEEP_SCREEN_ON -> setKeepScreenOn(bool(value))
                DeviceControlKey.SCREEN_TIMEOUT -> setTimeout((value as? DeviceControlValue.IntValue)?.value ?: return@withContext invalid(key, value, "Expected IntValue milliseconds"))
                DeviceControlKey.SCREEN_BRIGHTNESS -> setBrightness(value as? DeviceControlValue.Brightness ?: return@withContext invalid(key, value, "Expected Brightness(level 0..255, automatic)"))
                DeviceControlKey.AUTO_DATE_TIME -> setAutomatic(key, bool(value), "auto_time")
                DeviceControlKey.AUTO_TIME_ZONE -> setAutomatic(key, bool(value), "auto_time_zone")
                DeviceControlKey.TIME_ZONE -> setTimeZone((value as? DeviceControlValue.TextValue)?.value ?: return@withContext invalid(key, value, "Expected TextValue with an IANA time-zone ID"))
                DeviceControlKey.AUTO_ROTATE -> setAutoRotate(bool(value))
                DeviceControlKey.WIFI -> setWifi(bool(value))
                DeviceControlKey.BLUETOOTH -> setBluetooth(bool(value))
                DeviceControlKey.BATTERY_OPTIMIZATION -> {
                    val requested = DeviceControlValue.BooleanValue(bool(value))
                    val actual = read(key)
                    if (actual == requested) result(key, requested, actual, true, ExecutionStrategy.STANDARD_API, "The app already has the requested battery optimization state.")
                    else DeviceControlResult(key, DeviceControlStatus.REQUIRES_SETTINGS, requested, actual, requiresSettings = true, executionStrategy = ExecutionStrategy.USER_SETTINGS, message = "Use openSettings(key) to review battery optimization, then query the state again.")
                }
                DeviceControlKey.DO_NOT_DISTURB -> setDnd(bool(value))
                DeviceControlKey.AIRPLANE_MODE -> DeviceControlResult(key, DeviceControlStatus.UNSUPPORTED, value, read(key), message = "Ordinary apps cannot change global airplane mode. Ask the user to change it in system settings.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            DeviceControlResult(key, DeviceControlStatus.INVALID_VALUE, value, safeRead(key), message = e.message ?: "Value type does not match this control.", error = e)
        } catch (e: SecurityException) {
            DeviceControlResult(key, DeviceControlStatus.PERMISSION_REQUIRED, value, safeRead(key), message = e.message ?: "Android denied this operation.", error = e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DeviceControlResult(key, DeviceControlStatus.FAILED, value, safeRead(key), message = e.message ?: e.javaClass.simpleName, error = e)
        }
    }

    suspend fun get(key: DeviceControlKey): DeviceControlResult = withContext(Dispatchers.IO) {
        try {
            val actual = read(key)
            DeviceControlResult(key, if (actual == null) DeviceControlStatus.NOT_AVAILABLE else DeviceControlStatus.SUCCESS,
                actualValue = actual, message = if (actual == null) "State is not available on this device." else "Current state read successfully.")
        } catch (e: Exception) {
            DeviceControlResult(key, DeviceControlStatus.NOT_AVAILABLE, message = e.message ?: "Could not read state.", error = e)
        }
    }

    suspend fun enable(key: DeviceControlKey): DeviceControlResult = set(key, enableValue(key, true))
    suspend fun disable(key: DeviceControlKey): DeviceControlResult = set(key, enableValue(key, false))
    suspend fun isEnabled(key: DeviceControlKey): DeviceControlResult = get(key)

    suspend fun getCapabilities(key: DeviceControlKey): DeviceControlCapabilities {
        val owner = ownership()
        val ownerCanChange = owner != Ownership.NONE && when (key) {
            DeviceControlKey.AUTO_DATE_TIME, DeviceControlKey.AUTO_TIME_ZONE -> Build.VERSION.SDK_INT >= 30
            DeviceControlKey.TIME_ZONE -> Build.VERSION.SDK_INT >= 28
            else -> true
        }
        val root = rootAccessManager.detect()
        val writable = Settings.System.canWrite(app)
        val strategies = when (key) {
            DeviceControlKey.SCREENSHOT, DeviceControlKey.SCREEN_RECORDING, DeviceControlKey.KEEP_SCREEN_ON -> setOf(ExecutionStrategy.STANDARD_API)
            DeviceControlKey.SCREEN_TIMEOUT, DeviceControlKey.SCREEN_BRIGHTNESS, DeviceControlKey.AUTO_ROTATE -> setOfNotNull(ExecutionStrategy.STANDARD_API, if (writable) null else ExecutionStrategy.USER_SETTINGS)
            DeviceControlKey.AUTO_DATE_TIME, DeviceControlKey.AUTO_TIME_ZONE, DeviceControlKey.TIME_ZONE -> buildSet { if (ownerCanChange) add(if (owner == Ownership.DEVICE_OWNER) ExecutionStrategy.DEVICE_OWNER else ExecutionStrategy.PROFILE_OWNER); if (root) add(ExecutionStrategy.ROOT); add(ExecutionStrategy.UNSUPPORTED) }
            DeviceControlKey.WIFI, DeviceControlKey.BLUETOOTH -> buildSet { if (root) add(ExecutionStrategy.ROOT); if (!platformBlocksRadioChange(key) || (key == DeviceControlKey.BLUETOOTH && owner != Ownership.NONE)) add(ExecutionStrategy.STANDARD_API); add(ExecutionStrategy.USER_SETTINGS) }
            DeviceControlKey.DO_NOT_DISTURB -> setOf(ExecutionStrategy.STANDARD_API, ExecutionStrategy.USER_SETTINGS)
            DeviceControlKey.BATTERY_OPTIMIZATION -> setOf(ExecutionStrategy.USER_SETTINGS, ExecutionStrategy.STANDARD_API)
            DeviceControlKey.AIRPLANE_MODE -> setOf(ExecutionStrategy.STANDARD_API, ExecutionStrategy.UNSUPPORTED)
        }
        val writeSettingsKeys = setOf(DeviceControlKey.SCREEN_TIMEOUT, DeviceControlKey.SCREEN_BRIGHTNESS, DeviceControlKey.AUTO_ROTATE)
        val ownerOrRootKeys = setOf(DeviceControlKey.AUTO_DATE_TIME, DeviceControlKey.AUTO_TIME_ZONE, DeviceControlKey.TIME_ZONE)
        return DeviceControlCapabilities(key, supported = true, readable = true,
            modifiable = key !in setOf(DeviceControlKey.AIRPLANE_MODE) && (key !in ownerOrRootKeys || ownerCanChange || root),
            availableStrategies = strategies, rootAvailable = root, ownership = owner,
            settingsAuthorizationRequired = key in writeSettingsKeys && !writable,
            minimumApi = when (key) { DeviceControlKey.TIME_ZONE -> 26; DeviceControlKey.BLUETOOTH -> 18; else -> 26 },
            note = restrictionNote(key))
    }

    /** Launches a suitable system screen. Caller should call get() again on return. */
    fun openSettings(key: DeviceControlKey) {
        val intent = when (key) {
            DeviceControlKey.SCREEN_TIMEOUT, DeviceControlKey.SCREEN_BRIGHTNESS, DeviceControlKey.AUTO_ROTATE ->
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${activity.packageName}".toUri())
            DeviceControlKey.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
            DeviceControlKey.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            DeviceControlKey.BATTERY_OPTIMIZATION -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            DeviceControlKey.DO_NOT_DISTURB -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            DeviceControlKey.AUTO_DATE_TIME, DeviceControlKey.AUTO_TIME_ZONE, DeviceControlKey.TIME_ZONE -> Intent(Settings.ACTION_DATE_SETTINGS)
            DeviceControlKey.AIRPLANE_MODE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    private fun read(key: DeviceControlKey): DeviceControlValue? = when (key) {
        DeviceControlKey.SCREENSHOT -> DeviceControlValue.BooleanValue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
        DeviceControlKey.SCREEN_RECORDING -> DeviceControlValue.BooleanValue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
        DeviceControlKey.KEEP_SCREEN_ON -> DeviceControlValue.BooleanValue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        DeviceControlKey.SCREEN_TIMEOUT -> DeviceControlValue.IntValue(Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT))
        DeviceControlKey.SCREEN_BRIGHTNESS -> DeviceControlValue.Brightness(
            Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(0, 255),
            Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        DeviceControlKey.AUTO_DATE_TIME -> DeviceControlValue.BooleanValue(Settings.Global.getInt(app.contentResolver, Settings.Global.AUTO_TIME, 0) == 1)
        DeviceControlKey.AUTO_TIME_ZONE -> DeviceControlValue.BooleanValue(Settings.Global.getInt(app.contentResolver, Settings.Global.AUTO_TIME_ZONE, 0) == 1)
        DeviceControlKey.TIME_ZONE -> DeviceControlValue.TextValue(java.util.TimeZone.getDefault().id)
        DeviceControlKey.AUTO_ROTATE -> DeviceControlValue.BooleanValue(Settings.System.getInt(app.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1)
        DeviceControlKey.WIFI -> DeviceControlValue.BooleanValue((app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled)
        DeviceControlKey.BLUETOOTH -> {
            if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) null
            else app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled?.let { DeviceControlValue.BooleanValue(it) }
        }
        DeviceControlKey.BATTERY_OPTIMIZATION -> DeviceControlValue.BooleanValue(app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName))
        DeviceControlKey.DO_NOT_DISTURB -> DeviceControlValue.BooleanValue((app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
        DeviceControlKey.AIRPLANE_MODE -> DeviceControlValue.BooleanValue(Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
    }

    private suspend fun setSecureFlag(key: DeviceControlKey, allow: Boolean): DeviceControlResult {
        withContext(Dispatchers.Main) { activity.window.setFlags(if (allow) 0 else WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE) }
        val actual = safeRead(key)
        val verified = actual == DeviceControlValue.BooleanValue(allow)
        return result(key, DeviceControlValue.BooleanValue(allow), actual, verified, ExecutionStrategy.STANDARD_API,
            if (key == DeviceControlKey.SCREENSHOT) "Screenshot protection applies only to this Activity window." else "FLAG_SECURE is app-window protection; it is best-effort against capture and is not an OS-wide screen-recording policy.")
    }

    private suspend fun setKeepScreenOn(enabled: Boolean): DeviceControlResult {
        withContext(Dispatchers.Main) { if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        return result(DeviceControlKey.KEEP_SCREEN_ON, DeviceControlValue.BooleanValue(enabled), safeRead(DeviceControlKey.KEEP_SCREEN_ON), safeRead(DeviceControlKey.KEEP_SCREEN_ON) == DeviceControlValue.BooleanValue(enabled), ExecutionStrategy.STANDARD_API, "Applies only while this Activity window is visible.")
    }

    private suspend fun setTimeout(ms: Int): DeviceControlResult {
        val key = DeviceControlKey.SCREEN_TIMEOUT
        if (ms !in 15_000..86_400_000) return invalid(key, DeviceControlValue.IntValue(ms), "Timeout must be between 15 seconds and 24 hours.")
        if (!Settings.System.canWrite(app)) return needsSettings(key, DeviceControlValue.IntValue(ms), safeRead(key), "Grant WRITE_SETTINGS through system settings, then retry.")
        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms)
        val actual = safeRead(key)
        return result(key, DeviceControlValue.IntValue(ms), actual, actual == DeviceControlValue.IntValue(ms), ExecutionStrategy.STANDARD_API, "Android or an administrator may clamp the requested timeout.")
    }

    private suspend fun setBrightness(value: DeviceControlValue.Brightness): DeviceControlResult {
        val key = DeviceControlKey.SCREEN_BRIGHTNESS
        if (value.level !in 0..255) return invalid(key, value, "Brightness must be 0..255.")
        if (!Settings.System.canWrite(app)) return needsSettings(key, value, safeRead(key), "Grant WRITE_SETTINGS through system settings, then retry.")
        val resolver = app.contentResolver
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, if (value.automatic) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        if (!value.automatic) Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value.level)
        val actual = safeRead(key)
        val verified = (actual as? DeviceControlValue.Brightness)?.let { it.automatic == value.automatic && (value.automatic || it.level == value.level) } == true
        return result(key, value, actual, verified, ExecutionStrategy.STANDARD_API, "Automatic brightness is controlled by the device sensor; level is only configured in manual mode.")
    }

    private suspend fun setAutoRotate(enabled: Boolean): DeviceControlResult {
        val key = DeviceControlKey.AUTO_ROTATE
        if (!Settings.System.canWrite(app)) return needsSettings(key, DeviceControlValue.BooleanValue(enabled), safeRead(key), "Grant WRITE_SETTINGS through system settings, then retry.")
        Settings.System.putInt(app.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (enabled) 1 else 0)
        val actual = safeRead(key)
        return result(key, DeviceControlValue.BooleanValue(enabled), actual, actual == DeviceControlValue.BooleanValue(enabled), ExecutionStrategy.STANDARD_API, "Changes the global auto-rotate preference, not the requested Activity orientation.")
    }

    private suspend fun setAutomatic(key: DeviceControlKey, enabled: Boolean, setting: String): DeviceControlResult {
        val request = DeviceControlValue.BooleanValue(enabled)
        val owner = ownership()
        if (Build.VERSION.SDK_INT >= 30 && owner != Ownership.NONE && adminComponent != null) {
            try {
                if (key == DeviceControlKey.AUTO_DATE_TIME) dpm.setAutoTimeEnabled(adminComponent, enabled) else dpm.setAutoTimeZoneEnabled(adminComponent, enabled)
                val actual = safeRead(key)
                return result(key, request, actual, actual == request, if (owner == Ownership.DEVICE_OWNER) ExecutionStrategy.DEVICE_OWNER else ExecutionStrategy.PROFILE_OWNER, "Applied with DevicePolicyManager and verified against global state.")
            } catch (e: SecurityException) {
                return DeviceControlResult(key, DeviceControlStatus.ADMIN_REQUIRED, request, safeRead(key), executionStrategy = ExecutionStrategy.DEVICE_OWNER, message = e.message ?: "Owner policy rejected the operation.", error = e)
            }
        }
        val command = "settings put global $setting ${if (enabled) 1 else 0}"
        val root = rootAccessManager.run(command)
        if (root.timedOut) return DeviceControlResult(key, DeviceControlStatus.FAILED, request, safeRead(key), executionStrategy = ExecutionStrategy.ROOT, requiresRoot = true, message = "Root command timed out.")
        if (root.exitCode == 0) {
            val actual = awaitState(key, request)
            return result(key, request, actual, actual == request, ExecutionStrategy.ROOT, "Root setting command completed; state was independently read back.")
        }
        val failure = when {
            root.timedOut -> DeviceControlStatus.FAILED
            root.output.contains("denied", true) -> DeviceControlStatus.ROOT_DENIED
            root.exitCode == null -> if (owner != Ownership.NONE) DeviceControlStatus.ADMIN_REQUIRED else DeviceControlStatus.ROOT_REQUIRED
            else -> DeviceControlStatus.ADMIN_REQUIRED
        }
        return DeviceControlResult(key, failure,
            request, safeRead(key), executionStrategy = ExecutionStrategy.ROOT, requiresRoot = true, message = "Changing global automatic date/time settings requires a qualified owner app or authorized root. ${root.output.take(240)}")
    }

    private suspend fun setTimeZone(zone: String): DeviceControlResult {
        val key = DeviceControlKey.TIME_ZONE
        if (zone !in ZoneId.getAvailableZoneIds()) return invalid(key, DeviceControlValue.TextValue(zone), "Unknown IANA time-zone ID.")
        val owner = ownership()
        if (Build.VERSION.SDK_INT >= 28 && owner != Ownership.NONE && adminComponent != null) {
            try {
                dpm.setTimeZone(adminComponent, zone)
                val actual = safeRead(key)
                return result(key, DeviceControlValue.TextValue(zone), actual, actual == DeviceControlValue.TextValue(zone), if (owner == Ownership.DEVICE_OWNER) ExecutionStrategy.DEVICE_OWNER else ExecutionStrategy.PROFILE_OWNER, "Applied with DevicePolicyManager and read back.")
            } catch (e: SecurityException) {
                return DeviceControlResult(key, DeviceControlStatus.ADMIN_REQUIRED, DeviceControlValue.TextValue(zone), safeRead(key), executionStrategy = ExecutionStrategy.DEVICE_OWNER, message = e.message ?: "Owner policy rejected time-zone change.", error = e)
            }
        }
        val root = rootAccessManager.run("setprop persist.sys.timezone $zone")
        val actual = if (root.exitCode == 0) awaitState(key, DeviceControlValue.TextValue(zone)) else safeRead(key)
        if (root.exitCode == 0 && actual == DeviceControlValue.TextValue(zone)) return result(key, DeviceControlValue.TextValue(zone), actual, true, ExecutionStrategy.ROOT, "Applied through authorized root and verified.")
        val failure = when {
            root.timedOut -> DeviceControlStatus.FAILED
            root.output.contains("denied", true) -> DeviceControlStatus.ROOT_DENIED
            root.exitCode == null -> if (owner == Ownership.NONE) DeviceControlStatus.ROOT_REQUIRED else DeviceControlStatus.ADMIN_REQUIRED
            else -> DeviceControlStatus.ADMIN_REQUIRED
        }
        return DeviceControlResult(key, failure,
            DeviceControlValue.TextValue(zone), actual, executionStrategy = ExecutionStrategy.ROOT, requiresRoot = true, message = "Setting the system time zone requires a qualified owner app or authorized root. ${root.output.take(240)}")
    }

    @Suppress("DEPRECATION")
    private suspend fun setWifi(enabled: Boolean): DeviceControlResult {
        val key = DeviceControlKey.WIFI; val request = DeviceControlValue.BooleanValue(enabled)
        if (!platformBlocksRadioChange(key)) {
            val accepted = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).setWifiEnabled(enabled)
            if (accepted) {
                val actual = awaitState(key, request)
                if (actual == request) return result(key, request, actual, true, ExecutionStrategy.STANDARD_API, "Legacy Wi-Fi API operation verified.")
            }
        }
        val command = "svc wifi ${if (enabled) "enable" else "disable"}"
        val root = rootAccessManager.run(command)
        if (root.exitCode == 0) {
            val actual = awaitState(key, request)
            return result(key, request, actual, actual == request, ExecutionStrategy.ROOT, "Root command ran; Wi-Fi state was read back.")
        }
        return DeviceControlResult(key, if (root.output.contains("denied", true)) DeviceControlStatus.ROOT_DENIED else DeviceControlStatus.REQUIRES_SETTINGS,
            request, safeRead(key), executionStrategy = if (root.output.contains("denied", true)) ExecutionStrategy.ROOT else ExecutionStrategy.USER_SETTINGS, requiresRoot = root.output.contains("denied", true), requiresSettings = !root.output.contains("denied", true),
            message = "This Android version/target does not allow ordinary apps to toggle Wi-Fi. Use openSettings(key) and confirm the state afterward.")
    }

    @Suppress("DEPRECATION")
    private suspend fun setBluetooth(enabled: Boolean): DeviceControlResult {
        val key = DeviceControlKey.BLUETOOTH; val request = DeviceControlValue.BooleanValue(enabled)
        if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return DeviceControlResult(key, DeviceControlStatus.PERMISSION_REQUIRED, request, safeRead(key), message = "Grant BLUETOOTH_CONNECT before reading or changing Bluetooth, then retry.")
        }
        if (!platformBlocksRadioChange(key) || ownership() != Ownership.NONE) {
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
            val accepted = if (enabled) adapter?.enable() else adapter?.disable()
            if (accepted == true) {
                val actual = awaitState(key, request)
                if (actual == request) return result(key, request, actual, true, ExecutionStrategy.STANDARD_API, "Legacy Bluetooth API operation verified.")
            }
        }
        val root = rootAccessManager.run("svc bluetooth ${if (enabled) "enable" else "disable"}")
        if (root.exitCode == 0) {
            val actual = awaitState(key, request)
            return result(key, request, actual, actual == request, ExecutionStrategy.ROOT, "Root command ran; Bluetooth state was read back.")
        }
        return DeviceControlResult(key, if (safeRead(key) == null) DeviceControlStatus.PERMISSION_REQUIRED else DeviceControlStatus.REQUIRES_SETTINGS,
            request, safeRead(key), executionStrategy = ExecutionStrategy.USER_SETTINGS, requiresSettings = true,
            message = "Android restricts programmatic Bluetooth toggling for modern apps. Use the system Bluetooth settings screen and confirm the state afterward.")
    }

    private suspend fun setDnd(enabled: Boolean): DeviceControlResult {
        val key = DeviceControlKey.DO_NOT_DISTURB; val request = DeviceControlValue.BooleanValue(enabled)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return DeviceControlResult(key, DeviceControlStatus.PERMISSION_REQUIRED, request, safeRead(key), requiresSettings = true, message = "Grant Notification Policy Access in system settings, then retry.")
        nm.setInterruptionFilter(if (enabled) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL)
        val actual = awaitState(key, request)
        val semantics = if (app.applicationInfo.targetSdkVersion >= 35) "Android 15+ targets apply this through the app-associated automatic Zen rule." else "The notification interruption filter was set."
        return result(key, request, actual, actual == request, ExecutionStrategy.STANDARD_API, "$semantics Read-back verification is required; OEM policy may affect behavior.")
    }

    private fun ownership(): Ownership {
        val name = app.packageName
        return when {
            dpm.isDeviceOwnerApp(name) -> Ownership.DEVICE_OWNER
            dpm.isProfileOwnerApp(name) -> Ownership.PROFILE_OWNER
            else -> Ownership.NONE
        }
    }

    private fun platformBlocksRadioChange(key: DeviceControlKey): Boolean = when (key) {
        DeviceControlKey.WIFI -> Build.VERSION.SDK_INT >= 29 && app.applicationInfo.targetSdkVersion >= 29
        DeviceControlKey.BLUETOOTH -> Build.VERSION.SDK_INT >= 33 && app.applicationInfo.targetSdkVersion >= 33
        else -> true
    }

    private fun bool(value: DeviceControlValue): Boolean = (value as? DeviceControlValue.BooleanValue)?.value ?: throw IllegalArgumentException("Expected BooleanValue for this control.")
    private fun enableValue(key: DeviceControlKey, enabled: Boolean): DeviceControlValue = when (key) {
        DeviceControlKey.SCREEN_TIMEOUT -> DeviceControlValue.IntValue(if (enabled) 60_000 else 300_000)
        DeviceControlKey.SCREEN_BRIGHTNESS -> DeviceControlValue.Brightness(if (enabled) 200 else 80)
        DeviceControlKey.TIME_ZONE -> DeviceControlValue.TextValue(java.util.TimeZone.getDefault().id)
        else -> DeviceControlValue.BooleanValue(enabled)
    }

    private fun safeRead(key: DeviceControlKey): DeviceControlValue? = try { read(key) } catch (_: Exception) { null }
    private suspend fun awaitState(key: DeviceControlKey, expected: DeviceControlValue, timeoutMs: Long = 5_000L): DeviceControlValue? {
        val end = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var actual = safeRead(key)
        while (actual != expected && android.os.SystemClock.elapsedRealtime() < end) {
            delay(200)
            actual = safeRead(key)
        }
        return actual
    }
    private fun invalid(key: DeviceControlKey, value: DeviceControlValue, message: String) = DeviceControlResult(key, DeviceControlStatus.INVALID_VALUE, value, safeRead(key), message = message)
    private fun needsSettings(key: DeviceControlKey, value: DeviceControlValue, actual: DeviceControlValue?, message: String) = DeviceControlResult(key, DeviceControlStatus.REQUIRES_SETTINGS, value, actual, requiresSettings = true, executionStrategy = ExecutionStrategy.USER_SETTINGS, message = message)
    private fun result(key: DeviceControlKey, requested: DeviceControlValue?, actual: DeviceControlValue?, verified: Boolean, strategy: ExecutionStrategy, message: String) =
        DeviceControlResult(key, if (verified) DeviceControlStatus.SUCCESS else DeviceControlStatus.VERIFICATION_FAILED, requested, actual, executionStrategy = strategy, message = message)

    private fun restrictionNote(key: DeviceControlKey): String = when (key) {
        DeviceControlKey.SCREENSHOT -> "FLAG_SECURE affects this host Activity window only."
        DeviceControlKey.SCREEN_RECORDING -> "Window protection is best-effort and is not a global OS recording policy."
        DeviceControlKey.AUTO_DATE_TIME, DeviceControlKey.AUTO_TIME_ZONE, DeviceControlKey.TIME_ZONE -> "System mutation needs supported Device/Profile Owner policy or explicitly authorized root."
        DeviceControlKey.WIFI -> "Modern target apps cannot toggle Wi-Fi via WifiManager; use user settings or compatible root."
        DeviceControlKey.BLUETOOTH -> "Modern apps cannot silently toggle Bluetooth; use user settings or compatible root."
        DeviceControlKey.AIRPLANE_MODE -> "Ordinary third-party apps can inspect but cannot directly change airplane mode."
        DeviceControlKey.AUTO_ROTATE -> "Controls the global setting and needs WRITE_SETTINGS authorization."
        else -> "OEM policy and administrator restrictions can constrain this control."
    }
}
