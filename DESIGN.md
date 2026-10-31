---
name: 30namaTV
description: Netflix's TV app played straight on Leanback, with the Apple TV app's polish in motion and type; Persian first, white is the only focus.
colors:
  brand-red: "#E50914"
  free-green: "#46D369"
  imdb-yellow: "#F5C518"
  ink: "#141414"
  ink-deep: "#0B0B0B"
  surface: "#1F1F1F"
  surface-high: "#2E2E2E"
  hairline: "#333333"
  button-idle: "#4D4D4E"
  text: "#FFFFFF"
  text-body: "#E5E5E5"
  text-dim: "#B3B3B3"
  meta-origin: "#999999"
  text-faint: "#808080"
  glass-primary: "#40FFFFFF"
  glass-secondary: "#24FFFFFF"
  glass-disabled: "#12FFFFFF"
  hero-chip: "#33FFFFFF"
  focus-wash: "#33FFFFFF"
  scrim-mid: "#B3141414"
  notice-glass: "#D9141414"
  progress-track: "#66808080"
  settings-danger: "#FF5C5C"
  tier-golden: "#E3B341"
  tier-blue: "#4D8EFF"
typography:
  display:
    fontFamily: "Inter Bold (Latin names), Peyda Bold (Persian names)"
    fontSize: "38sp"
    fontWeight: 700
    letterSpacing: "-0.01em on Latin, 0 on Persian"
  headline:
    fontFamily: "Peyda Bold"
    fontSize: "30sp"
    fontWeight: 700
  numeral:
    fontFamily: "Peyda Bold"
    fontSize: "40sp"
    fontWeight: 700
  page-title:
    fontFamily: "Peyda Bold"
    fontSize: "28sp"
    fontWeight: 700
  dialog-title:
    fontFamily: "Peyda Bold"
    fontSize: "21sp"
    fontWeight: 700
  title:
    fontFamily: "Peyda Bold"
    fontSize: "20sp"
    fontWeight: 700
    lineHeight: 1.15
  subtitle:
    fontFamily: "Peyda SemiBold"
    fontSize: "20sp"
    fontWeight: 600
  tile:
    fontFamily: "Peyda Bold"
    fontSize: "18sp"
    fontWeight: 700
  synopsis:
    fontFamily: "Yekan Bakh (Persian), Inter (English)"
    fontSize: "18sp"
    fontWeight: 400
    lineHeight: "1.65 Persian, 1.45 English"
  body:
    fontFamily: "Yekan Bakh"
    fontSize: "17sp"
    fontWeight: 400
  nav:
    fontFamily: "Peyda SemiBold"
    fontSize: "17sp"
    fontWeight: 600
  meta:
    fontFamily: "Yekan Bakh"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "Yekan Bakh"
    fontSize: "16sp"
    fontWeight: 400
  caption:
    fontFamily: "Yekan Bakh"
    fontSize: "16sp"
    fontWeight: 400
  card-title:
    fontFamily: "Inter Medium"
    fontSize: "15sp"
    fontWeight: 500
  card-meta:
    fontFamily: "Yekan Bakh"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: 1.5
rounded:
  dot: "3dp"
  artwork: "6dp"
  row: "12dp"
  chip: "14dp"
  panel: "20dp"
  pill: "22dp"
  skip-pill: "24dp"
spacing:
  inset: "2dp"
  icon-gap: "8dp"
  rail-gap: "8dp"
  button-gap: "12dp"
  card-gap: "16dp"
  row-gap: "20dp"
  pad-label: "22dp"
  pad-icon: "16dp"
  safe-edge: "48dp"
  rail: "64dp"
  content-start: "96dp"
