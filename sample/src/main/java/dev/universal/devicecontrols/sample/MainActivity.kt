package dev.universal.devicecontrols.sample

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.universal.devicecontrols.DeviceControlManager
import dev.universal.devicecontrols.model.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var controls: DeviceControlManager
    private lateinit var status: TextView
    private lateinit var body: LinearLayout
    private val refreshers = mutableListOf<() -> Unit>()
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status.text = if (granted) "Bluetooth permission granted; press the control again." else "Bluetooth permission was not granted."
        refreshers.forEach { it() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controls = DeviceControlManager(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20) }
        body.addView(TextView(this).apply { text = "UniversalDeviceControls sample"; textSize = 22f })
        status = TextView(this).apply { text = "Loading device capabilities…"; textSize = 14f }
        body.addView(status)
        DeviceControlKey.entries.forEach(::addControl)
        setContentView(ScrollView(this).apply { addView(body, ViewGroup.LayoutParams(-1, -2)) })
        refreshCapabilities()
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) refreshers.forEach { it() }
    }

    private fun addControl(key: DeviceControlKey) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 16, 0, 16) }
        val title = TextView(this).apply { text = key.name.replace('_', ' '); textSize = 17f }
        val state = TextView(this).apply { text = "Reading…" }
        val toggle = if (key in booleanKeys) Switch(this).apply { text = "Enabled / allowed" } else null
        val action = if (toggle == null) Button(this).apply { text = "Apply sample value" } else null
        row.addView(title); if (toggle != null) row.addView(toggle); row.addView(state); if (action != null) row.addView(action)
        body.addView(row)
        lateinit var refreshState: () -> Unit
        fun updateToggle(actual: DeviceControlValue?) {
            val booleanValue = (actual as? DeviceControlValue.BooleanValue)?.value
            toggle?.setOnCheckedChangeListener(null)
            toggle?.isChecked = booleanValue == true
            toggle?.isEnabled = booleanValue != null
            toggle?.setOnCheckedChangeListener { _, checked ->
                if (key == DeviceControlKey.BLUETOOTH && Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    toggle?.setOnCheckedChangeListener(null)
                    toggle?.isChecked = booleanValue == true
                    refreshState()
                    bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    return@setOnCheckedChangeListener
                }
                lifecycleScope.launch {
                    val result = controls.set(key, DeviceControlValue.BooleanValue(checked))
                    status.text = "${result.key}: ${result.status}\n${result.message}\nStrategy: ${result.executionStrategy ?: "read-only/none"}"
                    if (result.requiresSettings) runCatching { controls.openSettings(key) }
                    refreshState()
                }
            }
        }
        refreshState = {
            lifecycleScope.launch {
                val result = controls.get(key)
                state.text = "State: ${result.actualValue ?: "unavailable"} • ${result.status}"
                updateToggle(result.actualValue)
            }
        }
        refreshers += refreshState
        action?.setOnClickListener {
            if (key == DeviceControlKey.BLUETOOTH && Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else lifecycleScope.launch {
                val result = when (key) {
                    DeviceControlKey.SCREEN_TIMEOUT -> controls.set(key, DeviceControlValue.IntValue(120_000))
                    DeviceControlKey.SCREEN_BRIGHTNESS -> controls.set(key, DeviceControlValue.Brightness(170, automatic = false))
                    DeviceControlKey.TIME_ZONE -> controls.set(key, DeviceControlValue.TextValue("UTC"))
                    else -> controls.get(key)
                }
                status.text = "${result.key}: ${result.status}\n${result.message}\nStrategy: ${result.executionStrategy ?: "read-only/none"}"
                if (result.requiresSettings) runCatching { controls.openSettings(key) }
                refreshState()
            }
        }
        state.setOnClickListener { refreshState() }
        refreshState()
    }

    private fun refreshCapabilities() = lifecycleScope.launch {
        val caps = controls.getCapabilities(DeviceControlKey.AUTO_DATE_TIME)
        status.text = "Root available: ${caps.rootAvailable} • ownership: ${caps.ownership} • SDK ${android.os.Build.VERSION.SDK_INT}"
    }

    private val booleanKeys = setOf(
        DeviceControlKey.SCREENSHOT, DeviceControlKey.SCREEN_RECORDING, DeviceControlKey.KEEP_SCREEN_ON,
        DeviceControlKey.AUTO_DATE_TIME, DeviceControlKey.AUTO_TIME_ZONE, DeviceControlKey.AUTO_ROTATE,
        DeviceControlKey.WIFI, DeviceControlKey.BLUETOOTH, DeviceControlKey.BATTERY_OPTIMIZATION,
        DeviceControlKey.DO_NOT_DISTURB, DeviceControlKey.AIRPLANE_MODE
    )
}
