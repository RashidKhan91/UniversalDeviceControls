package dev.universal.devicecontrols

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.universal.devicecontrols.model.DeviceControlKey
import dev.universal.devicecontrols.model.DeviceControlStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceControlWindowTest {
    @Test fun screenshotPolicyChangesAndReadsBackHostWindowFlag() = runBlocking {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var manager: DeviceControlManager
            scenario.onActivity { manager = DeviceControlManager(it) }
            val blocked = manager.disable(DeviceControlKey.SCREENSHOT)
            assertEquals(DeviceControlStatus.SUCCESS, blocked.status)
            scenario.onActivity { assertNotEquals(0, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
            val allowed = manager.enable(DeviceControlKey.SCREENSHOT)
            assertEquals(DeviceControlStatus.SUCCESS, allowed.status)
            scenario.onActivity { assertEquals(0, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }

    @Test fun keepScreenOnIsScopedToActivityWindow() = runBlocking {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var manager: DeviceControlManager
            scenario.onActivity { manager = DeviceControlManager(it) }
            val result = manager.enable(DeviceControlKey.KEEP_SCREEN_ON)
            assertEquals(DeviceControlStatus.SUCCESS, result.status)
            scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) }
        }
    }
}
