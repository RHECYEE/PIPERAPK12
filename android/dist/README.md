# Prebuilt debug APK

A checked-in build artifact, kept here only because this repository has no
release pipeline to attach it to. It is not part of the build: `assembleDebug`
writes to `android/app/build/outputs/apk/debug/` as usual.

| | |
| --- | --- |
| File | `piper-tts-mvp-arm64-v8a-debug.apk` |
| ABI | arm64-v8a (covers essentially every current Android phone) |
| Size | 44,535,358 bytes |
| sha256 | `23896100af2a1fffa2ef303a4494478d9c66f1a81b62471d232baf5dbc635178` |
| Built from | `ffbbd08` |
| Min / target SDK | 24 / 35 |
| Permissions | none, including no INTERNET |

Install with `adb install piper-tts-mvp-arm64-v8a-debug.apk`, or copy it to the
device and open it (Android will ask you to allow installing from this source).

First launch unpacks ~19 MB of espeak-ng data and the bundled voice into app
storage, so it takes a few seconds before the voice is ready.

Other ABIs (armeabi-v7a, x86_64) and a universal APK are not checked in; build
them with `./gradlew assembleDebug`.
