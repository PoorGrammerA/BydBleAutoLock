# Security policy

BLE AutoLock is an experimental proof of concept, not a production vehicle-key product. Security fixes, releases, and response times are not guaranteed.

## Reporting a vulnerability

If GitHub private vulnerability reporting is enabled for this repository, use it for reports that contain security-sensitive details. Otherwise, open a minimal issue asking the maintainer for a private contact method. Do not put exploit details or sensitive vehicle/account data in a public issue.

Before submitting, remove all real:

- VINs and number plates
- BLE MAC addresses and device identifiers
- QR images and QR payloads
- tokens, passwords, `dkey` values, and encrypted credential blobs
- decrypted server responses and unredacted Logcat output

Include the affected revision, Android version, reproduction conditions, and the smallest redacted evidence needed to understand the issue.

## Supported versions

There is no formally supported release line. Reports are evaluated against the current default branch when the maintainer has time. Forks and redistributed builds are maintained by their respective owners.

## Safety

Do not test a report by repeatedly operating a vehicle or by sending commands to a vehicle or account you do not own or have explicit authorization to use. Keep the vehicle parked, retain a physical key, and keep people and obstacles clear of moving parts.
