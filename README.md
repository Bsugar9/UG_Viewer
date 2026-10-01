# UG Viewer

An Android app for searching, viewing, and saving guitar tabs and chords from Ultimate Guitar.

<p align="center">
  <img src="Les_Paul_Guitar.png" width="120" alt="UG Viewer Icon"/>
</p>

## Features

- **Search** for songs and artists with autocomplete
- **Voice search** support
- **Chord Shape Search** — build a printable booklet of chord diagrams
  - Tap a root note (a 4x3 grid of all 12 roots) or type an exact chord name
  - **Say it instead of typing it**: the mic understands "Show me a C chord", "an A minor seven", or "F sharp minor"
  - Four diagrams per row on A4, 16 per page, ready to print
  - Every page credits the [chords-db](https://github.com/tombatossals/chords-db) source (MIT)
- **Chord charts** rendered as PDF with color-coded formatting
- **Chords aligned over lyrics** — chord names sit exactly above the word they belong to, wrap together with their word (a chord is never split or left behind when a line wraps), never overlap each other, and are centred in the white space between the lyric lines above and below. The shipped default (height 0.90, gap 0.30) puts each chord where you would draw it by hand, and the Studio's chord offset nudges it up or down further when a chord wants to sit closer to its own lyric
  - A chord can never print on top of the line below it. The row is sized for where the chord is *actually* drawn, so a Studio layout that slides the chord off centre gets a taller row rather than a chord that lands on the lyric — or, on a chord-only line above a tab block, on top of the tab itself
  - Every chord prints once, in red. A chord name is removed from the lyric text before it is drawn, so the same name can never appear twice, once black from the lyric row and once red from the chord row
  - A creator's `*` is treated as a highlight rather than a wildcard: `D*`, `C*` and `G**` are recognised as D, C and G chords and coloured like any other
- **Tap any chord to hear it and see its diagram** — tapping a chord name in the PDF preview sounds the chord *and* opens a popup showing that chord's picture from the same chord-book renderer the Chord Shape Search prints, so a quick lookup never leaves the song
  - **Played in the tab's own tuning and capo**, so a drop-D song does not sound like the same song in E standard, and a capo'd chart is heard from the shape rather than the key
  - **Real plucked-string synthesis**, not samples: each string is modelled as noise for the pick recirculating through a delay line one period long, with a low-pass that darkens the note as it fades and a pick position that gives the attack its edge. A steel string through a soundbox — the body and top-block resonances are what give it its woody top end
  - **Re-strums until you press Stop.** The chord is held at a steady level for the length of each strum, then released, and the strum is played again a second later — so it repeats as separate strokes of the same chord rather than dying once or droning on
  - The button replays the last chord without going back to the sheet
- **Page formats** — Small (the default), Fit To Page, or Custom with independent chord/lyric font dials (10-42pt), re-rendered live in the preview; layouts you save in the Studio appear here too, by name
  - The Custom dials can be **collapsed with one tap** (the ⚙ button in the bar), handing their height back to the sheet for reading instead of tuning
  - A saved Studio layout is re-applied every time you open a song, so a wrap or spacing change you tuned once sticks to every sheet that follows
  - Each sheet opens at 1× zoom rather than inheriting the zoom of the last one
- **Auto-scroll the sheet** — every PDF preview carries a floating control bar in the corner (↑ − 30 dp/s + ▶) that scrolls the sheet from top to bottom on its own:
  - **↑** jumps instantly to the top of the sheet
  - **− / +** step through 16 preset paces, from a **1 dp/s crawl** (about 8 minutes for a page, for freezing on a single chord) up to 600 dp/s for skimming, with the current speed shown between them
  - It starts at **30 dp/s** — roughly 17 seconds for a page, slow enough to read a lyric line and its chords before they leave the screen. The rungs are packed tightly at the slow end (1, 2, 3, 5, 8, 12, 20, 30) where you play along, and spread out at the fast end
  - The speed is in dp rather than pixels, so a sheet scrolls at the same readable pace on a cheap phone and a tablet
  - Scrolling the sheet by hand stops the auto-scroll immediately and keeps the speed you picked, so tapping ▶ resumes at that pace
  - It stops by itself at the end of the document, and pressing ▶ again replays the sheet from the top
- **PDF Design Studio** — design how sheets look, opened from the ⚙ icon beside the version in the home header:
  - Six steppers: **chord size** and **lyric size** (10-42pt, starting at the Small preset), **offset** (slides every chord up or down inside the white space between the lyric lines, so you can pull a chord closer to its own word), **height** (lyric-only line height), **gap** (blank line between stanzas), and **wrap** (how much of the page width a line may use)
  - A red guide rule is drawn on the preview page at the column where lines wrap — drag Wrap and watch exactly where they break
  - A **zoomable preview**: use the zoom slider or pinch with two fingers (the slider follows your pinch), then pan left/right and up/down to inspect closely
  - Previews against Pink Floyd's "Pigs on the Wing" (fetched from Ultimate Guitar, with an offline stand-in chart)
  - Opens on your **last saved layout**, so tuning continues where you left off
  - **Save** prompts for a name; the layout becomes the app-wide default for every sheet generated from then on, and shows up by that name in the PDF format list on the viewer screen
  - **Reset** returns to the **Small** preset, and makes Small the default again — Small stays a preset in the format list either way, and your test song and saved layout names are kept
- **Recent Search History** — the home screen remembers your last 25 lookups under a pinned header; tap one to search Ultimate Guitar again in one tap, or clear the whole list with the trash button
- **"Listen on YouTube"** — the app finds a matching video for the song automatically:
  - Tap the bar in the viewer to hear the song while you play
  - "Watch on YouTube" action right from the save-PDF confirmation
  - A `Listen on YouTube: <link>` line embedded in every saved PDF
- **Tab viewer** with adjustable font size and pinch-to-zoom
- **Save PDF** chord sheets to your Downloads folder (`Downloads/UG Viewer`) or any folder you pick with the system folder picker — the choice sticks for next time
- Dark theme with custom color palette
- Adaptive layout for phones, tablets, and foldables

## Download

Grab the latest APK from [Releases](https://github.com/Bsugar9/UG_Viewer/releases).

## Requirements

- Android 8.0 (API 26) or higher
- Internet permission (automatically requested)

## Building from Source

The project builds on any machine with a JDK 17+ — no machine-specific configuration required.

### Android Studio

1. Clone the repository:
   ```
   git clone https://github.com/Bsugar9/UG_Viewer.git
   ```

2. Open the project in Android Studio.

3. Sync Gradle and run on a device or emulator.

### Command line

1. Clone the repository (above).

2. Point `JAVA_HOME` at a JDK 17+ install and build:
   ```
   set JAVA_HOME=path\to\jdk-17
   gradlew.bat assembleDebug
   ```

3. The debug APK lands in `app/build/outputs/apk/debug/`.

If the Android SDK isn't at the default location, set its path in `local.properties` (`sdk.dir=...`).

### Versioning

The version is fixed in `app/build.gradle.kts` (`versionNameValue` / `versionCodeValue`) and bumped by hand for each release — the current release is **2.55**. The version shows in the app header beside the title.

### Release signing

Signing secrets are not committed. To produce a properly signed release build, create `keystore.properties` in the repository root (it is git-ignored):

```properties
storePassword=<your store password>
keyPassword=<your key password>
keyAlias=ugviewer
```

Place the keystore itself at `app/release-keystore.jks` (also git-ignored). Environment variables `UGVIEWER_STORE_PASSWORD`, `UGVIEWER_KEY_PASSWORD`, and `UGVIEWER_KEY_ALIAS` work as an alternative.

Without them `assembleRelease` still succeeds but falls back to the debug key, so fresh clones can build without any secrets.

### Tests

The chord-name matching, spoken-phrase parsing, PDF column layout, the chord shape engine (barre realization, curated voicings, slash-chord handling), and the chord audio engine are covered by JVM unit tests:

```
gradlew.bat :app:testDebugUnitTest
```

The audio tests render the real waveform rather than mocking it, because the things that go wrong in synthesis are inaudible in a passing build: that a string starts loud and rings on through the strum, that the tail is released rather than cut (a hard cut into the gap before the next strum is a click), that the output neither clips nor falls silent, that the string loses its highs as it fades, that the soundbox is actually reaching the signal, and that the pitch recovers the frequency that was asked for by autocorrelation.

### Regenerating the chord data

`app/src/main/assets/guitar_chords.json` is a [chords-db](https://github.com/tombatossals/chords-db) export (MIT) holding 2,150 shapes across all 12 roots. Regenerate it with:

```
python tools/fetch_chords_db.py
```

## Tech Stack

- Kotlin
- Jetpack Compose + Material 3 (adaptive layouts for foldables/tablets)
- OkHttp for API calls
- Gson for JSON parsing
- Android PdfDocument / PdfRenderer for PDF generation and preview
- Android AudioTrack for chord playback (synthesised, no bundled samples)
- JUnit 4 for JVM unit tests

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Fetch tabs from Ultimate Guitar API and find matching YouTube videos |
| `RECORD_AUDIO` | Voice search for songs and for saying a chord name |

## License

This project is for personal/educational use. Tab content is sourced from [Ultimate Guitar](https://www.ultimate-guitar.com/). Chord shapes come from [chords-db](https://github.com/tombatossals/chords-db) (MIT).
