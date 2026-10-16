# Architecture

## High-Level Architecture

```
[Draw or describe the high-level architecture. Example:]

┌──────────────────────────────────────────────────┐
│                   UI Layer                        │
│  [e.g., Jetpack Compose / SwiftUI / Flutter]     │
│  Screens, Components, Navigation                 │
├──────────────────────────────────────────────────┤
│               ViewModel / State Layer             │
│  [e.g., ViewModels, BLoC, Redux, MVI]            │
│  Screen state, UI logic, event handling           │
├──────────────────────────────────────────────────┤
│               Domain / Use Case Layer             │
│  Business logic, playback orchestration           │
├──────────────────────────────────────────────────┤
│                  Data Layer                        │
│  Repositories, local DB, file system, network     │
├──────────────────────────────────────────────────┤
│               Platform / OS Services              │
│  Media session, notifications, audio focus,       │
│  foreground service, file I/O                     │
└──────────────────────────────────────────────────┘
```

## Architecture Pattern

**Pattern:** [e.g., MVVM, MVI, Clean Architecture, BLoC, Redux — describe which and why]

**Rationale:** [Why this pattern was chosen. e.g., "MVVM with Clean Architecture layers provides clear separation of concerns and testability"]

---

## Module / Package Structure

```
[Describe the project module or package layout. Example:]

app/
├── src/main/
│   ├── [package root]/
│   │   ├── ui/                    # UI layer
│   │   │   ├── library/           # Library screen
│   │   │   ├── player/            # Now Playing screen
│   │   │   ├── detail/            # Book Detail screen
│   │   │   ├── settings/          # Settings screen
│   │   │   ├── import/            # Audible Import screen
│   │   │   ├── search/            # Search screen
│   │   │   ├── collections/       # Collections screen
│   │   │   ├── stats/             # Statistics screen
│   │   │   ├── components/        # Shared UI components
│   │   │   └── navigation/        # Navigation graph
│   │   ├── domain/                # Domain / business logic
│   │   │   ├── model/             # Domain models
│   │   │   ├── usecase/           # Use cases
│   │   │   └── repository/        # Repository interfaces
│   │   ├── data/                  # Data layer
│   │   │   ├── local/             # Local database (Room/SQLite)
│   │   │   ├── file/              # File system access
│   │   │   ├── network/           # Network (Audible API)
│   │   │   └── repository/        # Repository implementations
│   │   ├── player/                # Audio playback engine
│   │   │   ├── service/           # Foreground service / media session
│   │   │   ├── notification/      # Playback notification
│   │   │   └── engine/            # Audio engine wrapper
│   │   ├── audible/               # Audible integration
│   │   │   ├── auth/              # Authentication
│   │   │   ├── api/               # Audible API client
│   │   │   └── converter/         # Format conversion (AAX → M4B)
│   │   └── di/                    # Dependency injection
│   └── res/                       # Resources (layouts, strings, drawables)
└── [test directories]

[Modify the above to match your actual planned structure]
```

---

## Key Components

### Audio Playback Engine

**Technology:** [e.g., ExoPlayer (Media3), AVFoundation, just_audio]

**Responsibilities:**
- [e.g., Audio decoding and playback]
- [e.g., Chapter-aware seeking]
- [e.g., Variable speed playback]
- [e.g., Audio focus management]
- [e.g., Gapless chapter transitions]
- [List all]

**Design Decisions:**
- [e.g., "Using Media3 ExoPlayer for broad format support and built-in MediaSession"]
- [e.g., "Playback runs in a foreground service to survive activity destruction"]
- [List key decisions]

### Media Session / Notification

**Technology:** [e.g., MediaSessionCompat, MPNowPlayingInfoCenter]

**Responsibilities:**
- [e.g., Exposes playback controls to system (lock screen, notification, Bluetooth)]
- [e.g., Handles media button intents]
- [e.g., Provides metadata to external consumers (Android Auto, car displays)]
- [List all]

### Local Database

**Technology:** [e.g., Room, SQLite, Hive, Realm]

**Entities / Tables:**

| Entity | Description | Key Fields |
|--------|-------------|------------|
| Book | [Audiobook metadata] | [id, title, author, narrator, coverPath, filePath, duration, ...] |
| Chapter | [Chapter within a book] | [id, bookId, title, startPosition, duration, ...] |
| Bookmark | [User-created bookmark] | [id, bookId, position, label, createdAt, ...] |
| PlaybackState | [Saved playback position] | [bookId, position, speed, lastPlayed, ...] |
| Collection | [User-created collection] | [id, name, createdAt, ...] |
| CollectionBook | [Many-to-many join] | [collectionId, bookId, sortOrder, ...] |
| ListeningSession | [Stats tracking] | [id, bookId, startTime, endTime, duration, ...] |
| [Other entity] | [Description] | [Fields] |

