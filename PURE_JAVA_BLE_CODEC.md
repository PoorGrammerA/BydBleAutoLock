# Pure-Java BLE codec

## Scope

The active Korean Watch API `dkey` route is implemented entirely in Java. It covers BLE wake-up, random exchange, `dkey` authentication, encrypted control-frame generation, and response-frame assembly. The project does not contain or load a native `.so` library.

The implementation lives in the project-owned `com.poorgrammera.bydblekeycontrol.blecodec` namespace. It has no native compatibility facade and does not load native code.

## Validated authentication flow

The validated Korean route performs the following sequence:

1. Connect to the vehicle GATT service and enable notifications.
2. Send the wake-up frame `5A A5 D5 00 00 F5 FA`.
3. Send an application-random request and accept response type `0x2A`.
4. Derive the session material from the hexadecimal `dkey`, vehicle random, and application random.
5. Send a `dkey` authentication packet in a `5B B5 <16 encrypted bytes> F5 FA` envelope.
6. Accept authentication response type `0x2B` and enter `AUTH_PASS`.
7. Generate encrypted control frames for the validated command mappings.

Session material is derived as `SHA-256(hex(dkey) || vehicleRandom || appRandom)`. The result is split into AES and CMAC halves, and AES-CBC uses `vehicleRandom || appRandom` as the IV.

## GATT configuration

The current codec uses these vehicle BLE UUIDs:

| Purpose | UUID |
| --- | --- |
| Service | `42594420-4155-544F-E0A9-E50E24DCCA9E` |
| Write characteristic | `42590002-4155-544F-E0A9-E50E24DCCA9E` |
| Notify characteristic | `42590003-4155-544F-E0A9-E50E24DCCA9E` |

The notification assembler follows the compatibility convention that `crc8(data, start, end)` treats `end` as an inclusive array index. For example, `crc8(frame, 0, 16)` validates bytes `0..16` against the CRC byte at index `17`.

## Control mapping

`BydBleCodec` contains the function-ID to control-code mapping required by the developer test screen. `VehicleBleCommand` exposes the named commands used by the application. The vehicle-validated subset and unverified candidates are maintained in [BLE_COMMAND_MAPPING.md](BLE_COMMAND_MAPPING.md).

## Validation

- Unit tests cover AES-CMAC, CRC behavior, wake and random framing, encrypted `dkey` envelopes, response parsing, and control-code mapping using synthetic deterministic vectors.
- The app and tests run without native libraries.
- On an ATTO 3 using the Korean Watch API, random exchange completed, authentication reached `AUTH_PASS`, and safe commands including lock and lights operated successfully.

This validation applies only to the tested Korean `dkey` path, vehicle model, and firmware. Other regions may use different provisioning data or BLE behavior.

## Unsupported and unverified behavior

The older user-ID/password/KA authentication route is not included. A provisioning response without a non-empty hexadecimal `dkey` is rejected before BLE authentication frames are sent.

After a GATT write, the app waits up to three seconds for a matching control acknowledgement. The validated response payload starts with `24 E5 <control-code> <result>`, where result `0x01` indicates vehicle-reported success. A different result is treated as rejection; no response is reported as unconfirmed and is not automatically retried over REST because the vehicle may already have acted.
