# Lock Screen & Notification Controls

This document describes all playback controls accessible **without unlocking the device**, including lock screen media controls, notification shade controls, and hardware/peripheral interactions.

## Overview

When an audiobook is playing, the user must be able to control playback without unlocking their phone. This is critical for hands-free use cases such as driving, exercising, or falling asleep.

**Target platforms:** Android (tested target: Android 17). Implemented in v0.109 a.

---

## Lock Screen Media Controls

On Android 13+ the lock screen and notification share the system media controls,
built from the app's MediaSession (`player/PlaybackService.kt`).

### Displayed Information

| Element | Description |
|---------|-------------|
| Cover Art | Book cover |
| Title | Book title |
| Subtitle | Author |
| Progress Bar | Position within the **current chapter** (whole book if the book has no chapters). Not draggable. |
| Elapsed / Remaining | Within the current chapter (drawn by the system) |

### Available Controls

| Control | Behavior | Icon/Label |
|---------|----------|------------|
| Play / Pause | Toggles playback | System play/pause |
| Skip Backward | Jumps back by the configured skip-back seconds (Settings → Playback, default 15s). Recorded in the Jumps history as "Lock screen skip back". | Counter-clockwise arrow, label "Back Ns" |
| Skip Forward | Jumps forward by the configured skip-forward seconds (default 30s). Recorded as "Lock screen skip forward". | Clockwise arrow, label "Forward Ns" |

### Controls NOT Available on Lock Screen

- Previous / next chapter (deliberately removed)
- Dragging the progress bar to seek
- Speed, chapter list, history — require unlocking

### How chapter progress works

The session is given `ChapterProgressPlayer`, a wrapper that reports position and
duration relative to the current chapter. The app reads the real player through
`PlayerHolder`, so in-app positions stay absolute. System controllers (notification,
lock screen, Bluetooth) are given play/pause and the two skip commands only — no
seek or previous/next commands.

---

## Notification Shade Controls

When the notification shade is pulled down during playback, a media notification is displayed.

### Notification Layout

**Collapsed Notification:**
- [e.g., Cover art thumbnail, book title, author, play/pause, skip forward]
- [List all elements visible in collapsed state]

**Expanded Notification:**
- [e.g., Larger cover art, book title, author, chapter title]
- [e.g., Previous chapter, skip back, play/pause, skip forward, next chapter]
- [e.g., Progress bar (seekable: Yes/No)]
- [List all elements visible in expanded state]

### Notification Actions

| Action | Collapsed | Expanded | Behavior |
|--------|-----------|----------|----------|
| Play / Pause | [Yes/No] | [Yes/No] | [Same as lock screen] |
| Skip Forward | [Yes/No] | [Yes/No] | [Same as lock screen] |
| Skip Backward | [No/Yes] | [Yes/No] | [Same as lock screen] |
| Next Chapter | [No/Yes] | [Yes/No] | [Behavior description] |
| Previous Chapter | [No/Yes] | [Yes/No] | [Behavior description] |
| Dismiss | [Yes/No] | [Yes/No] | [e.g., Stops playback and removes notification / only hides notification] |
| [Other action] | [Yes/No] | [Yes/No] | [Behavior] |

### Notification Behavior

- **Persistence:** [e.g., Notification persists while playback is active. Becomes dismissible X minutes after pause]
- **Auto-dismiss:** [e.g., Notification auto-dismisses after ___ minutes of being paused / never / on sleep timer end]
- **Priority:** [e.g., High priority (heads-up) on play start / Low priority (silent)]
- **Channel:** [e.g., Dedicated "Playback" notification channel that users can configure in system settings]

---

## Hardware Button Controls

Controls available via physical device buttons while the screen is locked.

| Button | Single Press | Double Press | Long Press |
|--------|-------------|--------------|------------|
| Volume Up | [e.g., Increase volume] | [e.g., N/A] | [e.g., N/A] |
| Volume Down | [e.g., Decrease volume] | [e.g., N/A] | [e.g., N/A] |
| Power Button | [e.g., N/A — system default] | [e.g., N/A] | [e.g., N/A] |
| [Other HW button] | [Action] | [Action] | [Action] |

---

## Bluetooth / Headphone Controls

Controls available via Bluetooth headsets, earbuds, or wired headphones.

| Event | Behavior |
|-------|----------|
| Single press (play/pause button) | Toggle play/pause |
| Next / previous track buttons | Do nothing |
| Double press | [e.g., Skip forward by configured interval] |
| Triple press | [e.g., Skip backward by configured interval] |
| Long press | [e.g., Trigger voice assistant / do nothing / configurable] |
| Headphone disconnect | [e.g., Pause playback immediately] |
| Headphone reconnect | [e.g., Auto-resume playback: Yes/No (configurable)] |
| [Other event] | [Behavior] |

