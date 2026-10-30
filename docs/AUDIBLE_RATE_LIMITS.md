# Audible download limit — ongoing investigation

**Status: open, long-term.** This is a standing question, not a task with an end
date. Every change that touches Audible should be weighed against it, and every
request log the user shares should be read with it in mind.

**The question:** what exactly makes Audible refuse our download (license)
requests with `[Ownership] CustomerThrottled`, how long does it last, and what
lifts it?

Background and history: [AUDIBLE_DOWNLOAD_ISSUE.md](AUDIBLE_DOWNLOAD_ISSUE.md).

## What we know

- The refusal is a `200` from `POST /1.0/content/{asin}/licenserequest` with no
  `offline_url` and `license_denial_reasons` containing
  `[Ownership] CustomerThrottled` (the membership/AYCL reasons are noise).
- Library fetches (`/1.0/library`), catalog lookups and activation bytes keep
  working while license requests are refused.
- Downloading the same title from the audible.com website keeps working
  (v0.119 adds an in-app fallback for this).
- Other clients (Libation, audible-cli, Oahu) started seeing the same refusal
  around 2026-09-02. No official limits are published.

## What we don't know (hypotheses to test)

- **Count-based:** N license requests per hour/day per account? Per device
  registration?
- **Rate-based:** requests too close together (bursts) regardless of total?
- **Failed requests count too:** does each refused request extend the block?
  (The app pauses license requests for 15 min after a refusal and retries pending
  books at 15 min → 30 min → 1 h → 2 h → 4 h, so we can compare.)
- **Other endpoints count:** do library syncs, catalog lookups (Book details
  screen, series lists) or token refreshes add to the limit?
- **Client identity:** device type, User-Agent, request body (see "Ideas not yet
  tried" in the issue doc). Does a fresh device registration reset it?
- **Duration:** how long after the last refusal does the first success come?
- **Website vs API:** do website downloads count against the API limit?

## Data we collect

Every interaction with Audible/Amazon is written to a saved log that survives
restarts (`AudibleRequestLog`, `files/audible_requests.log`, ~2 MB, then one
older file is kept). Settings → View Debug Log → **Audible log** copies the
latest 3000 lines.

Tab-separated: `time  source  method  host  path  status  ms  bytes  note`

- Sources: `api` (every request from the app's HTTP client — license, library,
  catalog, chapters, auth/token refresh, activation bytes, audio download — one
  line per redirect hop), `website-download` (the .aax download from the website),
  `webview-website` / `webview-signin` (pages opened in the in-app browsers),
  `event` (app start with version, `download-requested`, `license-denied` with
  every reason, `throttled`, `pending`, `retry-run`).
- Query strings are not recorded (they can contain signed tokens); paths keep the
  ASIN.

**Rule:** any new code that talks to Audible or Amazon must show up in this log.
Requests through the shared Ktor `HttpClient` are logged automatically; anything
else (`HttpURLConnection`, WebViews, another HTTP client) must call
`AudibleRequestLog.request()` itself. Notable decisions (throttle detected,
retry scheduled) should be `event()` lines.

## How to analyse a log

For each `license-denied … CustomerThrottled` event, look back over the
preceding hours: count license requests (`POST …/licenserequest`), all `api`
requests, their spacing, and the time since the previous success. For each
first success after a block, note how long the block lasted and what happened in
between. Record conclusions below with the date and app version.

## Findings

| Date | Version | Observation | Conclusion |
|------|---------|-------------|------------|
| 2026-10-20 | v0.107 | Throttled after little recent downloading; library still works | Not just download volume (unconfirmed) |
| 2026-10-26 | v0.120 | Request log added | — |
| 2026-10-26 | v0.120 | While the API refused license requests, the website's `/library/download?asin=…&codec=AAX_44_128` answered normally — with `AUDIBLE_KINDLE_DOWNLOAD_ERROR` "not supported for full audio file download, please download individual parts" for a long title (Chrysalis, Books 1-3) | Website downloads aren't blocked by the API limit; long titles must be fetched in parts (v0.121 reads the part links from the library page) |
| 2026-10-27 | v0.125 | Website download of a 5-part title (Chrysalis, Books 1-3, `B0CS3XXW7C`) succeeded while the API was limited. Each "Part N" row on the desktop library page opens `/library/download?asin=<part ASIN>&codec=AAX` — every part has its own ASIN (`B0CS52NYXV`, `B0CS58G5PQ`, …) — which redirects to `cds.audible.com/download` (200). 5 downloads in ~6 min, ~1.5 GB, no refusals | Website downloads still unaffected by the API limit; five back-to-back website downloads didn't trigger anything |
| 2026-10-30 | v0.121–v0.124 | Request log 10-26 13:16 → 10-29 13:36 (log as pasted stops there). Refused at 10-26 13:16, 13:56 and 15:02 (automatic retries 40 min and 66 min apart); after the 16:45 website download cleared the pending book, **no license requests at all** for ~70 h. The next one, 10-29 13:36:40 (debug log), was still refused `CustomerThrottled` | The block lasts at least ~3 days and doesn't need repeated requests to keep it going — so it is not "each refusal extends it by 15 min". Rules out a short rolling window (minutes–hours) |
| 2026-10-30 | v0.124 | 10-29 13:36:08–13:36:38: series sync made ~150 catalog requests (`/catalog/products/{series}` + `/catalog/products?asins=`) ~150 ms apart, all `200`; the license request 2 s later was refused | Catalog bursts aren't refused themselves. Can't tell whether they count toward the license limit — the account was already blocked. Worth pacing them anyway |
| 2026-10-30 | v0.121–v0.124 | Website `/library/download` for the whole 5-part title (`B0CS3XXW7C`) → `cds.audible.com/download` `500` three times (13:16, 13:20, 13:51); per-part downloads at 16:45 all `200` | Confirms the earlier finding: long titles only download from the website in parts |
| 2026-10-30 | v0.124–v0.207 | Full request log to 10-30 16:33. Confirms the 10-29 13:36:40 refusal (`B0CRSC3RQF`, 1st try for that title) with no license request since 10-26 15:02. 14 s later the website download of the same title (`codec=AAX_44_128`, one file, 1.03 GB) worked. No license requests since, so nothing yet on when the block ends | Block ≥ 3 days 0 h with only 4 refused requests in it. Website downloads still unaffected. Next license request will show whether it has lifted |
| 2026-10-30 | v0.204 | Series syncs at 10-29 18:42 (2 series) and 10-30 16:09 (~150 catalog requests in 30 s) all `200`. Opening the Audible tab fetched `/1.0/library` twice 2 s apart (16:25:22, 16:25:24) | Catalog bursts still never refused. The double library fetch is avoidable traffic (v0.206 spaces catalog requests 1 s apart) |
