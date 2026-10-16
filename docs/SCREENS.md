# Screens & Navigation

This document describes every screen in the app, what it displays, what actions are available, and how screens connect to each other.

## Navigation Overview

```
[Describe the top-level navigation pattern]
[e.g., Bottom tab bar with 4 tabs: Library, Discover, Player, Profile]
[e.g., Drawer navigation with sections]

Tabs / Top-Level Destinations:
1. [Tab/Section Name]
2. [Tab/Section Name]
3. [Tab/Section Name]
4. [Tab/Section Name]
```

## Navigation Flow Diagram

```
[Draw or describe the navigation graph. Example:]

Splash → Home (Library)
              ├── Book Detail → Now Playing
              ├── Collections → Collection Detail → Book Detail
              ├── [Screen] → [Screen]
              └── [Screen] → [Screen]
         Search
              └── Search Results → Book Detail
         Now Playing (Mini Player always visible)
              └── Full Player
         Settings
              ├── [Sub-screen]
              ├── [Sub-screen]
              └── [Sub-screen]
```

---

## Screen 1: Splash / Loading Screen

**Purpose:** [e.g., App initialization, branding display while loading library data]

**Displayed Elements:**
- [e.g., App logo]
- [e.g., Loading indicator]
- [e.g., App version number]

**User Actions:**
- [e.g., None — auto-navigates to Home after loading]

**Navigates To:**
- [e.g., Home / Library screen after initialization]
- [e.g., Onboarding screen on first launch]

**Notes:**
[Any additional context about this screen]

---

## Screen 2: [Home / Library Screen]

