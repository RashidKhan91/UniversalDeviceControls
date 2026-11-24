package dev.universal.devicecontrols

import dev.universal.devicecontrols.model.*
import dev.universal.devicecontrols.security.RootAccessManager
import dev.universal.devicecontrols.security.RootCommandResult
import dev.universal.devicecontrols.security.RootCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceControlModelTest {
    @Test fun catalogContainsAllFourteenRequestedControls() {
        assertEquals(14, DeviceControlKey.entries.size)
        assertEquals(DeviceControlKey.entries.toSet().size, DeviceControlKey.entries.size)
    }

    @Test fun settingsAndAuthorizationOutcomesAreNotSuccess() {
        val result = DeviceControlResult(
            key = DeviceControlKey.WIFI,
            status = DeviceControlStatus.REQUIRES_SETTINGS,
            requiresSettings = true,
            message = "User action required"
        )
        assertFalse(result.isSuccess)
        assertTrue(result.requiresSettings)
    }

    @Test fun resultSuccessIsExactlyVerifiedSuccessStatus() {
        assertTrue(DeviceControlResult(DeviceControlKey.SCREENSHOT, DeviceControlStatus.SUCCESS, message = "verified").isSuccess)
        assertFalse(DeviceControlResult(DeviceControlKey.SCREENSHOT, DeviceControlStatus.VERIFICATION_FAILED, message = "mismatch").isSuccess)
    }

    @Test fun brightnessValueCarriesModeSeparately() {
        assertEquals(DeviceControlValue.Brightness(100, true), DeviceControlValue.Brightness(100, automatic = true))
        assertNotEquals(DeviceControlValue.Brightness(100, true), DeviceControlValue.Brightness(100, false))
    }

    @Test fun registryHasOneTypedSpecForEveryControl() {
        assertEquals(DeviceControlKey.entries.toSet(), ControlRegistry.specs.keys)
        assertTrue(ControlRegistry.accepts(DeviceControlKey.SCREEN_TIMEOUT, DeviceControlValue.IntValue(60_000)))
        assertTrue(ControlRegistry.accepts(DeviceControlKey.TIME_ZONE, DeviceControlValue.TextValue("UTC")))
        assertTrue(ControlRegistry.accepts(DeviceControlKey.SCREEN_BRIGHTNESS, DeviceControlValue.Brightness(120)))
        assertFalse(ControlRegistry.accepts(DeviceControlKey.SCREEN_TIMEOUT, DeviceControlValue.BooleanValue(true)))
        assertFalse(ControlRegistry.accepts(DeviceControlKey.TIME_ZONE, DeviceControlValue.IntValue(1)))
    }

    @Test fun rootTimeoutIsPreservedAndUsesConfiguredLimit() = runBlocking {
        var passedTimeout = 0L
        val runner = RootCommandRunner { _, timeout ->
            passedTimeout = timeout
            RootCommandResult(authorized = false, exitCode = null, output = "timeout", timedOut = true)
        }
        val result = RootAccessManager(timeoutMs = 321, runner = runner).run("id -u")
        assertEquals(321L, passedTimeout)
        assertTrue(result.timedOut)
        assertNull(result.exitCode)
    }

    @Test fun rootAuthorizationDenialIsNotSuccess() = runBlocking {
        val runner = RootCommandRunner { _, _ -> RootCommandResult(false, 1, "permission denied") }
        val result = RootAccessManager(runner = runner).run("id -u")
        assertFalse(result.authorized)
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("denied"))
    }
}
