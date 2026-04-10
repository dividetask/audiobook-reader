# Lock Screen & Notification Controls

This document describes all playback controls accessible **without unlocking the device**, including lock screen media controls, notification shade controls, and hardware/peripheral interactions.

## Overview

When an audiobook is playing, the user must be able to control playback without unlocking their phone. This is critical for hands-free use cases such as driving, exercising, or falling asleep.

**Target platforms:** Android

---

## Lock Screen Media Controls

The lock screen displays a media control widget when playback is active or paused.

### Displayed Information

| Element | Description |
|---------|-------------|
| Title | Book title |
| Subtitle | Chapter title |
| Playback Controls | Play, Pause, Skip 30 ahead/back |

### Available Controls

| Control | Behavior | Icon/Label |
|---------|----------|------------|
| Play / Pause | Shows pause icon when playing, play icon when paused | Play/Pause icon |
| Skip Forward | Jumps forward by 30s | +30s icon |
| Skip Backward | Jumps backward by 30s | -30s icon |
| Chapter Forward | Jumps to next chapter | Right arrow |
| Chapter Backward | Jumps to start of chapter or last chapter | Left Arrow |
| Bookmark Creation | Adds bookmark at current spot | Bookmark icon |

### Controls NOT Available on Lock Screen

- Progress bar skip

## Hardware Button Controls

Controls available via physical device buttons while the screen is locked.

| Button | Single Press | Double Press | Long Press |
|--------|-------------|--------------|------------|
| Volume Up | Increase volume | N/A | N/A |
| Volume Down | Decrease volume | Mute | Mute |
| Power Button | system default | system default | system default |

---

## Bluetooth / Headphone Controls

Controls available via Bluetooth headsets, earbuds, or wired headphones.

| Event | Behavior |
|-------|----------|
| Single press (play/pause button) | Toggle play/pause |
| Double press | Skip forward by 30 seconds |
| Triple press | Skip backward by 30 seconds |
| Headphone reconnect | Auto-resume playback: No |

### Bluetooth Metadata (AVRCP)

Information sent to Bluetooth devices / car displays:

| Field | Value |
|-------|-------|
| Track Title | Chapter title, or book title |
| Artist | Author name, or narrator name |
| Album | Book title, or series name |
| Album Art | Book cover image |
| Duration | Chapter duration, or total book duration |
| Position | Current position within chapter / book |

## Audio Focus & Interruption Handling

Behavior when other apps or system events interrupt playback while the device is locked:

| Interruption | Behavior |
|-------------|----------|
| Phone call incoming | Pause playback, resume when call ends |
| Phone call ends | Auto-resume after 3 second delay |
| Navigation voice prompt | Duck volume (lower to 50%) during prompt, restore after |
| Alarm / timer | Pause playback, resume when dismissed |
| Other media app starts | Pause playback, do NOT auto-resume |
| Notification sound | Duck volume briefly |