**Purpose:** [e.g., Main landing screen showing the user's audiobook library]

**Displayed Elements:**
- [e.g., Grid/list of book covers with title and author]
- [e.g., "Continue Listening" section with most recent book and progress bar]
- [e.g., Filter/sort controls (by title, author, date added, progress)]
- [e.g., Search icon in toolbar]
- [e.g., Mini player bar at bottom if a book is in progress]
- [List all visible UI elements]

**User Actions:**
- [e.g., Tap book → navigates to Book Detail]
- [e.g., Long-press book → context menu (delete, add to collection, mark finished)]
- [e.g., Tap search icon → navigates to Search]
- [e.g., Tap filter/sort → shows sort options]
- [e.g., Pull to refresh → re-scans library folder]
- [e.g., Tap mini player → opens full Now Playing screen]
- [List all actions]

**Navigates To:**
- [Book Detail screen]
- [Search screen]
- [Now Playing screen]
- [List all navigation destinations]

**Notes:**
[Any additional context]

---

## Screen 3: [Book Detail Screen]

**Purpose:** [e.g., Shows detailed information about a single audiobook]

**Displayed Elements:**
- [e.g., Large cover art]
- [e.g., Title, author, narrator]
- [e.g., Duration, chapter count]
- [e.g., Progress indicator (X% complete, X hr Y min remaining)]
- [e.g., Play / Resume button]
- [e.g., Chapter list]
- [e.g., Bookmarks list for this book]
- [e.g., Description / summary]
- [e.g., Series info if applicable]
- [List all visible UI elements]

**User Actions:**
- [e.g., Tap Play/Resume → starts playback and opens Now Playing]
- [e.g., Tap a chapter → starts playback from that chapter]
- [e.g., Tap bookmark → jumps to bookmarked position]
- [e.g., Tap "Add Bookmark" → creates bookmark at current position]
- [e.g., Tap share → shares book info]
- [e.g., Tap delete → removes book from library (with confirmation)]
- [List all actions]

**Navigates To:**
- [Now Playing screen]
- [Back to Library]
- [List all]

**Notes:**
[Any additional context]

---

## Screen 4: [Now Playing / Player Screen]

**Purpose:** [e.g., Full-screen playback controls for the currently playing audiobook]

**Displayed Elements:**
- [e.g., Cover art (large)]
- [e.g., Book title, author, narrator]
- [e.g., Chapter title / number]
- [e.g., Progress bar / seek slider with elapsed and remaining time]
- [e.g., Play / Pause button]
- [e.g., Skip forward button (configurable: 15s/30s/60s/custom)]
- [e.g., Skip backward button (configurable: 15s/30s/60s/custom)]
- [e.g., Previous chapter / Next chapter buttons]
- [e.g., Playback speed control (0.5x to 3.0x)]
- [e.g., Sleep timer button with remaining time if active]
- [e.g., Bookmark button]
- [e.g., Chapter list button]
- [e.g., Volume slider or system volume indicator]
- [List all visible UI elements]

**User Actions:**
- [e.g., Tap play/pause → toggles playback]
- [e.g., Tap skip forward → jumps forward by configured interval]
- [e.g., Tap skip backward → jumps backward by configured interval]
- [e.g., Drag seek bar → scrubs to position]
- [e.g., Tap speed → cycles through speed options or opens speed picker]
- [e.g., Tap sleep timer → opens sleep timer options (15m, 30m, 45m, 60m, end of chapter, custom)]
- [e.g., Tap bookmark → saves bookmark at current position]
- [e.g., Tap chapter list → opens chapter navigation overlay]
- [e.g., Swipe down → minimizes to mini player]
- [List all actions]

**Navigates To:**
- [Chapter list overlay]
- [Back to previous screen (minimizes to mini player)]
- [List all]

**Notes:**
[Any additional context. e.g., "This screen should support landscape orientation for car/tablet use"]

---

## Screen 5: [Mini Player Bar]

**Purpose:** [e.g., Persistent playback controls visible at the bottom of other screens]

**Displayed Elements:**
- [e.g., Small cover art thumbnail]
- [e.g., Book title (truncated)]
- [e.g., Play/Pause button]
- [e.g., Thin progress bar]
- [e.g., Skip forward button]
- [List all]

**User Actions:**
- [e.g., Tap play/pause → toggles playback]
- [e.g., Tap skip forward → jumps forward]
- [e.g., Tap anywhere else on the bar → opens full Now Playing screen]
- [List all]

**Navigates To:**
- [Now Playing screen]

**Notes:**
[e.g., "Visible on all screens except the full Now Playing screen. Should not overlap bottom navigation."]

---

## Screen 6: [Search Screen]

**Purpose:** [e.g., Search across the user's local library by title, author, narrator, or series]

**Displayed Elements:**
- [e.g., Search text field with auto-focus]
- [e.g., Recent searches]
- [e.g., Search results list (book cover, title, author, match highlight)]
- [e.g., Filter chips (All, Title, Author, Narrator, Series)]
- [List all]

**User Actions:**
- [e.g., Type query → results update in real time]
- [e.g., Tap result → navigates to Book Detail]
- [e.g., Tap filter chip → filters results by category]
- [e.g., Tap clear → clears search field]
- [List all]

**Navigates To:**
- [Book Detail screen]
- [Back to Library]

**Notes:**
[Any additional context]

---

## Screen 7: [Collections / Shelves Screen]

**Purpose:** [e.g., Organize books into user-created collections or shelves]

**Displayed Elements:**
- [e.g., List/grid of collections with cover art mosaic and book count]
- [e.g., "Create New Collection" button]
- [e.g., Default collections (e.g., "Want to Listen", "Finished", "Favorites")]
- [List all]

**User Actions:**
- [e.g., Tap collection → opens Collection Detail screen]
- [e.g., Tap create → dialog to name new collection]
- [e.g., Long-press collection → edit name, delete]
- [List all]

**Navigates To:**
- [Collection Detail screen]
- [Book Detail screen (from within a collection)]

**Notes:**
[Any additional context]

---

## Screen 8: [Audible Import Screen]

**Purpose:** [e.g., Connect to Audible account and import/backup purchased audiobooks]

**Displayed Elements:**
- [e.g., Audible login / authentication section]
- [e.g., List of Audible library books with import status]
- [e.g., Select All / Deselect All]
- [e.g., Import progress bar for active downloads]
- [e.g., Format selection (keep AAX, convert to M4B/MP3)]
- [e.g., Storage location selector]
- [e.g., Status indicators per book (Not imported, Importing, Imported, Error)]
- [List all]

**User Actions:**
- [e.g., Tap "Sign in to Audible" → opens Audible OAuth flow or credential entry]
- [e.g., Select books to import → checkboxes]
- [e.g., Tap "Import Selected" → begins download and optional conversion]
- [e.g., Tap book row → shows import details / error info]
- [e.g., Tap cancel → stops active import]
- [List all]

**Navigates To:**
- [Import progress detail]
- [Book Detail (for already-imported books)]
- [Back to Library]

**Notes:**
[e.g., "This is the most legally sensitive feature. Should include clear disclaimers about personal backup use only."]

---

## Screen 9: [Settings Screen]

**Purpose:** [e.g., App-wide configuration and preferences]

**Displayed Elements & Sections:**

### Playback Settings
- [e.g., Default skip forward interval: ___ seconds (default: 30)]
- [e.g., Default skip backward interval: ___ seconds (default: 15)]
- [e.g., Default playback speed: ___ (default: 1.0x)]
- [e.g., Resume from last position on app open: On/Off]
- [e.g., Pause on Bluetooth disconnect: On/Off]
- [e.g., Reduce volume on notification: On/Off]
- [List all playback settings]

### Library Settings
- [e.g., Library scan folder path(s)]
- [e.g., Auto-scan for new books: On/Off]
- [e.g., Default sort order: ___]
- [e.g., Default view mode: Grid/List]
- [List all library settings]

### Sleep Timer Defaults
- [e.g., Default sleep timer duration: ___ minutes]
- [e.g., Shake to reset timer: On/Off]
- [e.g., Fade out duration: ___ seconds]
- [List all]

### Download & Storage
- [e.g., Download location: Internal / SD Card / Custom path]
- [e.g., Auto-delete finished books: On/Off]
- [e.g., Storage usage summary]
- [List all]

### Audible Account
- [e.g., Connected account email]
- [e.g., Sign out button]
- [e.g., Default import format]
- [List all]

### Appearance
- [e.g., Theme: Light / Dark / System]
- [e.g., Cover art grid size]
- [List all]

### [Other Settings Sections]
- [List any other setting categories and their options]

**User Actions:**
- [e.g., Toggle switches, select from dropdowns, enter values]
- [e.g., Tap "Clear Cache" → clears cached cover art]
- [e.g., Tap "Export Library Data" → exports bookmarks/progress as JSON]
- [List all]

**Navigates To:**
- [Sub-settings screens if any]
- [Audible Import screen]
- [Back to Library]

**Notes:**
[Any additional context]

---

## Screen 10: [Listening Statistics Screen]

**Purpose:** [e.g., Show the user's listening habits and progress over time]

**Displayed Elements:**
- [e.g., Total listening time (all time, this month, this week)]
- [e.g., Books finished count]
- [e.g., Current streak (consecutive days listened)]
- [e.g., Daily/weekly listening time chart]
- [e.g., Most listened genres/authors]
- [e.g., Average listening session length]
- [List all]

**User Actions:**
- [e.g., Tap time period → switches between week/month/year/all-time views]
- [e.g., Tap a book in history → navigates to Book Detail]
- [List all]

**Navigates To:**
- [Book Detail screen]
- [Back to Library]

**Notes:**
[Any additional context]

---

## Screen 11: [___Screen Name___]

**Purpose:** [Describe purpose]

**Displayed Elements:**
- [List elements]

**User Actions:**
- [List actions]

**Navigates To:**
- [List destinations]

**Notes:**
[Any notes]

---

## Screen 12: [___Screen Name___]

**Purpose:** [Describe purpose]

**Displayed Elements:**
- [List elements]

**User Actions:**
- [List actions]

**Navigates To:**
- [List destinations]

**Notes:**
[Any notes]

---

*Copy the blank template above (Screen 11/12) for any additional screens.*
