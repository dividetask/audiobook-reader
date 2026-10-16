# Audiobook Reader

An open-source audiobook player for Android. Import audio files from your phone and listen with full playback controls, lock screen integration, and position saving.

## Features (v0.1 MVP)

- Import audiobooks by picking files from your phone
- Library view with cover art, title, author, and progress
- Full playback: play/pause, skip forward/back 30s, seek bar
- Variable playback speed (0.5x - 2.0x)
- Lock screen and notification playback controls
- Bluetooth/headphone media button support
- Automatic position saving and resume

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Platform | Android (min SDK 26 / Android 8.0) |
| Language | Kotlin |
| UI Framework | Jetpack Compose + Material 3 |
| Audio Engine | Media3 / ExoPlayer |
| Local Database | Room |
| DI Framework | Hilt |
| Async | Coroutines + Flow |
| Image Loading | Coil |
| Build System | Gradle (Kotlin DSL) |

## Getting Started

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17+
- Android SDK 34 (installed via Android Studio SDK Manager)

### Build & Run

```bash
git clone https://github.com/dividetask/audiobook-reader.git
cd audiobook-reader

# Build debug APK
./gradlew assembleDebug

# APK output location:
# app/build/outputs/apk/debug/app-debug.apk

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Open in Android Studio

1. Open Android Studio
2. File > Open > select the `audiobook-reader` directory
3. Wait for Gradle sync to complete
4. Run on device or emulator (API 26+)

## Supported Audio Formats

| Format | Extension | Supported |
|--------|-----------|-----------|
| MPEG-4 Audio | .m4a, .m4b | Yes |
| MP3 | .mp3 | Yes |
| Ogg Vorbis | .ogg | Yes |
| FLAC | .flac | Yes |
| WAV | .wav | Yes |
| AAX (Audible) | .aax | Planned (future version) |

## Project Structure

```
app/src/main/java/com/dividetask/audiobookreader/
├── AudiobookApp.kt              # Hilt application
├── MainActivity.kt              # Single activity entry point
├── navigation/AppNavigation.kt  # Compose Navigation (library + player)
├── data/
│   ├── db/                      # Room database, entities, DAOs
│   ├── repository/              # BookRepository interface + impl
│   └── metadata/                # Audio file metadata extraction
├── player/
│   ├── PlaybackService.kt      # MediaSessionService (background playback)
│   └── PlaybackController.kt   # MediaController wrapper for UI
├── ui/
│   ├── library/                 # Library screen + ViewModel
│   ├── player/                  # Player screen + ViewModel
│   └── theme/                   # Material 3 theme
└── di/                          # Hilt modules
```

## Future Plans

- Audible library import and backup
- Sleep timer
- Bookmarks
- Collections / organization
- Multi-source support (Libro.fm, NextCloud)
- Cross-device sync (self-hosted)

See [docs/FEATURE_ROADMAP.md](docs/FEATURE_ROADMAP.md) for details.

## License

MIT License. See [LICENSE](LICENSE) for details.
