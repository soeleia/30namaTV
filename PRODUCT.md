# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

A paying 30nama subscriber watching films, series and anime on a living-room Android TV with a D-pad remote, plus friends of theirs who also subscribe and receive the APK directly. They already know the 30nama catalogue; they open the app to resume what they were watching or to find the next thing, from a sofa three metres away. Persian is the default interface language, English the alternative. Most catalogue titles arrive in English.

## Product Purpose

30namaTV is an unofficial native client for the 30nama streaming service, built because the official 30tv app (React Native, 53 MB, about 170 MB of RAM on its login screen) hangs and lags on modest TVs. It must do everything the official TV app does, through the same account and API, while staying fast and small on low-memory hardware. Success: on a TV where the official app stutters, browsing feels instant and playback starts and resumes without hangs.

## Positioning

The same 30nama account and catalogue, rebuilt on the TV's own toolkit (Leanback and Media3) instead of a JavaScript runtime: a 1.75 MB APK using about 40 MB of RAM with the home screen loaded, no pre-roll ads, and progress that syncs with the official apps.

## Operating Context

- Driven only by a D-pad remote: up, down, left, right, OK and Back. No touch and no pointer.
- Sign-in by QR code: the TV shows a 30nama.com/auth link, the viewer approves it on a phone, and the TV polls for the session token. Passwords never reach the TV. Guests can browse but cannot play.
- Watching sessions are long. Viewers jump between Continue Watching, the details page and the player, and expect resume points to match the website and the official apps.
- Network quality varies (Iranian connections, VPNs with several exit IPs). The API host is discovered at startup from published lists. Stream segments are signed for the requesting IP.
- Reference device: Android TV OS 11 on a 1080p TV. Friends' TVs may run older Android (minimum supported is Android 6, API 23).

## Capabilities and Constraints

- Parity with the official TV app: QR login, guest mode, a home of Continue Watching, Featured, Suggested, Movies, Series and Anime, search, categories and genres with six sort orders, the user's lists (favourite, watchlist, notify), title details with seasons, episodes and cast, settings (account, language, sign out) and a full player (quality, audio channels, audio language, subtitles with size, colour and background, episode picker, previous and next, skip intro, recap and to the next episode, resume, progress sync every 15 seconds).
- Departures from the official TV app's home, made on purpose: two ranked rows under Suggested, «محبوب‌ترین فیلم‌ها» and «محبوب‌ترین سریال‌ها» ("Most popular movies" / "Most popular series"). Each holds the first ten titles of the catalogue's own «محبوب‌ترین» order (`archive`, `orderby=favorite`), numbered as Netflix numbers its Top 10, and ends in the whole catalogue in that order. `home_stream` once carried a `top_10` and a `new_releases` section, and the official TV app still reads both, but the server no longer sends either (checked in October 2026, as a guest and signed in, in Persian and English). There is no new-releases row, because Movies and Series already lead with the latest titles.
- Out of scope on purpose: VAST pre-roll ads, the official app's self-updater, its seasonal "snow" theme and animation toggle. Payments, OTP login, downloads and notifications exist in the shared SDK but not in the TV app.
- Hard constraint: low-end TV hardware (2 GB RAM class). Every visual choice has to be cheap to render. No blur, heavy shadows or video backgrounds. The requested original launch motion is the sole video exception: 1080p, 1.41 MB, cancelled as soon as home is ready and released on exit/stop.
- Bilingual RTL/LTR. Persian is the default and right-to-left. English switches the whole layout to left-to-right.
- Distributed by sideloading the APK to friends, so updates are manual.

## Brand Commitments

- App name on the TV launcher: **30namaTV**, with the original 30nama logo and motion intro requested by the user. The package stays `com.doxigo.tv30`.
- Typography chosen by the user: English text in **Inter**, Persian titles in **Peyda** (commercial fontiran.com family; the user supplied the TTFs), normal text in **Yekan Bakh** (Reza Bakhtiarifard, commercial; the user supplied the TTFs, Fa-En cut: Regular, Medium, Bold).
- Visual direction (user's choice, after rejecting a Scandinavian BeoVision rendition): the category standard, Netflix's TV app played straight, with the Apple TV app's polish in motion and typography as the second craft bar. Netflix and Apple TV set the quality level.
- The content and catalogue belong to 30nama. The 30nama logo is not used as this app's identity.

## Evidence on Hand

- The official APK (`~/Downloads/30tv-3.2.3.apk`) and its decompiled behaviour, which is the reference for every flow and API call.
- Live API responses captured during development (home, single, stream, continue watching, lists). Their personal fields are never committed.
- No testimonials, metrics or user research beyond the owner's own experience: the official app hangs on their TV.

## Product Principles

1. Speed is the feature. A change that costs frames or memory on a modest TV needs a strong reason.
2. Parity before novelty. Anything the official TV app can do, this app can do. Departures are deliberate and documented.
3. Remote first. Every action is reachable with the D-pad, and focus is never ambiguous from across the room.
4. Persian first, bilingual always. Persian typography and orthography are correct, not merely rendered. English is a complete alternative.
5. Stay in sync. Resume points, lists and sessions behave exactly as on the website and the official apps.

## Accessibility & Inclusion

Ten-foot viewing: text and focus must read clearly from a sofa. Subtitles offer five sizes, white or yellow text, and black, translucent or no background.
