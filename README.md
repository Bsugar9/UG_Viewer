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
- **Chords aligned over lyrics** — chord names sit exactly above the word they belong to, and stay there even when long lines wrap
- **Adjustable chord font size** (29-42pt) so big-print chord sheets fit the page without clipping
- **"Listen on YouTube"** — the app finds a matching video for the song automatically:
  - Tap the bar in the viewer to hear the song while you play
  - "Watch on YouTube" action right from the save-PDF confirmation
  - A `Listen on YouTube: <link>` line embedded in every saved PDF
- **Tab viewer** with adjustable font size and pinch-to-zoom
- **Save PDF** chord sheets directly to your Downloads folder (`Downloads/UG Viewer`)
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

The version is derived from the commit count, so committing a change is all it takes to bump it. `twoPointZeroCommit` in `app/build.gradle.kts` is the commit that opened the 2.x line; commits after it produce 2.01, 2.02, and so on, while `versionCode` stays the raw commit count so Android always accepts an upgrade.

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

The chord-name matching, spoken-phrase parsing, and PDF column layout are covered by JVM unit tests:

```
gradlew.bat :app:testDebugUnitTest
```

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
- JUnit 4 for JVM unit tests

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Fetch tabs from Ultimate Guitar API and find matching YouTube videos |
| `RECORD_AUDIO` | Voice search for songs and for saying a chord name |

## License

This project is for personal/educational use. Tab content is sourced from [Ultimate Guitar](https://www.ultimate-guitar.com/). Chord shapes come from [chords-db](https://github.com/tombatossals/chords-db) (MIT).
