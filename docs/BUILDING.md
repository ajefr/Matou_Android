# Building Matou

## Requirements

- JDK 17 or later
- Android SDK with platform **35** and build-tools (Android Studio installs them, or use `sdkmanager`)
- No NDK: the app is pure Kotlin

Point Gradle to the SDK with `ANDROID_HOME`, or a `local.properties` file:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Build

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # build and install on a connected device (adb)
```

Android Studio: *File → Open* the repository folder, then run the `app` configuration.

## Tests

The terminal core (`core/`) is plain Kotlin and its tests run on the JVM, without the Android SDK:

```sh
MATOU_CORE_ONLY=1 ./gradlew :core:test
```

They cover the VT parser (cursor movement, erase, scroll regions, SGR colors, alternate screen,
wide characters, DEC line drawing, OSC, device reports), key encoding, mouse reporting, the Telnet
codec and auto-login.

## Screenshots

The images in `docs/screenshots/` are rendered without a device or emulator, by a Robolectric test
that uses Android's native graphics on the JVM:

```sh
MATOU_SCREENSHOTS=$PWD/docs/screenshots ./gradlew :app:testDebugUnitTest
```

Without `MATOU_SCREENSHOTS` the test is skipped. The first run downloads the Android runtime used by
Robolectric (about 150 MB).

## Release signing

Release builds are signed with a key given through environment variables. The keystore is
**never** committed (`*.p12`, `*.jks` and `*.keystore` are git-ignored).

| Variable | Value |
|---|---|
| `MATOU_KEYSTORE_PATH` | Path to the PKCS#12 keystore |
| `MATOU_KEYSTORE_PASSWORD` | Keystore password |
| `MATOU_KEY_ALIAS` | Key alias (default `matou`) |
| `MATOU_KEY_PASSWORD` | Key password (defaults to the keystore password) |

```sh
MATOU_KEYSTORE_PATH=~/keys/matou-release.p12 \
MATOU_KEYSTORE_PASSWORD=… \
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

Without these variables, the release build is signed with the debug key, which is fine for testing
but cannot update an install signed with the release key.

To create a new key (only for a fork: the official key must stay the same for updates to install):

```sh
keytool -genkeypair -storetype PKCS12 -keystore matou-release.p12 -alias matou \
  -keyalg RSA -keysize 4096 -validity 10000
```

Check the signature of an APK:

```sh
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app-release.apk
```

## Continuous integration

`.github/workflows/build.yml` runs on every push to `main`, on pull requests, on `v*` tags and on demand:

1. runs the core tests and builds the debug APK (artifact `matou-debug-apk`);
2. if the signing secrets are available, rebuilds the keystore from them, builds and verifies the
   signed release APK (artifact `matou-release-apk`), then deletes the keystore from the runner.

Repository secrets (*Settings → Secrets and variables → Actions*):

| Secret | Content |
|---|---|
| `MATOU_KEYSTORE_BASE64` | The keystore file encoded in base64 (`base64 -w0 matou-release.p12`) |
| `MATOU_KEYSTORE_PASSWORD` | Keystore password |
| `MATOU_KEY_PASSWORD` | Key password |
| `MATOU_KEY_ALIAS` | `matou` |

Secrets are not passed to pull requests from forks, so those only build the debug APK.

## Publishing a release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`, and `CHANGELOG.md`.
2. Merge into `main`, then tag: `git tag v0.2.0 && git push origin v0.2.0`.
3. Download `matou-release-apk` from the tag's workflow run and attach it to a new GitHub release,
   with the changelog section as release notes.
