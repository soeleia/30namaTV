# Development

How to build, check and release 30namaTV. What the app does for viewers is in the [README](README.en.md).

## Build

Install Android Studio (or JDK 21) and these SDK packages, then build:

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'build-tools;37.0.0' 'platform-tools'
git clone https://github.com/soeleia/30namaTV.git
cd 30namaTV
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk`: one universal APK for Android 6.0+ (API 23), shrunk by R8. Android Studio's bundled JDK 21 works, and the Gradle wrapper verifies its own checksum. A local build is signed with your Android debug key unless all four `RELEASE_*` variables are set ([signing](docs/setup.md#stable-apk-signing)).

### Fonts

A fresh checkout builds with the bundled OFL fonts, Inter and Vazirmatn. Maintainer releases use Peyda for Persian headings and Yekan Bakh for Persian body text, which are private build inputs ([fonts](docs/setup.md#fonts)). English text uses Inter everywhere.

### API keys in your own builds

Public APKs carry no API keys; each viewer enters their own under Settings > Services. For your own builds, the git-ignored `local.properties` can hold fallbacks, used only while no key is saved in Settings:

```properties
tmdb.token=
subdl.key=
opensubtitles.key=
gemini.key=
anthropic.key=
openai.key=
```

Keys compiled into an APK can be extracted, so don't share an APK built with yours. How each key is checked, and the live translation test: [service keys](docs/setup.md#service-keys).

## Checks

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/tv-checks.py --serial emulator-5556
```

Give `--serial` your disposable emulator's serial (`emulator-5556` here; `adb devices` lists them), never an emulator or TV you are signed in on: the suites replace local preferences and use mocked account data, and the runner refuses physical devices. `--check <name>` reruns one suite. `--release <apk>` then replaces the debug build with that release APK and checks that it starts and stays up, which is how CI catches a crash only the R8-shrunk build has. Output, logcat and screenshots go to `app/build/reports/tv-checks/`.

The suites cover startup and request cancellation, the motion logo, player controls and seeking, subtitle layout and direction, Make Persian subtitles, resume and Start from the beginning, watched episodes, removing from Continue Watching, poster captions in English, My Lists, search and the Persian keyboard, the genre page, catalogue filters, the updater (with home's notice and the Settings dot) and trailers. [docs/testing.md](docs/testing.md) has the details and the manual checklist for a physical TV.

The trailer suite tries the public Animals trailer. If its CDN is unreachable, the log says `PUBLIC_UNAVAILABLE` and the suite uses a silent fixture instead, which checks interaction and lifecycle but not live CDN playback.

## CI

GitHub Actions builds and tests every push to `main`, every pull request and manual runs. It runs the instrumented debug APK on an Android 11 TV emulator (two cores, 1.5 GB RAM) and puts the optimized release APK through full lint, signature and ZIP alignment checks, then installs that release APK on the same emulator and fails unless it starts and stays up without a fatal exception. Each run uploads the APKs (`optimized-apk` is the installable one), lint and test reports, logs and screenshots.

Emulator timing says nothing about speed on a real TV; see [performance evidence](docs/performance.md).

## Release

Bump `versionCode` and `versionName` in `app/build.gradle.kts`, push to `main`, and once Actions passes, tag that commit `vVERSION`. A tag on a commit that is not on `main` fails the build before anything is signed. The tag build publishes `30namaTV.apk` to GitHub Releases, which is what the in-app updater installs. Full steps, signing secrets and the private font archive: [docs/setup.md](docs/setup.md).

## Screenshots

The README screenshots are unmodified captures from a disposable Android 16 emulator, not photos of a TV, and show no private account data. The browsing screens show the optimized release on the live catalogue as a guest, after artwork loaded; the trailer is Aparat's re-upload, with its uploader's watermark.

Guests can't play 30nama titles, so the player and Make Persian subtitles screens play Blender's open film [Tears of Steel](https://mango.blender.org/) (CC BY 3.0) from a public test stream, through an instrumented debug build that stands in for 30nama's answer with the film's name, year and saved point. Its Persian name is ours. The translation shown has only just started, against a stand-in model that never answers, so no translated text appears.

## Branding

The logo, loading animation and launcher artwork, and where each came from: [docs/branding.md](docs/branding.md).
