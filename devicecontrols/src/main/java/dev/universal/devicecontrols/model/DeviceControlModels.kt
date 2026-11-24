package dev.universal.devicecontrols.model

enum class DeviceControlKey {
    SCREENSHOT, SCREEN_RECORDING, SCREEN_TIMEOUT, SCREEN_BRIGHTNESS,
    AUTO_DATE_TIME, AUTO_TIME_ZONE, TIME_ZONE, KEEP_SCREEN_ON, AUTO_ROTATE,
    WIFI, BLUETOOTH, BATTERY_OPTIMIZATION, DO_NOT_DISTURB, AIRPLANE_MODE
}

sealed interface DeviceControlValue {
    data class BooleanValue(val value: Boolean) : DeviceControlValue
    data class IntValue(val value: Int) : DeviceControlValue
    data class TextValue(val value: String) : DeviceControlValue
    data class Brightness(val level: Int, val automatic: Boolean = false) : DeviceControlValue
}

enum class DeviceControlStatus {
    SUCCESS, FAILED, PERMISSION_REQUIRED, ROOT_REQUIRED, ROOT_DENIED, ADMIN_REQUIRED,
    REQUIRES_SETTINGS, UNSUPPORTED, NOT_AVAILABLE, PARTIALLY_APPLIED,
    VERIFICATION_FAILED, INVALID_VALUE
}

enum class ExecutionStrategy { STANDARD_API, DEVICE_OWNER, PROFILE_OWNER, ROOT, USER_SETTINGS, UNSUPPORTED }
enum class Ownership { NONE, DEVICE_OWNER, PROFILE_OWNER }

data class DeviceControlCapabilities(
    val key: DeviceControlKey,
    val supported: Boolean,
    val readable: Boolean,
    val modifiable: Boolean,
    val availableStrategies: Set<ExecutionStrategy>,
    val rootAvailable: Boolean,
    val ownership: Ownership,
    val settingsAuthorizationRequired: Boolean,
    val minimumApi: Int,
    val note: String
)

data class DeviceControlResult(
    val key: DeviceControlKey,
    val status: DeviceControlStatus,
    val requestedValue: DeviceControlValue? = null,
    val actualValue: DeviceControlValue? = null,
    val isEnabled: Boolean? = (actualValue as? DeviceControlValue.BooleanValue)?.value,
    val executionStrategy: ExecutionStrategy? = null,
    val requiresRoot: Boolean = false,
    val requiresSettings: Boolean = false,
    val message: String,
    val error: Throwable? = null
) {
    val isSuccess: Boolean get() = status == DeviceControlStatus.SUCCESS
}
