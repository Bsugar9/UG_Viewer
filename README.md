# UG Viewer

An Android app for searching, viewing, and saving guitar tabs and chords from Ultimate Guitar.

<p align="center">
  <img src="Les_Paul_Guitar.png" width="120" alt="UG Viewer Icon"/>
</p>

## Features

- **Search** for songs and artists with autocomplete
- **Voice search** support
- **Chord charts** rendered as PDF with color-coded formatting
- **Chords aligned over lyrics** — chord names sit exactly above the word they belong to, and stay there even when long lines wrap
- **"Listen on YouTube"** — the app finds a matching video for the song automatically:
  - Tap the bar in the viewer to hear the song while you play
  - "Watch on YouTube" action right from the save-PDF confirmation
  - A `Listen on YouTube: <link>` line embedded in every saved PDF
- **Tab viewer** with adjustable font size and pinch-to-zoom
- **Save PDF** chord sheets directly to your Downloads folder (`Downloads/UG Viewer`)
- Dark theme with custom color palette

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

## Tech Stack

- Kotlin
- Jetpack Compose + Material 3
- OkHttp for API calls
- Gson for JSON parsing
- Android PdfDocument / PdfRenderer for PDF generation and preview

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Fetch tabs from Ultimate Guitar API and find matching YouTube videos |
| `RECORD_AUDIO` | Voice search |

## License

This project is for personal/educational use. Tab content is sourced from [Ultimate Guitar](https://www.ultimate-guitar.com/).
