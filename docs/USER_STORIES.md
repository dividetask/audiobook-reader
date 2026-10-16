# User Stories

User stories organized by feature area. Each story follows the format:
**As a [type of user], I want [goal] so that [reason].**

Acceptance criteria are listed below each story. Fill in details and add/remove stories as needed.

---

## Library Management

### US-LIB-001: Import Local Audiobooks
**As a** user, **I want** to import audiobook files from my device storage **so that** I can play them in the app.

**Acceptance Criteria:**
- [ ] [e.g., User can select a folder to scan for audiobook files]
- [ ] [e.g., App detects M4B, MP3, M4A, and ___ formats]
- [ ] [e.g., Multi-file books in a folder are grouped as one book]
- [ ] [e.g., Metadata (title, author, cover) is extracted from file tags]
- [ ] [e.g., Imported books appear in the library immediately]

### US-LIB-002: Browse Library
**As a** user, **I want** to browse my audiobook library **so that** I can find a book to listen to.

**Acceptance Criteria:**
- [ ] [e.g., Library displays as a grid of cover art by default]
- [ ] [e.g., User can switch between grid and list view]
- [ ] [e.g., Each book shows title, author, and progress]
- [ ] [e.g., "Continue Listening" section shows most recent book]

### US-LIB-003: Sort and Filter Library
**As a** user, **I want** to sort and filter my library **so that** I can find books quickly.

**Acceptance Criteria:**
- [ ] [e.g., Sort by: title, author, date added, progress, duration]
- [ ] [e.g., Filter by: in progress, not started, finished]
- [ ] [e.g., Sort preference persists between sessions]

### US-LIB-004: Search Library
**As a** user, **I want** to search my library **so that** I can find a specific book.

**Acceptance Criteria:**
- [ ] [e.g., Search by title, author, narrator, series]
- [ ] [e.g., Results update as user types]
- [ ] [e.g., Tapping a result opens Book Detail]

### US-LIB-005: Organize into Collections
**As a** user, **I want** to organize books into collections **so that** I can group books by topic, mood, or status.

**Acceptance Criteria:**
- [ ] [e.g., User can create, rename, and delete collections]
- [ ] [e.g., A book can belong to multiple collections]
- [ ] [e.g., Default collections exist: Favorites, Want to Listen, Finished]
- [ ] [e.g., User can reorder books within a collection]

### US-LIB-006: [___Story Title___]
**As a** [user type], **I want** [goal] **so that** [reason].

**Acceptance Criteria:**
- [ ] [Criterion]

---

## Playback

### US-PLAY-001: Basic Playback
**As a** user, **I want** to play an audiobook **so that** I can listen to it.

**Acceptance Criteria:**
- [ ] [e.g., Tapping Play starts playback from saved position (or beginning if new)]
- [ ] [e.g., Playback continues when app is in background]
- [ ] [e.g., Playback continues when screen is locked]
- [ ] [e.g., Position is saved on pause, stop, or app close]

### US-PLAY-002: Playback Controls
**As a** user, **I want** play/pause, skip forward, and skip back controls **so that** I can control playback easily.

**Acceptance Criteria:**
- [ ] [e.g., Play/Pause toggles playback]
- [ ] [e.g., Skip forward jumps ahead by ___ seconds (configurable)]
- [ ] [e.g., Skip back jumps back by ___ seconds (configurable)]
- [ ] [e.g., Controls are accessible on Now Playing screen, mini player, lock screen, and notification]

### US-PLAY-003: Chapter Navigation
**As a** user, **I want** to navigate between chapters **so that** I can jump to a specific part of the book.

**Acceptance Criteria:**
- [ ] [e.g., Chapter list is accessible from Now Playing screen]
- [ ] [e.g., Current chapter is highlighted]
- [ ] [e.g., Tapping a chapter starts playback from its beginning]
- [ ] [e.g., Previous/Next chapter buttons are available]

### US-PLAY-004: Variable Speed Playback
**As a** user, **I want** to change playback speed **so that** I can listen faster or slower.

**Acceptance Criteria:**
- [ ] [e.g., Speed options: 0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 1.75x, 2.0x, 2.5x, 3.0x]
- [ ] [e.g., Speed persists per book / globally — specify which]
- [ ] [e.g., Speed change takes effect immediately without interruption]
- [ ] [e.g., Pitch correction is applied (no chipmunk effect)]

### US-PLAY-005: Sleep Timer
**As a** user, **I want** to set a sleep timer **so that** playback stops automatically when I fall asleep.

**Acceptance Criteria:**
- [ ] [e.g., Timer options: 5m, 10m, 15m, 30m, 45m, 60m, end of chapter, custom]
- [ ] [e.g., Countdown is visible on Now Playing screen]
- [ ] [e.g., Audio fades out over ___ seconds before pausing]
- [ ] [e.g., Shake to extend timer by ___ minutes]
- [ ] [e.g., Timer survives app backgrounding / screen lock]

### US-PLAY-006: Bookmarks
**As a** user, **I want** to bookmark positions in an audiobook **so that** I can return to important moments.

**Acceptance Criteria:**
- [ ] [e.g., User can create a bookmark at current position]
- [ ] [e.g., Bookmarks can have optional labels/notes]
- [ ] [e.g., Bookmarks are listed on Book Detail screen]
- [ ] [e.g., Tapping a bookmark jumps to that position]
- [ ] [e.g., Bookmarks can be deleted]

### US-PLAY-007: Seek / Scrub
**As a** user, **I want** to drag the progress bar **so that** I can jump to a specific point.

