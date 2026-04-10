# Screens & Navigation

This document describes every screen in the app, what it displays, what actions are available, and how screens connect to each other.

## Navigation Overview

Bottom tab bar with 4 tabs: Player, Local, Library, Store

Tabs / Top-Level Destinations:
1. Current Book (Player)
2. Local Library
3. Cloud Library
4. Store
5. Options

## Screen 1: Player Screen

**Purpose:** Control playback of audio file, browse chapters, browse bookmarks, add new bookmarks, voice recognition search

**Displayed Elements:**
- Bottom Menu Tabs
- Book Cover Image
- Current Chapter
- Remaining Time
- Playback Controls
- Speed Controls
- Bookmark Controls

**User Actions:**
- Tap play/pause → toggles playback
- Tap skip forward → jumps forward by configured interval (default 30 seconds)
- Tap skip backward → jumps backward by configured interval (default 30 seconds)
- Drag seek bar → scrubs to position
- Tap speed → cycles through speed options or opens speed picker
- Tap bookmark → saves bookmark at current position
- Tap chapter list → opens chapter navigation overlay
- Tap bookmark list → opens bookmark navigation overlay
- Tap search → opens search overlay text input and button allowing voice recognition search


**Navigates To:**
- Local Library (Screen 2)
- Cloud Library (Screen 3)
- Store (Screen 4)
- Options (Screen 6)

---

## Screen 2: Local Library

**Purpose:** Allows user to select which downloaded book they want to listen to. Also allows deleting books

**Displayed Elements:**
- List of books with title, author, and length
- Current book and progress bar (Hidden if no book is active)

**User Actions:**
- Change current book
- Remove local copy of book
- Read book details

**Navigates To:**
- Current Book (Screen 1)
- Cloud Library (Screen 3)
- Store (Screen 4)
- Book Details (Screen 5)
- Options (Screen 6)

---

## Screen 3: Cloud Library

**Purpose:** Allows user to manage local library and organize remote library

**Displayed Elements:**
- List of all book series or individual titles
- List of all books in a series with expand or hide controls (hidden by default)
- List of all archived books (hidden by default)
- Read book details
- Current book and progress bar (Hidden if no book is active)

**User Actions:**
- Change current book
- Download local copy of book
- Remove local copy of book
- Archive book
- Unarchive book
- Hide or show books in a specific series
- Hide or show archived books
- Read book details

**Navigates To:**
- Current Book (Screen 1)
- Local Library (Screen 2)
- Store (Screen 4)
- Book Details (Screen 5)
- Options (Screen 6)

---

## Screen 4: [Store Screen]

**Purpose:** Allow user to purchase books from Audible, Libro.fm, or any other implemented platform

**Displayed Elements:**
- TBD
- Current book and progress bar (Hidden if no book is active)

**User Actions:**
- TBD

**Navigates To:**
- Current Book (Screen 1)
- Local Library (Screen 2)
- Cloud Library (Screen 3)
- Options (Screen 6)

---


## Screen 5: [Book Detail Screen]

**Purpose:** Shows detailed information about a single audiobook

**Displayed Elements:**
- Large cover art
- Title, author, narrator
- Duration, chapter count
- Start button
- Description / summary
- List of other books in series if applicable

**User Actions:**
- Tap Start → Changes current book
- Tap Download → Downloads local copy

**Navigates To:**
- Current Book (Screen 1)
- Local Library (Screen 2)
- Cloud Library (Screen 3)
- Store (Screen 4)
- Options (Screen 6)

---

## Screen 6: [Options Screen]

**Purpose:** Configure NextCloud, Audible, and Libro.fm login credentials in addition to other necessary configuration

**Displayed Elements:**
- Enable/Disable Nextcloud checkbox
- NextCloud server ip address/url
- NextCloud username and password
- NextCloud audiobook folder
- Enable/Disable Audible checkbox
- Audible username and password
- Enable/Disable Libro.fm checkbox
- Libro.fm username and password

**User Actions:**
- Configure login information

**Navigates To:**
- Current Book (Screen 1)
- Local Library (Screen 2)
- Cloud Library (Screen 3)
- Store (Screen 4)
- Book Details (Screen 5)

---

