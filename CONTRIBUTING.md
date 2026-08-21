# Contributing

This repository is an experimental proof of concept and is maintained only when the author has time. Regular support, issue responses, pull-request reviews, releases, and compatibility updates are not guaranteed.

The recommended way to use or extend this project is to fork the repository and maintain the changes you need in your own fork. You do not need to wait for an upstream feature request or pull request to be accepted.

## Pull requests and issues

Issues and pull requests may be submitted for reference, but they may remain unanswered or unreviewed for an extended period. Please do not depend on this repository for time-sensitive fixes or production support.

If you submit a pull request:

- Keep the change focused and explain how it was tested.
- Run `./gradlew test lint assembleRelease` before submitting when possible.
- Do not include real VINs, number plates, BLE MAC addresses, QR images, tokens, passwords, `dkey` values, server responses, or unredacted Logcat output.
- Clearly identify behavior that has been validated only with the Korean BYD Watch `dkey` flow.
- Do not claim that a GATT write completion proves the vehicle performed a command unless a vehicle response was independently verified.

## Safety and responsibility

Test vehicle-control commands only on a vehicle you own or are explicitly authorized to use. Keep the vehicle parked in a safe location and check the surroundings before testing doors, windows, the trunk, climate control, lights, or the horn.

By using or modifying this project, you are responsible for reviewing the code, regional compatibility, third-party rights, local laws, and operational risks for your own use case.