components:
  button-primary:
    backgroundColor: "{colors.glass-primary}"
    textColor: "{colors.text}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 22dp 0 16dp"
    height: "44dp"
  button-primary-focus:
    backgroundColor: "{colors.text}"
    textColor: "{colors.ink}"
    rounded: "{rounded.pill}"
  button-primary-pressed:
    backgroundColor: "{colors.text-body}"
    textColor: "{colors.ink}"
  button-secondary:
    backgroundColor: "{colors.glass-secondary}"
    textColor: "{colors.text}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 22dp"
    height: "44dp"
  button-secondary-focus:
    backgroundColor: "{colors.text}"
    textColor: "{colors.ink}"
  button-disabled:
    backgroundColor: "{colors.glass-disabled}"
    textColor: "{colors.text-faint}"
  button-round:
    backgroundColor: "{colors.glass-secondary}"
    textColor: "{colors.text}"
    rounded: "{rounded.pill}"
    size: "44dp"
    padding: "12dp"
  rail-pill:
    backgroundColor: "{colors.text}"
    textColor: "{colors.ink}"
    typography: "{typography.nav}"
    rounded: "{rounded.pill}"
    height: "44dp"
    width: "200dp"
  poster-card:
    textColor: "{colors.text}"
    typography: "{typography.card-title}"
    rounded: "{rounded.artwork}"
    width: "108dp"
    height: "162dp"
  settings-panel:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.panel}"
    padding: "12dp"
    width: "320dp"
  settings-row-focus:
    backgroundColor: "{colors.text}"
    textColor: "{colors.ink}"
    rounded: "{rounded.row}"
    height: "48dp"
    padding: "0 16dp"
  settings-chip:
    backgroundColor: "{colors.surface-high}"
    textColor: "{colors.text}"
    rounded: "{rounded.chip}"
    height: "28dp"
  skip-pill:
    backgroundColor: "{colors.notice-glass}"
    textColor: "{colors.text}"
    rounded: "{rounded.skip-pill}"
    height: "48dp"
  skip-pill-focus:
    backgroundColor: "{colors.text}"
    textColor: "{colors.ink}"
  hero-chip:
    backgroundColor: "{colors.hero-chip}"
    textColor: "{colors.text}"
    typography: "{typography.meta}"
  update-notice:
    backgroundColor: "{colors.notice-glass}"
    textColor: "{colors.text}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 22dp 0 16dp"
    height: "44dp"
  update-notice-focus:
    backgroundColor: "{colors.text}"
    textColor: "{colors.ink}"
  rail-update-dot:
    backgroundColor: "{colors.brand-red}"
    size: "6dp"
---

# Design System: 30namaTV

## Overview

**Creative North Star: "The Living-Room Marquee"**

30namaTV is Netflix's TV app played straight on Android's own Leanback toolkit, finished to the Apple TV app's standard of motion and type. The screen is a dark room: an ink ground, full-bleed artwork that changes with whatever the remote rests on, and poster rows running beneath it. Everything chrome-like is quiet so the art leads. One colour language does the work of focus from three metres away: white. Whatever the remote is on turns solid white (or is ringed in white, if it is artwork), and nothing else on the screen is ever solid white.

The system is cheap to draw by rule, not by accident. It runs on 2 GB TVs, so depth comes from tone and scrims, never shadows or blur; only alpha and transforms animate; artwork crossfades rather than video plays. Persian is the default and right-to-left, English mirrors it, and type is chosen per script: Peyda for Persian names and headings, Yekan Bakh for everything read, Inter for English names and Latin runs. Persian is never letter-spaced.

Motion is the second craft bar. One set of curves (a strong ease-out that moves at once and settles, a longer emphasized ease-out for big travel) and one duration table govern the whole app, exits run quicker than entrances, and Reduce Motion turns all travel into 120 ms fades rather than cuts.