### File System Scanner

**Responsibilities:**
- [e.g., Scans configured directories for audiobook files]
- [e.g., Extracts metadata from file tags (ID3, MP4 atoms)]
- [e.g., Detects multi-file audiobooks (folder-based grouping)]
- [e.g., Extracts embedded cover art]
- [List all]

**Supported folder structures:**
```
[Describe how the scanner identifies books. Example:]

# Single file per book:
/audiobooks/BookTitle.m4b

# Multiple files per book (one folder = one book):
/audiobooks/BookTitle/
  ├── Chapter 01.mp3
  ├── Chapter 02.mp3
  └── cover.jpg

# [Other supported structures]
```

### Audible Integration Module

See [AUDIBLE_INTEGRATION.md](AUDIBLE_INTEGRATION.md) for full details.

---

## Data Flow

### Playback Flow

```
[Describe the data flow for starting playback. Example:]

User taps "Play" on Book Detail
  → ViewModel calls PlayBookUseCase(bookId)
  → UseCase retrieves book + saved position from Repository
  → UseCase sends play command to PlaybackService
  → PlaybackService initializes ExoPlayer with media source
  → PlaybackService creates MediaSession + notification
  → ExoPlayer starts playback
  → PlaybackService emits state updates via Flow/LiveData
  → ViewModel receives state → UI updates
```

### Library Import Flow

```
[Describe the flow for importing books. Example:]

User adds folder in Settings
  → LibraryScanUseCase scans folder recursively
  → For each audio file: extract metadata via [library name]
  → Group files into books (by folder or metadata)
  → Insert/update Book + Chapter entities in database
  → Emit library update event
  → Library screen observes and refreshes
```

### Audible Download Flow

```
[Describe the flow. See AUDIBLE_INTEGRATION.md for full detail.]
```

---

## State Management

**Approach:** [e.g., Unidirectional data flow with ViewModels exposing StateFlow]

**Playback state is global** because:
- [e.g., Mini player needs state on every screen]
- [e.g., Notification needs state while app is backgrounded]
- [e.g., Lock screen controls need state while screen is off]

**How global playback state is shared:**
- [e.g., PlaybackService exposes a StateFlow<PlaybackState> that any ViewModel can collect]
- [e.g., Singleton PlaybackManager accessible via DI]
- [Describe your approach]

---

## Threading / Concurrency Model

| Operation | Thread/Dispatcher |
|-----------|-------------------|
| UI rendering | [e.g., Main thread] |
| Database queries | [e.g., IO dispatcher] |
| File scanning | [e.g., IO dispatcher / background thread] |
| Audio decoding | [e.g., Managed by ExoPlayer internally] |
| Network requests | [e.g., IO dispatcher] |
| Metadata extraction | [e.g., IO dispatcher] |

---

## Error Handling Strategy

- [e.g., Playback errors: show snackbar, offer retry, log to crash reporting]
- [e.g., File not found: mark book as unavailable, show indicator in library]
- [e.g., Audible auth failure: prompt re-login, do not persist invalid tokens]
- [e.g., Database errors: ___]
- [Describe the error handling approach]

---

## Testing Strategy

| Layer | Testing Approach | Framework |
|-------|-----------------|-----------|
| UI | [e.g., Compose UI tests, widget tests] | [e.g., ComposeTestRule, flutter_test] |
| ViewModel | [e.g., Unit tests with fake repositories] | [e.g., JUnit, Turbine for Flows] |
| Use Cases | [e.g., Unit tests] | [e.g., JUnit, Mockito/MockK] |
| Repository | [e.g., Integration tests with in-memory DB] | [e.g., Room testing, sqflite] |
| Playback | [e.g., Robolectric tests / manual testing] | [e.g., Robolectric] |
| E2E | [e.g., Espresso / XCUITest / integration_test] | [Framework] |

---

## Dependencies

[List key third-party dependencies]

| Dependency | Purpose | Version |
|-----------|---------|---------|
| [e.g., Media3 ExoPlayer] | [Audio playback] | [___] |
| [e.g., Room] | [Local database] | [___] |
| [e.g., Hilt] | [Dependency injection] | [___] |
| [e.g., Coil/Glide] | [Image loading] | [___] |
| [e.g., FFmpeg] | [Audio format conversion] | [___] |
| [e.g., Retrofit/Ktor] | [HTTP client] | [___] |
| [Add more] | [Purpose] | [___] |
