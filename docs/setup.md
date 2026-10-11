# Build and GitHub setup

The repository is `soeleia/30namaTV`. Pushes to `main`, pull requests and manual workflow runs build and test the app. A `v*` tag publishes a release only after build/lint/unit checks and Android 11 TV regressions succeed.

## Toolchain

- JDK 21 (the app's bytecode targets Java 17).
- Android SDK platform `android-37.0`, build-tools 36.0.0 and 37.0.0.
- Gradle 9.8.0, AGP 9.4.1 and Kotlin 2.4.20, pinned in the project.
- ADB and Python 3 for the native regression runner.

`local.properties`, account data, QR login images, APKs and signing keys are ignored by Git. Build artifacts live in Actions or Releases.

## Fonts

Inter and Vazirmatn are bundled under the SIL Open Font License, with notices in `app/src/main/assets/licenses/`. A fresh checkout uses Vazirmatn for Persian.

The maintainer's requested fonts are five private inputs in `app/src/main/res/font/`:

```text
peyda_bold.ttf
peyda_medium.ttf
peyda_semibold.ttf
yekanbakh_regular.ttf
yekanbakh_bold.ttf
```

Supply all five or none. They are ignored by Git. With none, Gradle adds the open-font aliases in `app/src/openFonts/res`; with all five, it uses the private fonts. The encrypted `.github/private-fonts.zip.gpg` preserves the maintainer's input without exposing raw commercial fonts in the public checkout. `PRIVATE_FONTS_PASSWORD` in Actions decrypts it; pull requests and forks without that secret use the open fallback. Obtain your own font permissions before distributing private-font builds.

To refresh the encrypted input, zip just those five files at the archive root, then:

```sh
gpg --symmetric --cipher-algo AES256 --output .github/private-fonts.zip.gpg /path/to/private-fonts.zip
gh secret set PRIVATE_FONTS_PASSWORD --repo soeleia/30namaTV
```

The secret must match the archive password. Do not commit the raw ZIP, font files or password.

## Stable APK signing

The project's release key is private and backed up locally in ignored `.signing/30namatv-release.jks`. Keep a secure backup: a replacement key cannot update already installed APKs signed with the original key.

Repository secrets:

| Secret | Purpose |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Base64 of the dedicated JKS file |
| `RELEASE_STORE_PASSWORD` | Store/key password, shared by this key |
| `PRIVATE_FONTS_PASSWORD` | Decrypts the private font archive |

The key alias is `30namatv`. Secrets are restored only for trusted push/manual/tag builds, not pull requests. A tag build fails if the release key is unavailable. Signing files and passwords never appear in public artifacts or source.

For a signed local release, set:

```sh
export RELEASE_KEYSTORE='/absolute/path/30namatv-release.jks'
export RELEASE_STORE_PASSWORD='YOUR_STORE_PASSWORD'
export RELEASE_KEY_ALIAS='30namatv'
export RELEASE_KEY_PASSWORD='YOUR_KEY_PASSWORD'
./gradlew :app:assembleRelease --no-configuration-cache
```

Supply all four variables together. To replace Actions secrets without printing their contents:

```sh
base64 < /path/to/30namatv-release.jks | tr -d '\n' | gh secret set RELEASE_KEYSTORE_BASE64 --repo soeleia/30namaTV
gh secret set RELEASE_STORE_PASSWORD --repo soeleia/30namaTV
```

## Publish an update

1. Increase `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Run local checks and test playback/navigation on the TV.
3. Commit and push to `main`; wait for the Actions build to pass.
4. Tag the tested commit, for example `git tag v1.0.1` and `git push origin v1.0.1`.

Actions uploads the optimized APK and SHA-256 checksum to the tag's GitHub release after emulator checks pass. The SHA file uses `dist/30namaTV.apk` as its path; verify from a folder containing `dist/30namaTV.apk` using `sha256sum -c SHA256SUMS`, or compare the hash directly.

The workflow pins actions to verified commit SHAs and excludes Gradle's local build cache, which can contain private font resources. APK tests, reports and screenshots are retained as workflow artifacts; tagged releases keep the installable APK. Forks should use their own signing key and remove/change the encrypted font input if they do not have its password.

## Future releases and in-app updates

Increment both `versionCode` and `versionName` in `app/build.gradle.kts`, commit and push, then tag that tested commit as `vVERSION`. Keep the same signing key. The release job waits for build and TV tests, then publishes `30namaTV.apk`; branch artifacts alone do not trigger in-app updates.

The updater reads `https://api.github.com/repos/soeleia/30namaTV/releases/latest`, skips drafts/prereleases and uses only the repository's `30namaTV.apk` asset. Checks are daily after opening, with a manual Settings check available. Android's Download Manager retains the download; Settings shows progress and opens the system installer after validating the APK. No account token is sent to GitHub. Update files stay in the app's own external files directory.

Android requires the user to approve installation. If unknown-source permission is needed, allow installation for 30namaTV and select Install again. A different signing key is rejected; never uninstall automatically or reset a user's account to force an update.