**Key Characteristics:**
- Ink ground (#141414) with full-bleed art, side and bottom ink scrims holding the text.
- White is focus: a white fill with ink text on controls, a 3dp white ring on artwork.
- One control shape, the 44dp pill in a 48dp target; artwork keeps 6dp corners so a control never reads as art.
- 30nama red is a mark, never a field.
- Flat: tonal steps and scrims, no shadows, no blur.
- Peyda / Yekan Bakh / Inter, each fixed to its script and role.

## Colors

A near-black neutral ladder, white glass controls, and three borrowed marks (30nama red, "free" green, IMDb yellow), each confined to one meaning.

### Primary
- **30nama Red** (brand-red): the one accent. Watched progress (playback and card progress bars), the rail's current-page mark (a 3 × 16dp bar), 30nama's own marks, sign-in steps, the status light, the update dot (a dot, never a bar: on home's update notice and on the rail's Settings icon, and the same 6dp dot before «قسمت تازه» on a continue poster), the done check and the player's loading indicator. As a fill it carries white text (4.8:1). Never a large area, never body text.

### Secondary
- **Free Green** (free-green): the «رایگان» badge on artwork and the Free plan's mark. Ink on it. It means "free" and nothing else.
- **IMDb Yellow** (imdb-yellow): IMDb's own mark beside a score, and only there: a filled badge on the billboard, title page and player, the bare word in poster captions.

### Tertiary
- **Lifted Alarm Red** (settings-danger): Settings' sign out and a lapsing plan on the dark panel, where brand red falls to 3.4:1 (this reaches 5.4:1). On the white focus card the brand red itself carries it.
- **Plan Gold / Plan Blue** (tier-golden, tier-blue): 30nama names its plans by colour; used only for the plan's mark in Settings.

### Neutral
- **Theatre Ink** (ink): the window ground, the scrims' solid end, and text on any white focus.
- **Deep Ink** (ink-deep): Leanback's brand band, the darkest step.
- **Surface / Surface High** (surface, surface-high): the raised panel (Settings, dialogs) and a step above it (chips, loading cards, the idle voice orb).
- **Hairline** (hairline): rules between grouped rows.
- **Idle Grey** (button-idle): a control's idle tone where it sits on plain ink; the pulsed voice orb.
- **White** (text): titles, and every focused fill and ring.
- **Body / Dim / Faint** (text-body, text-dim, text-faint): reading text, meta lines and unfocused row titles, then rail icons at rest and disabled labels. Meta Origin (meta-origin) is the one step between dim and faint, kept for the title page's origin line because it holds 4.5:1 over white art under the side scrim where faint does not.
- **White Glass** (glass-primary 25%, glass-secondary 14%, glass-disabled 7%, hero-chip 20%): control fills at rest, white laid over whatever is behind so a button over art takes its tone instead of sitting on it as a grey slab.
- **Notice Glass** (notice-glass, ink at 85%): the other way round, for a control over art with no scrim behind it (the player's skip pill, home's update notice): white glass would vanish over bright art there, dark glass keeps white labels above 10:1.
- **Scrims** (ink at 100% / 70% / 0%): the billboard's side gradient (ink, scrim-mid, clear, clear, from the start edge) and bottom gradient (ink, ink, clear, clear); the open rail dims the page with ink at 45%.

### Named Rules
**The White Is Focus Rule.** Solid white belongs only to whatever the remote is on. Controls at rest are glass, never solid white, so two white buttons can never leave the viewer guessing which one has the remote.

**The Mark Not Field Rule.** 30nama red, green and IMDb yellow each mean one thing and appear as marks: bars, badges, dots. None of them fills a surface or colours body text.

**The Names Not Hex Rule.** Screens take colours by name from colors*.xml; Leanback's own colour names are overridden so stock widgets join the palette.

## Typography

**Display Font:** Peyda (Medium, SemiBold, Bold) for Persian names, headings and the rail; Inter Bold for an English name.
**Body Font:** Yekan Bakh, Fa-En cut (Regular, Bold), with Latin from its own Lato-derived letters; Inter for English runs.
**Label/Mono Font:** none distinct; labels use Yekan Bakh. The running clock turns on `tnum`.

**Character:** Apple TV's discipline: a big bold title, a quiet meta line, a readable body, and nothing in between competing. Peyda gives Persian names a display voice; Yekan Bakh stays calm and legible at three metres.

### Hierarchy
- **Display** (Bold, 38sp, shrinks to fit): the billboard's name. Inter with -0.01em tracking for an English name, Peyda for a Persian one.
- **Numeral** (Peyda Bold, 40sp): an episode's number over its art.
- **Headline** (Peyda Bold, 30sp): a page's own heading on sign-in and Settings.
- **Page Title** (Peyda Bold, 28sp): Leanback's browse title (grid and catalogue pages).
- **Dialog Title** (Peyda Bold, 21sp): sheets and dialogs.
- **Title** (Peyda Bold, 20sp, 1.15 leading): row titles; dim at rest, white when their row has focus.
- **Subtitle** (Peyda SemiBold, 20sp, text-body): the Persian name under an English display name.
- **Tile** (Peyda Bold, 18sp): a label set on artwork at a tile's foot, over a 85% black gradient.
- **Synopsis** (18sp, 1.65 leading in Persian, 1.45 in English): the billboard's two or three lines over art.
- **Body** (Yekan Bakh, 17sp, text-body): reading text; the title page keeps its lines to 500dp.
- **Nav** (Peyda SemiBold, 17sp): the rail's labels.
- **Meta / Label / Caption** (Yekan Bakh, 16sp): the dotted fact line (1.5 em leading centred on the digits), button labels, captions. 16sp is the floor for anything read.
- **Card Title / Card Meta** (Inter Medium 15sp / Yekan Bakh 14sp): a poster's name and its IMDb score and year beneath.

### Named Rules
**The Script Decides Rule.** Face follows script, not role alone: Persian names in Peyda, English names in Inter, reading text in Yekan Bakh. Persian digits in Persian mode; each part of a dotted meta line is fenced with direction marks so Latin and Persian keep their order.

**The No Tracking On Persian Rule.** Never letter-space Persian: it pulls the joins apart. Its narrow word spaces are widened at layout instead (TextSpacing.kt).

**The Sixteen Floor Rule.** Nothing read is set under 16sp, except a poster's own caption (15/14sp) under its artwork.

## Layout

A 960 × 540dp canvas read from a sofa. A 64dp navigation rail sits at the start edge (right in Persian, left in English) on solid ink with a feather; content starts at 96dp from that edge, and the billboard's text, row titles and the first poster all align there. Both browse paddings stay equal at 96dp because Leanback resolves row padding before RTL. Persian sets about 10% larger than Latin and needs more leading, so sizes run above Leanback's defaults.

Home: the billboard holds the top 252dp; rows start beneath with a 36dp title band. The top end corner stays clear for the update notice, its centre level with the rail's brand mark (60dp down) and 48dp in from the safe edge. Poster rows use 108 × 162dp posters (124 × 186dp elsewhere) with 16dp between neighbours (room for the 1.08 focus lift) and 20dp between rows; unfocused rows are never dimmed. While the promoted hero is up, the rows drop 172dp so posters peek in under it; its pill sits 142dp from the foot and the words stack upward from 212dp. The title page keeps its reading measure to 500dp and parks its rows at 431dp, raising them to 186dp when the remote moves down. Settings runs three columns on a 4dp scale between the content start and a 48dp safe edge: sections 176dp, a 24dp gap, a 320dp panel, a 40dp gap, the explanation. A section taller than the screen scrolls its panel with the remote, the panel fading over 48dp at an edge with more past it.

Controls: 48dp is the remote's target everywhere (buttons, settings rows, the skip pill), 12dp between buttons in a row, 8dp between an icon and its label.

## Elevation & Depth

Flat by construction. There are no shadows and no blur anywhere: depth is tone (ink, then surface #1F1F1F, then surface-high #2E2E2E), ink scrims over artwork, and scale. A focused card comes forward 8% and a focused button 5%; nothing else lifts. Raised surfaces (the Settings panel, dialogs) are one tonal step over ink with no edge or shadow.

### Named Rules
**The Tone Not Shadow Rule.** A surface rises by one tonal step, never by a shadow. On a 2 GB TV, a shadow is a cost with no reader.

**The Scrim Holds The Words Rule.** Text over art sits on an ink gradient from the start edge and from the foot, strong enough that labels keep 4.5:1 even over a white frame of art.

## Shapes

Two families and one rule between them. Artwork (posters, backdrops in cards, episode tiles, cast discs aside) has softly squared 6dp corners. Every control is a pill: buttons 44dp tall with 22dp radius, the rail's focus pill, the skip pill (48dp, 24dp radius), value chips (28dp, 14dp radius), the hero's page dots (6dp dots, a 18 × 6dp current-page pill). Containers sit between: the Settings panel at 20dp and its rows at 12dp, so a lifted row's curve runs parallel to the panel's, 8dp inside it. Meta-line chips round their corners by a fifth of their height. Icons are authored outline strokes, 1.8 on a 24 grid with round caps and joins.

**The Control Is Not Art Rule.** A control never wears artwork's 6dp corner; artwork never wears a pill.

## Components

### Buttons
White glass that turns solid white under the remote: quiet and sure under a 38sp title.
- **Shape:** full pill (22dp radius), 44dp tall, inset 2dp inside a 48dp view so the focused lift stays in bounds; minimum width 88dp.
- **Primary:** Play / Resume and the hero's «اطلاعات بیشتر» / "More info": glass-primary (25% white) with a 20dp leading icon, 16dp padding on the icon side and 22dp on the label side.
- **Secondary:** label only (Check now, Guest, Retry, dialogs): glass-secondary (14% white), 22dp padding.
- **Round:** an icon-only toggle (favourite, watchlist, notify), the pill closed to a 44dp circle with the 20dp icon centred.
- **Filter:** the catalogue grids' genre, country, IMDb and decade (Movies, Series, Anime), on their own line under the page name so they never crowd it, from the reading start. The label, then a 16dp chevron 4dp after it (16dp padding on that side, 22dp on the label's), at most 168dp wide. Unset, it names its kind on secondary glass («ژانر», "IMDb"); set, it names the choice («درام», «IMDb ۷+», «دههٔ ۲۰۱۰») on primary glass (activated, never a swapped drawable). A round × after them clears all, there only while a filter is set. Each opens choose's end-edge panel, its first row taking that filter off; a long list scrolls, fading over 48dp at an edge.
- **Unavailable:** on the page of a title 30nama doesn't stream, Play gives way to what stands in its place, «به‌زودی» / "Coming soon" or «پخش آنلاین ندارد» / "Not available to stream": no icon, secondary glass and a dim label at rest, a step below the action it replaces. It keeps the remote's starting place and white focus, so the page reads the same; OK says why in a notice.
- **Focus:** fill fades to solid white in 150ms (100ms out), label and icon turn ink, and the button scales to 1.05 over 180ms (ease-out), settling over 120ms. **Pressed:** text-body, back to scale 1. **Disabled:** 7% white, faint label.

### Chips
- **Meta chip:** the hero's category («سریال») on 20% white, corners a fifth of its height; a fact, not a control.
- **Scores (title page):** IMDb's badge leads and is the line's only colour; the rest are quiet, in the line's grey: MAL or MDL as words, then the critics' as their sites' glyphs before the number, Rotten Tomatoes' tomato under its star of leaves («۹۴٪») and Metacritic's ringed M («۷۵»), stroked as the globe is (1.8 on the 24 grid, drawn 1 em, on the digits' middle). The critics' give way when the line would otherwise lose its end (the length, the Persian subtitles chip) to the ellipsis. 30nama's own members' score is never shown.
- **Value chip (Settings):** a 28dp pill, surface-high at rest, ink at 12% on the focused white row.