### Bluetooth Metadata (AVRCP)

Information sent to Bluetooth devices / car displays:

| Field | Value |
|-------|-------|
| Track Title | [e.g., Chapter title, or book title] |
| Artist | [e.g., Author name, or narrator name] |
| Album | [e.g., Book title, or series name] |
| Album Art | [e.g., Book cover image] |
| Duration | [e.g., Chapter duration, or total book duration] |
| Position | [e.g., Current position within chapter / book] |
| [Other field] | [Value] |

---

## Android Auto / CarPlay Integration

[e.g., "Planned for v2.0" / "Not planned" / describe current support]

### Android Auto

- [e.g., Displays in media app list]
- [e.g., Shows library browsable by: recently played, collections, all books]
- [e.g., Voice commands supported: "Play [book title]", "Pause", "Skip"]
- [List all Android Auto features]

### Apple CarPlay

- [e.g., Not applicable (Android only)]
- [e.g., Displays in media app list]
- [List all CarPlay features]

---

## Wear OS / Watch Controls

[e.g., "Planned for v3.0" / "Not planned" / describe current support]

- [e.g., Media controls tile on watch face]
- [e.g., Standalone app with offline playback]
- [e.g., Complication showing current book/progress]
- [List all watch features]

---

## Sleep Timer Behavior on Lock Screen

Since sleep timer is a key use case when the device is locked:

- **Active sleep timer indicator:** [e.g., Shown in notification as countdown / not shown]
- **Timer expiration behavior:** [e.g., Fades audio over ___ seconds, then pauses]
- **Shake to extend:** [e.g., Shaking the device extends the timer by ___ minutes (configurable: Yes/No)]
- **End-of-chapter mode:** [e.g., Timer waits until current chapter ends before pausing]
- [Other sleep timer behaviors accessible from lock screen]

---

## Audio Focus & Interruption Handling

Behavior when other apps or system events interrupt playback while the device is locked:

| Interruption | Behavior |
|-------------|----------|
| Phone call incoming | [e.g., Pause playback, resume when call ends] |
| Phone call ends | [e.g., Auto-resume after ___ second delay] |
| Navigation voice prompt | [e.g., Duck volume (lower to ___%) during prompt, restore after] |
| Alarm / timer | [e.g., Pause playback, resume when dismissed] |
| Other media app starts | [e.g., Pause playback, do NOT auto-resume] |
| Notification sound | [e.g., Duck volume briefly / pause briefly / ignore] |
| [Other interruption] | [Behavior] |

---

## Platform-Specific Implementation Notes

### Android
- [e.g., Uses MediaSession API for lock screen and notification controls]
- [e.g., Foreground service with notification required for background playback]
- [e.g., MediaBrowserService for Android Auto integration]
- [e.g., Notification channel: "Playback" — user can customize importance]
- [Add Android-specific notes]

### iOS
- [e.g., Uses MPNowPlayingInfoCenter and MPRemoteCommandCenter]
- [e.g., Background audio mode enabled in capabilities]
- [e.g., Control Center integration automatic via Now Playing info]
- [Add iOS-specific notes, or "N/A — Android only"]

---

## Summary: Controls by Context

| Control | Lock Screen | Notification (Collapsed) | Notification (Expanded) | Bluetooth | Hardware Buttons |
|---------|------------|-------------------------|------------------------|-----------|-----------------|
| Play/Pause | [x] | [x] | [x] | [x] | [ ] |
| Skip Forward | [x] | [x] | [x] | [x] | [ ] |
| Skip Backward | [x] | [ ] | [x] | [x] | [ ] |
| Next Chapter | [ ] | [ ] | [x] | [ ] | [ ] |
| Previous Chapter | [ ] | [ ] | [x] | [ ] | [ ] |
| Seek/Scrub | [x] | [ ] | [x] | [ ] | [ ] |
| Volume | [ ] | [ ] | [ ] | [ ] | [x] |
| Bookmark | [ ] | [ ] | [ ] | [ ] | [ ] |
| Speed Change | [ ] | [ ] | [ ] | [ ] | [ ] |
| Sleep Timer | [ ] | [ ] | [ ] | [ ] | [ ] |
| Dismiss/Stop | [ ] | [x] | [x] | [ ] | [ ] |

*Fill in [x] for available, [ ] for not available. Adjust rows/columns as needed.*
