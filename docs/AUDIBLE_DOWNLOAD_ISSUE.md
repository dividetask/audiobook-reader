# Audible download failures — handoff notes

Status as of v0.107 a (2026-10-20). Downloads from Audible fail; importing
`.aax` files downloaded from the Audible website works (Library tab → +).

## Symptom

`POST https://api.audible.com/1.0/content/{asin}/licenserequest` returns
**200** but with no `offline_url`; instead `content_license.license_denial_reasons`:

```
[Membership] RequesterEligibility: Customer is not part of any plans
[Ownership]  CustomerThrottled:    Customer id [A77WDUWXGDV9H] being throttled
[Client]     RequesterEligibility: urn:cdo:AudibleApiExternalRouterService:Prod:Default[AAA],
             does not has access to asin[B004ZLBFZ4].…Default[LWA], does not has access to asin[…]
[AYCL]       ContentEligibility:   Asin: [B004ZLBFZ4] is not eligible for AYCL
```

- "Not part of any plans" and "not eligible for AYCL" are membership / Plus
  catalogue checks — they show up even for purchased titles and are noise.
- **Ownership → CustomerThrottled** and **Client → [AAA]/[LWA] no access** are
  the real refusals.
- Same book downloads fine in the official Audible app on the same account,
  and from the Audible website. So the account owns it; only our client is refused.
- The user had not downloaded much recently. Library fetch (`/1.0/library`)
  still works (215 books).

## What other projects report (since ~2026-09-02)

- Libation, audible-cli and Oahu all hit the same denial on the same day.
  - Libation #2053, #2036, #2021; v14.0.2 release notes ("wait 24–48h").
  - Oahu PR #64: switched registration from Android emulator
    (`A10KISP2GWF0E4`) to iPhone (`A2CZJZGLK2JJVM`), `registration_data.domain`
    `Device`, iOS `assoc_handle`, matching User-Agent. Changed
    `supported_drm_types` order too (not isolated). Needed re-login.
  - omarchy-audible PR #121: detects CustomerThrottled, pauses 4h → 8h → 16h → 24h cap.
- Libation users: removing and re-adding the account (= new device
  registration) sometimes fixes it, sometimes for only one or two downloads.
- No official documentation of limits.

## What this app already does

- Registers as Audible for iPhone: device type `A2CZJZGLK2JJVM`, `domain: Device`,
  `amzn_audible_ios_*` assoc handle (`audible/auth/AudibleAuthManager.kt`,
  `AudibleEndpoints.kt`).
- v0.104: API requests (license, library, chapters) are ADP-signed
  (`x-adp-token`, `x-adp-alg`, `x-adp-signature`) via `AudibleRequestSigner`,
  like audible-cli. 401/403 retries fall back to bearer. **Did not change the
  result** — but the account was still throttled when tested (~2h after the
  first failures), so this is inconclusive.
- v0.103: parses and logs every denial reason; on CustomerThrottled skips
  license requests for 15 min (`AudibleApiClient.throttledUntil`), shows a
  snackbar, stops server backups (`AudibleThrottledException` in `BackupEngine`).
- Activation bytes (`/license/token`, signed) still work: `b36b9c06`.

## Ideas not yet tried

1. License request body like audible-cli:
   `{"supported_drm_types": ["Mpeg", "Adrm"], "quality": "High",
   "consumption_type": "Download", "response_groups": "last_position_heard,pdf_url,content_reference"}`
   (ours sends `"drm_type": "Adrm"`, which audible-cli doesn't).
2. iPhone app `User-Agent` / app-version headers on API requests.
3. Request AAXC (`supported_drm_types` incl. `Mpeg`/`Adrm` and handle the
   key/iv voucher — download code already handles key/iv).
4. Sign out / sign in to get a fresh device registration (after waiting 24–48h).
5. Longer throttle pause (omarchy schedule) — user said "not yet".

Possible hard wall: the official apps may use device attestation (App Attest /
Play Integrity) that third-party clients can't produce. Unconfirmed.

Note: impersonating the official client is against Audible's terms; the user
has been told. Website download + import avoids that.

## Testing tips

- Each attempt is a license request; during a throttle, retries may extend it.
  Make one attempt, then wait.
- Debug log (Settings → View Debug Log) shows `Auth: ADP-signed` and each
  `Denied [...]` line.
