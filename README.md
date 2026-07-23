# UG Viewer

An Android app for searching, viewing, and saving guitar tabs and chords from Ultimate Guitar.

<p align="center">
  <img src="Les_Paul_Guitar.png" width="120" alt="UG Viewer Icon"/>
</p>

## Features

- **Search** for songs and artists with autocomplete
- **Voice search** support
- **Chord charts** rendered as PDF with color-coded formatting
- **Tab viewer** with adjustable font size and pinch-to-zoom
- **Save PDF** chord sheets directly to your Downloads folder
- Dark theme with custom color palette

## Download

Grab the latest APK from [Releases](https://github.com/Bsugar9/UG_Viewer/releases).

## Requirements

- Android 8.0 (API 26) or higher
- Internet permission (automatically requested)

## Building from Source

1. Clone the repository:
   ```
   git clone https://github.com/Bsugar9/UG_Viewer.git
   ```

2. Open the project in Android Studio.

3. Sync Gradle and run on a device or emulator.

## Tech Stack

- Kotlin
- Jetpack Compose + Material 3
- OkHttp for API calls
- Gson for JSON parsing
- Android PdfDocument / PdfRenderer for PDF generation and preview

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Fetch tabs from Ultimate Guitar API |
| `RECORD_AUDIO` | Voice search |

## License

This project is for personal/educational use. Tab content is sourced from [Ultimate Guitar](https://www.ultimate-guitar.com/).
