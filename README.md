# 30namaTV

**English** · [فارسی](README.fa.md)

An **unofficial, independent** native Kotlin app for browsing and watching 30nama on Android TV. **This is not the official 30nama app and is not affiliated with or endorsed by 30nama.** Built with Android Views, Leanback and Media3, with Persian and English interfaces and D-pad navigation. A 30nama account/subscription is required for subscription content.

[![Android TV build](https://github.com/soeleia/30namaTV/actions/workflows/android.yml/badge.svg)](https://github.com/soeleia/30namaTV/actions/workflows/android.yml)

Android 6.0+ (API 23). Universal APK; no React Native runtime. Release builds use R8 optimization and resource shrinking. Browsing stays available while account/menu extras load, posters are decoded to display size, and search requests are cancelled when replaced.

Search uses a six-column vertical grid, with its own Persian keyboard for TVs whose keyboard has no Persian layout. Home shows every genre in its row, ending in a link to the dedicated four-column genre page. Movies, Series and Anime can be narrowed by genre, country, a minimum IMDb score and decade, alone or together, under any of the six sort orders.

## Features

- **Home.** 30nama's promoted titles page left and right on a hero with one **More info** button; the remote's play key starts the title. Under Suggested, **Most popular movies** and **Most popular series** rank 30nama's ten most popular of each, a large numeral beside each poster. Home draws at once from the last saved copy, then refreshes in place. The artwork of the titles next to the one in focus is fetched ahead, so moving along a row rarely waits.
- **Posters.** Hold OK on any poster for quick actions: More info, add or remove it from your three lists, and the trailer when one exists. On Continue Watching, holding OK offers to remove the title or open its page.
- **Trailers.** The title page's **Trailer** plays in the app's own player: Aparat's upload in Iran, with no VPN and often with Persian subtitles, and the studio's YouTube trailer elsewhere, each falling back to the other. YouTube's mark on the button says when it plays from YouTube; hold OK on it to choose. YouTube needs youtube.com within reach and a WebView from 2019 on.
- **Search.** Recent searches return when the box is empty, above 30nama's suggestions. **Persian keyboard**, beside the box, opens the app's own Persian keys (with ZWNJ, Persian digits, ی and ک), driven by the remote.
- **Player.** Above the seek bar sits only the title: its poster, the English name, the Persian one, then what plays, its IMDb score and year. Under the bar are the elapsed time, the length with when it ends, and one row of buttons, as on YouTube. The controls open on play/pause. Left and right scrub in five-second steps that speed up while held, and the seek lands a second after the last press. The light part of the seek bar shows what is ready, including the disk read-ahead (Settings > Playback > Preload), and the next episode's opening is fetched during the credits.
- **Resume.** Play and Continue Watching pick up where you left off. The TV keeps its own exact point for each title, because 30nama's saved point can lag behind; it uses whichever of the two is further on. A title picked up part-way offers **Start from the beginning** for its first ten seconds.
- **Subtitles.** **Find subtitles** searches SubDL and OpenSubtitles for the film or exact episode, Persian first, and loads the chosen file without stopping playback, converting old Windows-1256 Persian files. **Make Persian subtitles** has Claude, OpenAI or Gemini translate the English subtitle (30nama's, or one from the subtitle sites) into natural, spoken Persian in a few minutes while the film plays, then switches it on. **Subtitle timing** shifts any subtitle earlier or later in quarter-second steps. Persian subtitles show Persian ی, ک and digits, and a file typed for left-to-right players (its full stops first) is laid out the way it was written.
- **Episodes.** Watched episodes carry a check, partly watched ones a progress bar and the minutes left, and Play resumes where you are. The history is kept on the TV (30nama keeps one position per title) and cleared on sign-out.

## Install

Download `30namaTV.apk` from [Releases](https://github.com/soeleia/30namaTV/releases), or the `optimized-apk` artifact of a successful [Actions run](https://github.com/soeleia/30namaTV/actions).

Enable installation from your chosen file manager on the TV and open the APK, or use ADB:

```sh
adb connect YOUR_TV_IP:5555
adb -s YOUR_TV_IP:5555 install -r 30namaTV.apk
```

The application ID is `com.doxigo.tv30`. Public builds use one dedicated release key so subsequent public APKs install as updates. Earlier personal test APKs used a different local debug key: moving from those to the public release requires uninstalling the old build, which removes its local settings/login. The separately supplied TV test APK retains the original test key for an in-place update.

## Updates

Each time home comes to the front (at launch, back from the player, out of standby), the app checks the latest stable GitHub release, at most once every six hours, and automatically downloads a newer APK using Android's Download Manager. Downloads continue in the background. Once a new version is downloaded and verified, home says so in its top corner, "Version 1.5.0 is ready" with **Install**: press Up from the featured title to reach it and OK to install. Back puts that version's notice away for good, and a red dot stays on the rail's **Settings** icon until it is installed. That dot leads to **Settings > App > App update**, where you can also follow a download, check again or install. Android requires installation confirmation and may ask you to allow updates from this app.

Before installation, the app verifies the package name, newer version code and signing certificate. It rejects incompatible or invalid APKs. The first migration from an older personal test key to the public release key still requires the manual installation described above.

## Service keys

TMDB, the player's subtitle search and its Persian subtitle maker use API keys that each viewer supplies. Open **Settings > Services** and select a service. Scan the QR code with a phone on the same Wi-Fi: it opens a page served by the TV that links to where the key is made and sends the pasted key straight to the TV. You can also type the key with the TV's keyboard or the Google TV app, or use **Paste**. The sheet checks the key, says whether it works, and keeps it in the app's private storage on that TV. Without a key that feature stays off; browsing and 30nama playback need none.

- **TMDB** adds the trending row, Persian names 30nama lacks, More Like This, trailers and episode stills. Its API page offers an API key and an API read access token; either works.
- **SubDL** and **OpenSubtitles** power **Find subtitles** in the player (Audio & Subtitles → Subtitles), which appears once either has a key. A free OpenSubtitles key downloads five subtitles a day.
- **Claude**, **OpenAI** and **Gemini** power **Make Persian subtitles** in the same column. **Translation model**, above their keys, picks the model and with it the service: Claude Opus 5.5 (the most faithful Persian, about $0.92 a two-hour film), Claude Sonnet 5.5 or GPT-6.1 Sol (about $0.46), GPT-6 Astra (about $2.30), or Gemini 3.8 Flash (free within Gemini's free tier, but it slips more often). Until you pick one, the best model of the first service with a key, in that order, translates; the paid ones charge your own account. The result is kept on the TV, so watching again costs nothing. These providers refuse connections from Iran without a VPN.

## Build locally

Install Android Studio, JDK 21 and these SDK packages:

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'build-tools;37.0.0' 'platform-tools'
git clone https://github.com/soeleia/30namaTV.git
cd 30namaTV
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk`. Android Studio's bundled JDK 21 also works. The Gradle wrapper verifies its distribution checksum. A local build uses your Android debug key unless all four `RELEASE_*` variables described below are supplied.

The public checkout includes OFL-licensed Inter and Vazirmatn, so it builds without private font files. Maintainer APKs retain the requested Peyda headings and Yekan Bakh body text; see [fonts and build inputs](docs/setup.md#fonts). English text uses Inter throughout.

Public APKs carry no API keys (see [service keys](#service-keys)). For your own builds, ignored `local.properties` can hold fallbacks used while no key is saved in Settings: `tmdb.token=`, `subdl.key=`, `opensubtitles.key=`, `gemini.key=`, `anthropic.key=` and `openai.key=` ([details](docs/setup.md#service-keys)). Keys compiled into an APK can be extracted, so don't share an APK built with yours, and never put an account password there.

## Checks and screenshots

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/tv-checks.py --serial emulator-5554
```

Use a disposable emulator. Fixture checks replace local preferences and use mocked account data; the runner rejects physical devices. They cover startup/cancellation, logo playback and cleanup, five-second control hiding, play/pause focus, accelerating and self-landing seeks, cancelled/backward seeks, column alignment, Persian lines fitting their width, subtitle direction and centering, making Persian subtitles (part by part, with a refused key), title clipping, intro/recap countdown, watched episodes, My Lists, vertical search and recent searches, the complete genre page, catalogue filters (D-pad, parameters, stale pages, paging, clear and empty results), update validation/status, home's update notice and the rail's Settings dot, and trailer playback/lifecycle.

The trailer check attempts the public Animals trailer. If its CDN is unreachable, logs state `PUBLIC_UNAVAILABLE` and use a silence fixture for interaction/lifecycle checks. That result does not certify live CDN playback.

GitHub Actions runs the instrumented debug APK on an Android 11 TV emulator with two cores and 1.5 GB RAM. The optimized release APK also passes build, full lint, signature and ZIP alignment checks. Each run uploads APKs, lint/unit-test reports, instrumentation logs and screenshots. See [setup and release instructions](docs/setup.md) and [manual Sony TV checks](docs/testing.md) and [performance evidence](docs/performance.md). Emulator timing does not establish performance on a 2017 Sony TV.

## Interface

| | |
|:-:|:-:|
| ![Home: the billboard of 30nama's promoted titles](docs/screenshots/home-fa.png)<br>Home | ![Most popular movies, ranked 1 to 10 beside their posters](docs/screenshots/ranked-fa.png)<br>Most popular movies |
| ![Interstellar's title page with Play and Trailer](docs/screenshots/title-fa.png)<br>Title page | ![Interstellar's trailer from Aparat, paused in the app's trailer player](docs/screenshots/trailer-fa.png)<br>Trailer, played in the app |
| ![The player resumed five minutes into Tears of Steel, its poster, names and year above the bar and Start from the beginning on offer](docs/screenshots/player-fa.png)<br>Player, resumed | ![The same player in English, laid out left to right](docs/screenshots/player-en.png)<br>Player in English |
| ![Audio & Subtitles with Make Persian subtitles, by Gemini 3.8 Flash](docs/screenshots/subtitles-ai-fa.png)<br>Make Persian subtitles | ![Make Persian subtitles under way while the film plays](docs/screenshots/subtitles-ai-progress-fa.png)<br>Translation under way |
| ![Search for جوکر typed on the app's Persian keyboard](docs/screenshots/search-keyboard-fa.png)<br>Search with the Persian keyboard | ![Movies narrowed to the 2010s and IMDb 8+](docs/screenshots/catalogue-filters-fa.png)<br>Movies, filtered |
| ![Categories and genres](docs/screenshots/genres-fa.png)<br>Categories and genres | ![Settings, Services: TMDB, the subtitle sites and the models that make Persian subtitles](docs/screenshots/settings-services-fa.png)<br>Settings: services |

The browsing screenshots show the optimized release on the live catalogue as a guest, on a disposable Android 16 emulator, after artwork loaded. The trailer is Aparat's re-upload, with its uploader's watermark. Guests can't play 30nama titles, so the player and **Make Persian subtitles** screenshots play Blender's open film [Tears of Steel](https://mango.blender.org/) (CC BY 3.0, Blender Foundation; poster from [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Tos-poster.png)) from a public test stream, through an instrumented debug build that stands in for 30nama's answer with the film's name, year and saved point; its Persian name is ours. The translation shown has only just started, against a stand-in model that never answers, so no translated text appears. The screenshots contain no private account data. The emulator's status bar is visible; these are unmodified captures, not photos of a physical TV. The original logo and loading animation are documented in [branding provenance](docs/branding.md). 30nama branding and third-party font licenses retain their respective owners' rights; publishing this client does not imply affiliation or a license to reuse those assets separately.
