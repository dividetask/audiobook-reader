# Audible Integration

This document covers how the app imports, downloads, and backs up audiobooks from a user's Audible account.

## Legal Disclaimer

This feature is intended **exclusively for personal backup** of audiobooks the user has legally purchased. Users are responsible for complying with:

- Audible's Terms of Service
- The Digital Millennium Copyright Act (DMCA) — [note: personal backup may be protected under fair use in some jurisdictions]
- Applicable copyright laws in their jurisdiction

[Add or modify legal language as appropriate. Consider consulting a lawyer.]

---

## Overview

| Aspect | Detail |
|--------|--------|
| Supported Audible Regions | [e.g., US (audible.com), UK (audible.co.uk), DE (audible.de), all — list which] |
| Authentication Method | [e.g., OAuth via Audible's login page, credential-based auth, auth token import] |
| Download Format | [e.g., AAX (Audible's encrypted format)] |
| Conversion Support | [e.g., AAX → M4B, AAX → MP3, AAX → FLAC — list supported conversions] |
| Conversion Tool | [e.g., Built-in FFmpeg, external tool, AAXtoMP3 integration] |
| Metadata Preservation | [e.g., Chapter markers, cover art, title/author — list what is preserved] |
| Credential Storage | [e.g., Encrypted in Android Keystore / iOS Keychain / not stored] |

---

## Authentication Flow

### Step-by-Step

1. [e.g., User taps "Connect Audible Account" in Settings or Import screen]
2. [e.g., App opens a WebView / browser to Audible's login page]
3. [e.g., User enters Audible credentials and completes any 2FA]
4. [e.g., App captures the authentication token / cookies from the response]
5. [e.g., Token is stored securely in ___]
6. [e.g., App uses token to access Audible's API for library listing]

### Security Considerations

- [e.g., Credentials are NEVER stored in plain text]
- [e.g., Auth tokens are stored in: ___]
- [e.g., Token refresh strategy: ___]
- [e.g., User can revoke access by signing out, which deletes all stored tokens]
- [List all security measures]

### Supported Authentication Methods

- [ ] [e.g., Audible OAuth (if available)]
- [ ] [e.g., Amazon account login via WebView]
- [ ] [e.g., Manual auth token entry (for advanced users)]
- [ ] [e.g., Import from existing tool (e.g., OpenAudible export)]
- [Check which methods will be supported]

---

## Library Retrieval

Once authenticated, the app retrieves the user's Audible library.

### Data Retrieved Per Book

| Field | Source | Stored Locally |
|-------|--------|---------------|
| ASIN (Audible ID) | [API response] | [Yes/No] |
| Title | [API response] | [Yes/No] |
| Author(s) | [API response] | [Yes/No] |
| Narrator(s) | [API response] | [Yes/No] |
| Duration | [API response] | [Yes/No] |
| Cover Art URL | [API response] | [Yes — downloaded] |
| Series Name | [API response] | [Yes/No] |
| Series Position | [API response] | [Yes/No] |
| Purchase Date | [API response] | [Yes/No] |
| Description/Summary | [API response] | [Yes/No] |
| Chapter Info | [API/file metadata] | [Yes/No] |
| [Other field] | [Source] | [Yes/No] |

### Library Sync Behavior

- [e.g., Full library fetch on first connection]
- [e.g., Incremental sync on subsequent opens — only fetch new purchases]
- [e.g., User can trigger manual refresh]
- [e.g., Sync frequency: manual only / on app open / configurable interval]

---

## Download Process

### Download Flow

```
User selects book(s) to import
  → App requests download URL from Audible API (using auth token)
  → App downloads .aax file to [temporary location / final location]
  → [If conversion enabled]: 
      → App decrypts AAX using activation bytes / auth token
      → App converts to target format (M4B/MP3) using [FFmpeg / other tool]
      → Converted file saved to [user-configured library path]
      → Original .aax file [deleted / kept based on setting]
  → [If no conversion]:
      → .aax file moved to library path
  → Book metadata + chapters written to local database
  → Book appears in library
```

### Activation Bytes / Decryption

[This is the most technically and legally sensitive part of the integration.]

- **Method:** [e.g., Activation bytes derived from Audible account, voucher-based download, direct API download]
- **How obtained:** [e.g., Extracted from authenticated session, user provides manually, calculated from account info]
- **Storage:** [e.g., Stored encrypted alongside account credentials]
- **Legal note:** [e.g., "Decryption of purchased content for personal backup may be protected under fair use. This project does not condone piracy."]

### Download Settings

| Setting | Options | Default |
|---------|---------|---------|
| Output format | [e.g., M4B, MP3, FLAC, keep AAX] | [___] |
| Audio quality | [e.g., Original, re-encode at ___ kbps] | [___] |
| Download location | [e.g., Internal storage, SD card, custom path] | [___] |
| Concurrent downloads | [e.g., 1, 2, 3] | [___] |
| Keep original AAX | [e.g., Yes / No] | [___] |
| Auto-import to library | [e.g., Yes / No] | [___] |
| Download on Wi-Fi only | [e.g., Yes / No] | [___] |
| [Other setting] | [Options] | [___] |

### Download Status States

| State | Description | UI Indicator |
|-------|-------------|-------------|
| Not Downloaded | Book exists in Audible but not locally | [e.g., Cloud/download icon] |
| Queued | Waiting to download | [e.g., Clock icon] |
| Downloading | Active download in progress | [e.g., Progress bar with percentage] |
| Converting | Downloaded, conversion in progress | [e.g., Spinning gear icon] |
| Imported | Successfully in local library | [e.g., Checkmark icon] |
| Error | Download or conversion failed | [e.g., Red exclamation icon with retry option] |
| [Other state] | [Description] | [Indicator] |

---

## Format Conversion

### Supported Conversions

| Input Format | Output Format | Chapter Markers | Cover Art | Metadata |
|-------------|---------------|----------------|-----------|----------|
| AAX → M4B | [Supported?] | [Preserved?] | [Preserved?] | [Preserved?] |
| AAX → MP3 | [Supported?] | [N/A or via cue sheet] | [Embedded?] | [Preserved?] |
| AAX → FLAC | [Supported?] | [Preserved?] | [Embedded?] | [Preserved?] |
| AAX → Opus | [Supported?] | [Preserved?] | [Embedded?] | [Preserved?] |
| [Other] | [Other] | [Preserved?] | [Preserved?] | [Preserved?] |

### Conversion Engine

- **Tool:** [e.g., FFmpeg (bundled / system), mobile-ffmpeg, custom decoder]
- **Runs on:** [e.g., Device locally, no server-side processing]
- **Performance:** [e.g., Estimated conversion speed relative to book length: ___]
- **Battery consideration:** [e.g., Conversion pauses on low battery: Yes/No]

---

## Existing Tools & Inspiration

The following open-source projects provide reference for Audible integration:

| Project | Description | Relevance |
|---------|-------------|-----------|
| [OpenAudible](https://openaudible.org/) | Desktop Audible manager | [e.g., Reference for API interaction, format conversion] |
| [Libation](https://github.com/rmcrackan/Libation) | Audible library manager (.NET) | [e.g., Reference for authentication flow, book decryption] |
| [Audible-cli](https://github.com/mkb79/audible-cli) | Python CLI for Audible API | [e.g., Reference for API endpoints, auth flow] |
| [AAXtoMP3](https://github.com/KrumpetPirate/AAXtoMP3) | Bash script for AAX conversion | [e.g., Reference for FFmpeg conversion commands] |
| [audible (Python)](https://github.com/mkb79/Audible) | Python Audible API wrapper | [e.g., Most complete API documentation] |
| [___] | [Description] | [Relevance] |

---

## API Endpoints Used

[Document the Audible API endpoints the app will interact with. These are not officially documented by Amazon.]

| Endpoint | Purpose | Method |
|----------|---------|--------|
| [e.g., /1.0/library] | [List user's library] | [GET] |
| [e.g., /1.0/library/{asin}] | [Get book details] | [GET] |
| [e.g., /1.0/content/{asin}/licenserequest] | [Get download license/URL] | [POST] |
| [e.g., /1.0/annotations] | [Get bookmarks/notes] | [GET] |
| [___] | [Purpose] | [Method] |

**API Base URLs by Region:**

| Region | Base URL |
|--------|----------|
| US | [e.g., https://api.audible.com] |
| UK | [e.g., https://api.audible.co.uk] |
| DE | [e.g., https://api.audible.de] |
| [Other] | [URL] |

---

## Syncing Audible Progress (Optional)

[Describe whether the app will sync playback position with Audible]

- **Sync position FROM Audible:** [e.g., Yes — import last known position / No]
- **Sync position TO Audible:** [e.g., No — one-way only / Yes]
- **Sync bookmarks/notes:** [e.g., Import only / Bidirectional / Not supported]
- **Whispersync equivalent:** [e.g., Not planned / Planned with self-hosted server]

---

## Privacy & Data Handling

- [e.g., Audible credentials are stored in ___ and never transmitted to any third party]
- [e.g., No analytics or telemetry related to Audible usage]
- [e.g., Downloaded books remain on-device only — no cloud upload]
- [e.g., User can delete all Audible data (tokens, cached library info) via Settings]
- [List all privacy-relevant details]

---

## Known Limitations

- [e.g., Audible may change their API without notice, breaking integration]
- [e.g., Two-factor authentication adds complexity to the auth flow]
- [e.g., Some Audible-exclusive features (e.g., Whispersync for Voice) cannot be replicated]
- [e.g., Audible Plus catalog titles (non-purchased) may not be downloadable]
- [e.g., AAX conversion requires significant CPU time on mobile devices]
- [List all known limitations]
