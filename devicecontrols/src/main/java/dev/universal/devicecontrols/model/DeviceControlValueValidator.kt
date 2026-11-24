package dev.universal.devicecontrols.model

internal enum class ControlValueKind { BOOLEAN, INTEGER, TEXT, BRIGHTNESS }

internal data class ControlSpec(val key: DeviceControlKey, val valueKind: ControlValueKind)

/** Central key/type catalog used to reject invalid public key-value combinations. */
internal object ControlRegistry {
    val specs: Map<DeviceControlKey, ControlSpec> = DeviceControlKey.entries.associateWith { key ->
        ControlSpec(key, when (key) {
            DeviceControlKey.SCREEN_TIMEOUT -> ControlValueKind.INTEGER
            DeviceControlKey.SCREEN_BRIGHTNESS -> ControlValueKind.BRIGHTNESS
            DeviceControlKey.TIME_ZONE -> ControlValueKind.TEXT
            else -> ControlValueKind.BOOLEAN
        })
    }

    fun accepts(key: DeviceControlKey, value: DeviceControlValue): Boolean = when (specs[key]?.valueKind) {
        ControlValueKind.BOOLEAN -> value is DeviceControlValue.BooleanValue
        ControlValueKind.INTEGER -> value is DeviceControlValue.IntValue
        ControlValueKind.TEXT -> value is DeviceControlValue.TextValue
        ControlValueKind.BRIGHTNESS -> value is DeviceControlValue.Brightness
        null -> false
    }
}
