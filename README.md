#The source code will be uploaded soon after the final testing is complete.

# BLE AutoLock

BLE AutoLock is an unofficial Android proof of concept for studying BYD Watch-style Bluetooth-key authentication, BLE vehicle control, and server REST fallback behavior.

> **Proof of concept only.** This project controls safety-relevant vehicle functions and uses private, undocumented interfaces that may change without notice. Do not rely on it as a production key, your only vehicle key, or a safety-critical access system.

## Demo video

[![Watch the BLE AutoLock demo on YouTube](https://i.ytimg.com/vi/cefO3kPVDN8/hqdefault.jpg)](https://www.youtube.com/shorts/cefO3kPVDN8)

Click the thumbnail to watch the demo on YouTube.

## Validation scope

- The pure-Java BLE codec has been validated only with the Korean (`KR`) Watch API `dkey` flow on a BYD ATTO 3.
- Other regions are exposed for protocol research but are **experimental and unverified**.
- Only the Korean `dkey` BLE route is implemented. Older user-ID/password/KA authentication is not included.
- Vehicle firmware, model, region, and account configuration can change command behavior.

## What the PoC demonstrates

- QR-based Watch-key provisioning through the selected BYD regional server
- Android Keystore-backed storage for server tokens and BLE key material
- BLE scanning, RSSI filtering, proximity thresholds, reconnect handling, and foreground monitoring
- Pure-Java BLE wake, random exchange, `dkey` authentication, and control-frame generation
- BLE-first manual and automatic control with REST fallback where implemented
- A developer screen for inspecting the Watch flow, protocol state, and raw command mappings

## Important security and safety notes

- Automatic control is based on BLE address and signal strength. BLE addresses and radio signals are not proof of physical proximity and can be spoofed or relayed.
- This PoC intentionally retains verbose protocol logging. Logcat can contain decrypted server responses, partial credentials, vehicle identifiers, BLE frames, and QR-related values. Never publish logs without reviewing and redacting them.
- Some controls fall back to the REST API when BLE is unavailable. Review `VehicleAccessService` before enabling automatic control on a real vehicle.
- After a BLE write, the app waits up to three seconds for a matching `0x24/E5` vehicle response. Result `0x01` is reported as vehicle-confirmed success; a timeout remains unconfirmed and is not automatically retried over REST to avoid a duplicate action.
- Test only while the vehicle is parked, with a physical key available and the area around doors, windows, and the trunk clear.
- Do not include VINs, number plates, BLE MAC addresses, QR images, tokens, `dkey` values, or server responses in issues or pull requests.

## Data handling

- The app sends authentication and control requests directly to the selected BYD regional server.
- A stable Watch-style identifier is derived from `Settings.Secure.ANDROID_ID`; it is not the hardware IMEI.
- Tokens, VIN data, vehicle responses, and BLE key material are encrypted with an Android Keystore AES-GCM key before being stored in app preferences.
- Credential preferences are excluded from cloud backup and device transfer.
- When the user chooses **Save QR and open BYD app**, the QR sheet is temporarily written to the public Download collection. The app attempts to delete that exact item after provisioning succeeds or before a replacement QR is exported.
- The project contains no analytics or advertising SDK.

## Requirements

- Android 10 (API 29) or newer
- Android Studio with JDK 17
- Android SDK 36
- A compatible BYD vehicle and an account authorized to control it
- The official BYD app for scanning the provisioning QR code

## Build

1. Clone the repository.
2. Open the project in Android Studio and let Gradle sync.
3. Select a connected Android device with Bluetooth enabled.
4. Build and install the `debug` variant.

Command-line checks on Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleRelease
```

The release build is unsigned unless you configure your own signing key. Never commit a keystore, signing password, `local.properties`, exported QR image, or captured vehicle log.

## Basic use

1. Grant Bluetooth and notification permissions.
2. Select the service region. Only `KR` BLE authentication has been validated.
3. Create the QR code and scan it in the official BYD app.
4. Wait for token, vehicle, and Bluetooth-key provisioning to complete.
5. Review RSSI thresholds and automatic-control options before starting monitoring.
6. Use **Stop service** to keep monitoring disabled across app restarts and device reboots. Starting it again explicitly re-enables boot restoration.

## Project structure

- `AuthActivity`: QR provisioning and regional server selection
- `VehicleControlActivity`: status dashboard, thresholds, service state, and manual controls
- `VehicleAccessService`: foreground BLE monitoring and BLE/REST command dispatch
- `bydapi`: Watch-compatible REST request and credential flow
- `service`: GATT session, frame assembly, authentication, and control transport
- `com.poorgrammera.bydblekeycontrol.blecodec`: pure-Java Korean `dkey` frame codec; no native `.so` is required

## Protocol notes

- [Watch BLE flow](WATCH_BLE_FLOW_PROTOCOL.md)
- [Sanitized gain/vehicle response structure](WATCH_GAIN_VEHICLE_RESPONSE.md)
- [BLE command mapping and validation status](BLE_COMMAND_MAPPING.md)
- [Pure-Java BLE codec](PURE_JAVA_BLE_CODEC.md)

## Contributing

This project is maintained irregularly. Forking the repository and maintaining the changes needed for your own use is the recommended contribution model. See [CONTRIBUTING.md](CONTRIBUTING.md) for expectations, testing guidance, and data-redaction requirements.

Security reports should follow [SECURITY.md](SECURITY.md). Direct dependency licenses and notices are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## License and trademarks

Project-authored code is released under the [MIT License](LICENSE). Third-party dependencies and materials retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

BYD and related product names are trademarks of their respective owners. This project is not affiliated with, endorsed by, or supported by BYD. The MIT License does not grant rights to BYD trademarks, private services, accounts, firmware, or third-party applications.
