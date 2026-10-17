# TV verification

## Automated checks

`scripts/tv-checks.py` runs each native instrumentation suite independently and fails unless its final instrumentation result explicitly reports success. It saves raw output, logcat, emulator properties and PNG screenshots in `app/build/reports/tv-checks/`.

Use `--check search`, for example, for a targeted rerun. The `performance` check holds ancillary requests to prove that home content can appear without waiting for menu/account extras; it also checks request cancellation and stable late-row order. This is a responsiveness regression test, not a network speed benchmark.

The CI device is Android 11 TV (API 30), 1080p, two cores, 1536 MB RAM, 128 MB heap. It exercises the platform's real RTL layout and D-pad focus behavior. Software-rendered emulator results cannot represent Sony's decoder/GPU speed or the user's network.

## Manual 2017 Sony TV check

Install the APK on the Android 11 Sony TV and verify:

1. Cold launch, quick relaunch, home focus movement and opening a title. Home content should appear as soon as its catalogue response is ready; the motion logo must not delay it. A relaunch draws the last home from disk at once (the logo still plays its first 1.5 s), then the fresh rows land in place without moving focus. Offline with a saved home, home still shows with a quiet notice.
2. Search `friends`: results scroll vertically; Down enters the grid, Up from its first row returns to search, and the edge arrow reaches the sidebar. Search replacement and voice search should show only the current query.
3. My Lists: all three actions still open their original lists. Move rapidly between posters and rows, including the end of a paginated list.
   Genres: home shows every genre, then More; open More. The full four-column page should open immediately, every genre should open its catalogue, and Back should restore the focused genre/home tile.
   Filters: on Movies, Up from the first column reaches «ژانر»; set IMDb ۷+ and «دههٔ ۲۰۱۰», then sort by IMDb. Every poster should score 7 or more and date from 2010–2019, scrolling should keep finding them, and × should bring the whole catalogue back in the same order.
4. Animals teaser: video starts, pause/play work, controls hide after inactivity, backgrounding stops playback, and returning resumes correctly. Outside Iran, or through a VPN, with a TMDB key: a title whose Trailer shows YouTube's mark plays it in the app with the same controls, and Back returns to the title page.
5. Full playback: controls hide about five seconds after the last remote interaction while playing. Paused controls remain visible. Opening audio/quality/subtitle menus should keep them open until dismissed.
6. Pause and seek forward using remote buttons and the progress bar: playback resumes after the committed seek. Cancelling the seek or seeking backward should preserve the previous paused state.
7. Persian/English player titles: `I`, `F` and `d` remain complete. Subtitle paragraphs stay centered across the screen while controls/title information are visible. Check punctuation with Persian and mixed English/numeric cues from the actual Friends subtitle file.
8. Intro/recap skip buttons show a shrinking fill based on the marked segment's remaining playback time. It freezes while paused, updates after a seek and disappears when the segment ends. Automatic skip/next-episode settings retain their behavior.
9. Audio/subtitle/quality/speed option labels share a consistent column edge in both interface languages. Test larger system font size and reduced motion.
10. Updates: open Settings > App > App update. Check download progress, retry after a failed connection, allow this app as an installation source when Android asks, and confirm the system installer. Reject an APK from a different package/signing key or with an older version code. A main-branch artifact must not appear as a release update.
11. Leave playback running for 20 minutes, switch episodes/quality/subtitles, then return home. Check for dropped frames, audio drift, memory growth and decoder failures in logcat.

## Collect evidence from the TV

These read-only commands can capture timing/memory evidence after a manual session:

```sh
adb -s YOUR_TV_IP:5555 shell dumpsys meminfo com.doxigo.tv30 > tv-memory.txt
adb -s YOUR_TV_IP:5555 shell dumpsys gfxinfo com.doxigo.tv30 framestats > tv-frames.txt
adb -s YOUR_TV_IP:5555 logcat -d -v threadtime > tv-logcat.txt
```

Review logs before sharing: a real account/network session may contain identifying URLs or server messages. The exact photographed Friends cue cannot be certified from synthetic subtitle fixtures; its original cue text is required to distinguish malformed source text from rendering direction.