### Cards / Containers
- **Poster card:** artwork with its caption always shown beneath (title, then IMDb score and year); no plate, no background. 6dp corners. The caption's score mark is bare: the word «IMDb» alone in IMDb yellow at the badge's word size (0.8em), no box, the score 0.25em after it and the year 0.6em after that, in both languages. A filled badge under every card of a row read louder than the titles above it; the box stays for the billboard, the title page and the player, where one mark leads the line. MAL and MDL marks, whose blues are too dark to read bare on ink, are lifted 45% toward white. A Continue Watching poster's caption is its time left instead («۸ دقیقه مانده»), and while its series has an episode out that the viewer has not gone back to, «● قسمت تازه» / "● New episode": the rail's 6dp red update dot on the digits' middle, 0.35em before the words in the caption's own grey, so the news is a mark and the line stays as quiet as the one it replaces. Focus: a 3dp white ring drawn inside the art's edge, fading in over 150ms, plus a 1.08 lift. Art fades in over 240ms only when it arrives off network or disk.
- **Panel / sheet:** surface (#1F1F1F) at 20dp radius, 12dp inner pad, no border, no shadow.
- **Note (in content's place):** when what a page lists fails or comes back empty, its place says so instead of going without it: a title, why in Body text-dim (520dp at most), and one secondary pill 20dp below («تلاش دوباره» / "Retry", or Back). A person's page sets it where its rows would be; a series' title page makes it the row where the first season would be, the row title «قسمت‌ها بارگیری نشد» / "Couldn't load the episodes" over why and Retry. Down from Play lands on it, the pill wearing the row's focus; OK reads «در حال بارگذاری» while it asks, and the seasons take its place with the remote on Play's episode. A catalogue grid whose first page fails says «فهرست عنوان‌ها بارگیری نشد» / "Couldn't load the titles" (an archive with nothing in it, «هنوز عنوانی اینجا نیست» / "Nothing here yet") centred in the grid's place, Retry on the remote bringing the skeleton back; home with nothing kept from the last run says «صفحهٔ اصلی بارگیری نشد» / "Couldn't load home" in its rows' place (no server at all, the same note), the rail a press away. Search keeps its grid and says it in the heading instead: «جست‌وجو انجام نشد» / "Couldn't search". Coming back to the page asks again on its own. A page whose later pages fail keeps what it shows and says why in the notice. A screen that closes as it fails (the player with nothing to play) leaves its words to the page beneath, which says them in its notice.

### Inputs / Fields
- **Search:** Leanback's search bar on surface-high, never dimmed while typing; hint in text-faint; the voice orb a dark surface-high disc at rest.
- **Toggle:** an 18dp knob 3dp inside a 40 × 24dp track; on the focused white row the off track steps down to #BDBDBD.

### Navigation
- **Rail:** a 64dp column of stroked icons at the start edge under the brand mark. At rest icons are text-faint, the current page white with a 3 × 16dp red mark at the edge. While a downloaded update waits, Settings wears a 6dp red dot on its icon's top end corner, cut out of the glyph by a 1.5dp ring of what is behind it (ink on the rail, white on the pill), and then opens on its App update row: the bar says where you are, the dot says something waits there. Opening: a 320dp panel slides 24dp in from the edge as ink at 45% dims the page (240ms), labels follow 20ms apart, and one white pill (200dp × 44dp) glides item to item on a 52dp pitch over 200ms. Focused item: ink icon and label on the white pill.
- **Settings rows:** transparent at rest, a white 12dp-cornered card under the remote, lifted 4dp a side.

### Billboard and Promoted Hero (signature)
The full-bleed art behind home. Browsing rows, the words change at once (out 120ms, in 260ms with a 40ms stagger, emphasized ease) and the art waits for the remote to rest 350ms, then crossfades in place over 700ms. The promoted hero pages through featured titles: Left/Right turn pages both ways in reading order and wrap, OK opens the title page, the remote's Play key plays, and pages auto-advance every 8s. A page turn glides the words 32dp and drifts the art 16dp (the art bleeds 16dp past each side so the drift never bares an edge) while the old art crossfades out on a second layer, starting with the words. A page lands once paging has rested 220ms after the words leave (150ms), so a held key or a quick run of presses turns once, to where it stops; while the rail is open over the hero, its pill and dots step out of sight. Beside the one pill sits the dot track: dim dots at 35% white and the current page's white pill gliding over them (300ms). Under Reduce Motion every turn and swap is a 120ms fade with no travel.

**Title logo (with a TMDB key).** The English name gives way to the title's TMDB logo (in the interface's language, else English, else wordless), on the billboard and the title page alike; the Persian name stays beneath it in Peyda. The logo stands on the name's foot at the start edge, fit to its box and 12dp above it (62dp over a one-line name, 320dp wide at most, its top inside the title page's 27dp safe edge), so nothing moves when it arrives. A logo already in memory comes in with the words; a late one fades in over them in 240ms (120ms under Reduce Motion). One that is missing, fails, or has less than a tenth of its lettering at 3:1 over the ink (black lettering made for light posters) leaves the words. Logos are looked up with the art beside the focused title and the hero's next pages, and kept, so paging never asks twice.

