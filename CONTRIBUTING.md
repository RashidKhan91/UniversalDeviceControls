# Contributing

1. Open an issue with the Android version, target SDK, device/OEM, owner/root state, expected behavior, and observed `DeviceControlResult`.
2. Keep public control values typed and command execution restricted to reviewed internal allowlists.
3. Add tests for every behavior change; include a physical-device matrix result for privileged APIs.
4. Run `./gradlew :devicecontrols:testDebugUnitTest :devicecontrols:assembleRelease :sample:assembleDebug`.
5. Document platform and OEM restrictions. Never report an operation as successful without verifying its state.

Please do not include device identifiers, root logs with personal data, or credentials in bug reports.