**Acceptance Criteria:**
- [ ] [e.g., Dragging the seek bar scrubs to that position]
- [ ] [e.g., Elapsed and remaining time update in real time while dragging]
- [ ] [e.g., Seek bar represents current chapter / entire book — specify which]

### US-PLAY-008: [___Story Title___]
**As a** [user type], **I want** [goal] **so that** [reason].

**Acceptance Criteria:**
- [ ] [Criterion]

---

## Lock Screen & Notifications

### US-LOCK-001: Lock Screen Playback Controls
**As a** user, **I want** to control playback from my lock screen **so that** I don't have to unlock my phone.

**Acceptance Criteria:**
- [ ] [e.g., Play/Pause visible on lock screen]
- [ ] [e.g., Skip forward/back visible on lock screen]
- [ ] [e.g., Cover art and book info displayed]
- [ ] [e.g., Seek bar available (if platform supports)]

### US-LOCK-002: Notification Controls
**As a** user, **I want** playback controls in my notification shade **so that** I can quickly control playback.

**Acceptance Criteria:**
- [ ] [e.g., Notification appears when playback starts]
- [ ] [e.g., Collapsed: cover art, title, play/pause, skip forward]
- [ ] [e.g., Expanded: adds skip back, next/previous chapter]
- [ ] [e.g., Notification is non-dismissible during active playback]
- [ ] [e.g., Notification becomes dismissible after ___ minutes of pause]

### US-LOCK-003: Bluetooth/Headphone Controls
**As a** user, **I want** to control playback with my Bluetooth headphones **so that** I can listen hands-free.

**Acceptance Criteria:**
- [ ] [e.g., Single press: play/pause]
- [ ] [e.g., Double press: skip forward]
- [ ] [e.g., Triple press: skip back]
- [ ] [e.g., Disconnect: auto-pause]
- [ ] [e.g., Reconnect: auto-resume (configurable)]

### US-LOCK-004: [___Story Title___]
**As a** [user type], **I want** [goal] **so that** [reason].

**Acceptance Criteria:**
- [ ] [Criterion]

---

## Audible Integration

### US-AUD-001: Connect Audible Account
**As a** user, **I want** to connect my Audible account **so that** I can access my purchased audiobooks.

**Acceptance Criteria:**
- [ ] [e.g., User can authenticate with Audible credentials]
- [ ] [e.g., 2FA is supported]
- [ ] [e.g., Credentials are stored securely]
- [ ] [e.g., User can disconnect/sign out at any time]

### US-AUD-002: Browse Audible Library
**As a** user, **I want** to browse my Audible library in the app **so that** I can see what books I own.

**Acceptance Criteria:**
- [ ] [e.g., All purchased books are listed]
- [ ] [e.g., Each book shows cover, title, author, duration]
- [ ] [e.g., Import status is shown (not downloaded, downloaded, error)]

### US-AUD-003: Download and Back Up Books
**As a** user, **I want** to download my Audible books to my device **so that** I have a personal backup.

**Acceptance Criteria:**
- [ ] [e.g., User can select individual books or batch-select]
- [ ] [e.g., Download progress is shown per book]
- [ ] [e.g., Downloads continue in background]
- [ ] [e.g., Failed downloads can be retried]
- [ ] [e.g., Downloaded books are playable in the app]

### US-AUD-004: Convert Audible Format
**As a** user, **I want** to convert Audible books to standard formats **so that** I truly own my audiobooks.

**Acceptance Criteria:**
- [ ] [e.g., AAX can be converted to M4B with chapters preserved]
- [ ] [e.g., AAX can be converted to MP3]
- [ ] [e.g., Conversion happens on-device]
- [ ] [e.g., Original quality is preserved]
- [ ] [e.g., Cover art and metadata are preserved]

### US-AUD-005: [___Story Title___]
**As a** [user type], **I want** [goal] **so that** [reason].

**Acceptance Criteria:**
- [ ] [Criterion]

---

## Settings & Preferences

### US-SET-001: Configure Skip Intervals
**As a** user, **I want** to customize skip forward/back durations **so that** they match my listening preference.

**Acceptance Criteria:**
- [ ] [e.g., Skip forward configurable: 10s, 15s, 30s, 45s, 60s, 90s]
- [ ] [e.g., Skip back configurable: 10s, 15s, 30s, 45s, 60s, 90s]
- [ ] [e.g., Defaults: forward 30s, back 15s]

### US-SET-002: Theme Selection
**As a** user, **I want** to choose light or dark theme **so that** the app matches my preference.

**Acceptance Criteria:**
- [ ] [e.g., Options: Light, Dark, Follow System]
- [ ] [e.g., Theme change applies immediately]
- [ ] [e.g., Default: Follow System]

### US-SET-003: [___Story Title___]
**As a** [user type], **I want** [goal] **so that** [reason].

**Acceptance Criteria:**
- [ ] [Criterion]

---

## Statistics

### US-STAT-001: View Listening Statistics
**As a** user, **I want** to see my listening statistics **so that** I can track my habits.

**Acceptance Criteria:**
- [ ] [e.g., Shows total time listened (all time, this month, this week)]
- [ ] [e.g., Shows books finished count]
- [ ] [e.g., Shows daily/weekly listening chart]
- [ ] [e.g., Shows current listening streak]

### US-STAT-002: [___Story Title___]
**As a** [user type], **I want** [goal] **so that** [reason].

**Acceptance Criteria:**
- [ ] [Criterion]

---

*Copy the blank story template for any additional stories:*

```
### US-[AREA]-[NUM]: [___Story Title___]
**As a** [user type], **I want** [goal] **so that** [reason].

**Acceptance Criteria:**
- [ ] [Criterion]
- [ ] [Criterion]
```