### Player Controls
The billboard's grammar over video. At the start edge, the title's poster (120dp tall, artwork's 6dp corners) with its names beside it: the English name in Inter Bold, the Persian name in Peyda SemiBold, then the billboard's fact line (Meta.playerMeta): what plays in white («فصل ۲، قسمت ۱۰»), IMDb's badge and score, the year; no age rating. The block's foot sits level with the poster's, and nothing else sits above the bar. Time facts live on the timeline, not under the title: elapsed under the bar's left end, the length under its right end with «پایان ساعت ۲۲:۴۵» / "Ends at 10:45 PM" beside it, the same clock style. Under the clock every button shares one row, YouTube's, left to right in either language as the bar is: the play cluster from the bar's left end, the panels' buttons (subtitles, quality, speed, sleep timer, episodes) to its right end. The sleep timer (a crescent, the speed gauge's stroke) pauses at the end of this episode or film, or after 15, 30, 45 or 60 minutes, a running one saying on its row how long it has left; it then says so in a notice. The screen stays lit only while video plays, so a paused film lets the screensaver come. The remote opens the controls on play/pause; Up reaches the bar. The seek bar's dot is 20dp while the bar has the remote (YouTube's, over three times the 6dp bar), the bar's thinner rest state otherwise.

### Update Notice (home)
A downloaded, verified release said where every viewer passes, not only in Settings: one pill in home's top end corner, «● نسخهٔ ۱.۵.۰ آماده است | نصب» / "● Version 1.5.0 is ready | Install". Notice glass with a 1dp 20% white hairline at rest (a white rim would read as focus among home's controls); an 8dp red status dot, the message in the label face, a 1 × 20dp hairline, then the action in Yekan Bakh Bold (Inter Bold in English); 16dp on the dot side, 22dp on the action side, 10dp after the dot and 14dp either side of the hairline. It never takes the remote: Up from the hero's button (or from the first row with no hero) reaches it, and only Down leaves. Focus is the button's: solid white, ink words, a 12% ink hairline so it keeps its edge over white art, the 1.05 lift. OK installs, Back puts it away for that version (remembered; a newer one shows again, and the rail's dot stays until it is installed). It lives in home's own layout, so it is never over the player, and changes only while home is in front: it comes down 12dp as it fades in, over 420ms on the emphasized curve, 800ms after home's rows have risen, and leaves up 8dp in 150ms. Under Reduce Motion both are fades.

### Skip / Next Pill (player)
Dark glass (ink at 85%) with a 2dp white rim over video, solid white under the remote. A countdown fill wipes from the start edge as the next episode or auto-skip comes due: light on the glass, a shade of ink on the white. A title picked up past its first minute offers «پخش از ابتدا» / "Start from the beginning" on the same pill for 10 seconds of playing time, its fill draining as the offer runs out; auto-skip never takes it. A file with no credits mark offers the next episode 25 seconds before its end, the same countdown and Back as in credits.

## Do's and Don'ts

### Do:
- **Do** make focus white: a white fill with ink text on controls and rail items, a 3dp white ring on artwork.
- **Do** build every control as the 44dp pill in a 48dp target, primary at 25% white glass, secondary at 14%.
- **Do** keep artwork at 6dp corners with its title, IMDb score and year always shown beneath.
- **Do** start content 96dp from the rail's edge and keep both browse paddings equal.
- **Do** take motion from Motion.kt: ease-out (0.23, 1, 0.32, 1) for enter and leave, emphasized (0.16, 1, 0.3, 1) for big travel, exits shorter than entrances, and 120ms fades under Reduce Motion.
- **Do** set Persian names in Peyda, English names in Inter, reading text in Yekan Bakh at 16sp or more.
- **Do** take colours by name from colors*.xml.

### Don't:
- **Don't** fill a resting control with solid white; white is focus.
- **Don't** use 30nama red, green or IMDb yellow as a surface or for body text.
- **Don't** reach for the system's grey toast or a stock dialog to tell the viewer something; the app's own surfaces do it, and a standing notice never takes the remote.
- **Don't** add shadows, blur, animated backgrounds or video beyond the launch motion.
- **Don't** letter-space Persian.
- **Don't** dim unfocused rows; artwork keeps its own colour.
- **Don't** give a control artwork's 6dp corner, or square it to 4dp.
- **Don't** animate anything but alpha and transforms.
