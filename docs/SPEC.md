# WhoHas — product and API spec (v0.4)

**WhoHas** is a contact discovery platform. For any phone number, it shows who on WhoHas has it: people who have it in their contacts or their call log. People the viewer knows are named; everyone else is a count.

v0.4 (16 Nov 2026) follows the final design (90 screens): call logs count, contact names are stored encrypted for the popular name, every number looks the same everywhere, sign-in comes before a name, WhoHas is never the caller ID app (it tells people after a call, from the call log), the evening note and wake-ups come by push, phone health is reported by brand, and the invite and opt-out web pages are served by the API. Section 10 lists the changes; sections below describe v0.4. **The design that the app follows is in `docs/DESIGN.md` (project doc `claude/found-with-design.md`).**

## 1. Features

| Feature | What the user sees |
|---|---|
| **Sign-in** | One tap: Android's own sheet offers the phone's numbers, the SMS code is read by itself, and Home opens. The name comes later, once. |
| **Your number** | First thing on Home, before anything is allowed: "Your number · 23 people have it" (shown only when someone has it). |
| **Allow** | One card explains it; Android then asks for contacts, call log and notifications. Each can be refused on its own; Home works with whatever is allowed. |
| **Updates, Calls, Contacts** | Three tabs at the bottom. Updates: your number, calls from numbers you haven't saved, and contacts whose count went up, with green dots for what's new. Calls: every call. Contacts: every contact, and "Only you have these" with Invite. |
| **Search** | "Whose number is this?": a mobile number searches everyone; letters search your own contacts. |
| **A number's card** | Who it is, "12 people have this number · You know 3 of them", the label, "+3 today", one main action (Ask someone in your contacts first; Invite for saved contacts not on WhoHas), Call back (after a call), the people you know (contacts first, then call log), "+9 more people you don't know", "What does this mean?". ⋮: Share number, Save to contacts, Block (people on WhoHas). |
| **After a call** | A notification: who knows the caller. Sound only for missed calls from unsaved numbers someone has. |
| **Evening note** | Once a day, only if something changed, counts only. |
| **Invite friends** | Share your link, or invite the people you call most. No reward. |
| **Settings** | Permissions, notifications, your name, your data and account (blocked people, download, sign out, delete), Help with How it works. |
| **Phone fix** | When calls keep being reached late, a one-tap link to the brand's Autostart or battery setting. |
| **Opt-out page** | `/out`: anyone can take their number out after an SMS code; a number with an account loses the account too, after a warning. |

**Wording** (all of it in [WHOHAS_COPY.md](WHOHAS_COPY.md)). The number comes first and "has" means contacts or call log, never "saved": "12 people have this number · You know 3 of them" on a card, "5 people have it · You know Priya and Meena" on a contact row, "Known to Priya, Meena and 10 more" on a call row and after a call. With nobody: "No one else on WhoHas has it yet" for a number on your phone, "No one on WhoHas has this number" otherwise. Counts never include the viewer and are never padded.

## 2. Matching rules

1. A user takes part (their numbers count for others) only after they allow it and accept the current consent text. Before that they see their own number's count and can search, by count. When the consent version changes, syncing stops until they accept again.
2. A user is a *holder* of a number when it is in their **contacts or call log** (v0.4; before, contacts only).
3. **Only people you know are named** (`HOLDER_VISIBILITY = known`). A holder is named only if their own number is in the viewer's contacts or call log. Everyone else is a count.
4. **Every number looks the same everywhere.** Its name, in order: the viewer's contact name (on the phone), the owner's own WhoHas name ("name they gave"), the popular name, the number. Left out of the owner name: blocked people, opted-out numbers, suspended people and people not taking part.
5. **Popular name**: the name at least 3 people saved for a number in their contacts, counted nightly from established accounts (7 days old, used in the last 30 days, syncing, not suspended). Names are compared loosely (case, spacing, dots). No word filter (decided 16 Nov 2026).
6. **Hide this number** (API only) takes a number out for that user in both directions.
7. **Opt-out** takes a number out for everybody: removed from every contact list and call log at once, never stored again, its popular name dropped, and it shows as "Not shown on WhoHas". A number with an account loses the account too.
8. **Blocking** works both ways: neither person is shown to the other anywhere. It never blocks calls.
9. **Suspension** (after a complaint to the help address, or a report) removes a person from every card and stops their syncs and lookups. A moderator can also **hold** a number's popular name: it's removed at once and never worked out again for that number.
10. **Inactive accounts** (not seen for 90 days) are deleted with everything they synced.
11. **Deleting an account** removes the profile, every synced number and saved name at once, then the sign-in account.

## 3. Real-time behaviour

### Budget

A phone rings for about 30 seconds, and the panel is only useful in the first two or three. The server's share of that is small; the network and the phone take most of it.

| Path | Target inside the API | Alert |
|---|---|---|
| Incoming-call lookup | p95 under 50 ms | p95 above 300 ms for 10 minutes |
| Search | p95 under 50 ms | same alert |
| Home cards (first 100) | p95 under 300 ms | Cloud Run p95 above 800 ms |
| One saved contact (changes) | p95 under 50 ms | — |

### Measured

`python -m tests.perf_check --users 50000` on 12 Nov 2026: 50,000 people, 11.5 million stored numbers, a 3 GB database on a 2-vCPU machine with Cloud SQL-like memory settings. Address books are shaped like real ones: about 60 other members, 20 locally popular numbers, a few numbers almost everyone has, 115 rare ones and 30 call-log-only numbers each.

| Path | v0.1 queries, median / p95 (ms) | Now, median / p95 (ms) |
|---|---|---|
| Incoming call, ordinary number | 4 / 7 | 4 / 7 |
| Incoming call, locally popular number | 4 / 6 | 3 / 5 |
| Incoming call, number almost everyone has | 15 / 21 | 4 / 6 |
| Search | 4 / 7 | 3 / 4 |
| Holder list, one page of 50 | 3 / 4 | 3 / 5 |
| Home cards, first 100 | 78 / 103 | 57 / 84 |
| One saved contact (changes) | not measured | 3 / 4 |
| Full sync, 2,000 new numbers | not measured | 160 / 213 |
| Full sync, nothing changed | not measured | 38 / 49 |

`python -m tests.concurrency_check`: 16 people syncing at once, all adding and removing the same 12 popular numbers. With the counts kept by triggers alone, 37 of 320 syncs (8 people) failed with deadlocks and the run took 166 s. With the ordered locking described below, all 960 syncs succeed in under 2 s, in three runs out of three, and the stored counts equal a recount.

### How it stays fast

- **Stored holder counts.** `number_stats` holds each number's holder count, kept by statement-level triggers in the same transaction as the change. A lookup reads one row instead of counting holders, so a number saved by half the city costs the same as one saved by two people. The nightly job recounts and repairs any drift, and logs a warning if it finds some.
- **No full-table reads.** The people a viewer knows are found through the users index, one of their contacts at a time. Left to itself, Postgres read the whole users table on every lookup and Home load, which grows with every sign-up; the forced plan grows only with the viewer's own address book.
- **Samples skip known people in SQL.** Card samples exclude holders the viewer already knows inside the query, so each card reads only the few index entries it shows.
- **A warm instance.** `api_min_instances` defaults to 1. The sign-in certificates are downloaded at start-up, before traffic arrives.
- **Database guard rails.** 10-second statement timeout and 5-second lock timeout for API connections, 15 seconds for an idle transaction. Six connections per instance, with a Terraform check that instances × pool stays under the database's limit.
- **No deadlocks on popular numbers.** A sync locks every holder count it will change in one statement, in number order, before it writes. Syncs that share numbers wait their turn instead of deadlocking. Any remaining lock conflict is retried automatically.
- **Fail fast.** When the database is busy, the API answers 503 with `Retry-After` instead of queueing until Cloud Run cuts the request off.

### What the Android app must do

1. **After a call, from the call log.** WhoHas is never the caller ID or phone app. Android wakes it when the call log changes (a content trigger, no extra permission); it also checks when the app opens, and when the server's wake-up push arrives. Each call is handled once, and only notified about within the hour. `POST /v1/lookup` with `context: "call"`.
2. **Notify sparingly.** Sound only for a missed call from an unsaved number that someone has (or that has a name); silent for answered calls and contacts whose list grew; nothing for calls made, hidden numbers, blocked people, unchanged contacts and unsaved numbers nobody has. Silent from 9 pm to 9 am. One notification per number.
3. **Send changes as they happen.** Watch contacts and the call log and send `POST /v1/me/numbers/changes`; contacts go with the name they're saved under. The whole address book goes on first setup and then once a week (the daily run sends only changes).
4. **Keep everything on the phone.** Cards, the last counts seen (for "3 more" in Updates) and blocked people are cached, so Home opens instantly and works offline.
5. **Report health.** Once a day: calls the phone saw and how many were checked in time, by brand (`POST /v1/me/health`). Offer the brand's phone-settings fix when calls keep being reached late.
6. **Respect `Retry-After`** on 429 and 503, and send `X-App-Version` (versionCode) on every request.

The app in `android/` does all six; `android/README.md` says how. It runs on Android 8 and later.

## 4. What is stored

| Data | Stored? | How long |
|---|---|---|
| The user's own phone number | Keyed hash only: `HMAC-SHA256(pepper, E.164)` | Until the account is deleted |
| Numbers from contacts and call log | Keyed hash only, with two flags: in contacts, in call log | Until removed from the phone, sync is turned off, or the account is deleted or inactive for 90 days |
| Contact names (how the user saved a number) | Encrypted (AES-GCM, key derived from `NAME_KEY` in Secret Manager, bound to the number) plus a keyed hash of the tidied name for counting | Like the number they belong to |
| Popular names | The encrypted name at least 3 established people saved for a number, and how many | Recomputed nightly; removed at once on opt-out |
| Holder count at the start of the day | One number per hashed number | Reset nightly; for the evening note |
| Push tokens | One per installed app | 60 days after the app last refreshed it |
| Phone health | Per day, brand and Android version: phones, calls seen, calls checked. No person or phone | 400 days |
| Holder count per number | A number per hashed number | Kept current; zero counts dropped nightly |
| Call times, durations, directions | **Never stored** (the phone reads them locally) | — |
| Display name | Yes, shown to others on cards | Until changed or the account is deleted |
| Lookups (who looked up which hashed number, how many holders came back) | Yes, for abuse investigation and to allow holder-list paging after a search | 180 days, including after account deletion |
| Opted-out numbers | Keyed hash only | Permanently, while the opt-out stands |
| Deleted-account record (allowance, today's quota use, suspension) | Keyed hash of the number, no address book or name | 30 days (90 if suspended) |
| Reports | Reporter, reported person's number hash and name at the time, reason, note | Until resolved and beyond, for repeat-offence checks |
| Popular-name holds | Keyed hash of the number and the moderator's reason | Until released |
| Blocks | Against the other person's number hash | Until removed or the account is deleted |
| Consent version and time | Yes | Until the account is deleted |
| Invite code | One random 8-character code per user | Until the account is deleted |
| Invite redemptions | Keyed hash of the number that joined, and who invited it | Permanently, so a number earns the reward once even after deleting and re-creating its account |

The pepper is in Secret Manager, not in the database. The API sees numbers in plain text while it normalises them; the hashing protects a stolen database copy, not a compromised server. Say exactly that in the privacy notice.

## 5. Numbers

Clients send numbers exactly as saved; only the server normalises them.

| Saved as | Becomes |
|---|---|
| `98450 31207`, `098450 31207`, `91 98450 31207`, `+91-98450-31207`, `00 91 98450 31207` | `+919845031207` |
| Digits in Devanagari, Kannada or other Indian scripts, full-width digits, non-breaking spaces, invisible direction marks | converted, then as above |
| `080 4123 5566` (landline with STD code) | `+918041235566` |
| `1800 425 1234` (toll-free) | `+9118004251234` |
| `+1 415 555 0100` (other countries) | `+14155550100` |
| `121`, `*121#`, landlines without STD code, extensions, text | skipped |

Search accepts only 10-digit mobiles (starting 6–9). Incoming-call lookups accept any valid number.

## 6. Limits

| Limit | Default | Why |
|---|---|---|
| Searches per day | 50 | Search reveals who knows someone, so it is metered |
| Incoming-call lookups per day | 100 | A client can claim a call happened; this caps abuse |
| Numbers per user | 10,000 | Normal address books are far smaller |
| New numbers | 3,000 at first, then 150 a day | Saving numbers is also a way to look them up. Numbers over the allowance are deferred to later syncs, never rejected |
| Names in one number's holder list | 500 | Caps what one lookup can reveal |
| Holder list access | Own cards, or numbers searched in the last 24 hours | A reference passed on by someone else is worthless |
| Hidden numbers / blocks | 1,000 / 500 | |
| Name changes | First change free, then one every 14 days | Stops hopping between impersonations |
| Reports | 20 a day | |
| Requests per person | 120 a minute; 20 a minute for syncs and Home | Stops a runaway client, per API instance |
| Holder count shown | up to 10,000, then "10,000+" | |
| Popular name | At least 3 people, accounts at least 7 days old and used in the last 30 days | A handful of fresh accounts can't name somebody |
| Invite window | An invite counts only in the new account's first 7 days, once per number ever | Stops old accounts and re-created accounts from farming rewards |

Days are counted in IST and quotas reset at midnight.

## 7. API

Base URL: the Cloud Run service URL. Every `/v1` call needs `Authorization: Bearer <Identity Platform ID token>` from phone (OTP) sign-in. Phone numbers only travel in request bodies, never in URLs.

| Method and path | Body | Returns |
|---|---|---|
| `POST /v1/me` | `{}` or `{"display_name": "Arjun Rao"}` | Profile. Creates the account straight after sign-in, with or without a name; calling again changes nothing. |
| `GET /v1/me` | | Profile: sync state, visibility, opt-out state, today's quota, limits |
| `PATCH /v1/me` | `{"display_name": ...}` | Profile. Choosing the first name is free; after a change, the next waits 14 days. |
| `PUT /v1/me/settings` | `{"evening_note": bool}` | Profile |
| `PUT` / `DELETE /v1/me/device` | `{"token": "<FCM token>"}` | 204. The app's push token. |
| `POST /v1/me/health` | `{"day", "brand", "android", "calls_seen", "calls_checked"}` | 204. Stored by brand only. |
| `GET /v1/me/data`, `GET /v1/me/export` | | What is stored, in counts; everything stored, as a file (saved names decrypted). |
| `DELETE /v1/me` | | `{"deleted": true, "sign_in_account_deleted": true}` |
| `PUT /v1/me/sync` | `{"enabled": true, "consent_version": "2026-11-13"}` | Profile. Turning off removes the user's numbers at once. |
| `PUT /v1/me/numbers` | `{"contacts": [...], "call_log": [...]}`; a contact is the number as saved or `{"number", "name"}` | `{"refs": {"+919845172046": "<ref>"}, "stats": {"numbers", "added", "removed", "skipped", "deferred"}}`. Full snapshot; replaces the previous one. |
| `POST /v1/me/numbers/changes` | `{"contacts_added": [], "contacts_removed": [], "call_log_added": [], "call_log_removed": []}`, up to 500 each | `{"refs": {...}, "stats": {"added", "removed", "skipped", "deferred"}}`. Needs one full sync first. |
| `GET /v1/cards?cursor=&limit=` | | `{"cards": [Card], "mine": Card, "taking_part": bool, "total_cards": n, "next": cursor}`. `mine` is the viewer's own number, on the first page only. Before the person allows anything (`taking_part` false), only `mine`, with counts. |
| `POST /v1/lookup` | `{"number": "98451 72046", "context": "search" \| "call"}` | Card fields plus `number`, `hidden`, `taken_out`, `quota`. Works before taking part (counts only). |
| `GET /v1/numbers/{ref}/holders?cursor=&limit=` | | `{"holders": [Holder], "next": cursor, "list_capped": bool}` |
| `GET /v1/hidden` | | `{"refs": [...]}` |
| `PUT /v1/hidden/{ref}` / `DELETE /v1/hidden/{ref}` | | 204 |
| `GET` / `PUT` / `DELETE /v1/optout` | PUT: `{"delete_account": bool}` | GET `{"opted_out", "has_account"}`; PUT `{"opted_out": true, "account_deleted": bool}`, or 409 `account_exists` when the number has an account and `delete_account` isn't true. Needs only a verified sign-in, no profile. |
| `POST /v1/reports` | `{"user_id", "reason", "note"}` | 204. Kept for moderation; the app no longer offers it. |
| `GET` / `POST /v1/blocks`, `DELETE /v1/blocks/{id}` | `{"user_id": "<owner id>"}` | Blocks list `[{"id", "name", "ref"}]` / `{"id", "name"}` / 204 |
| `GET /v1/me/circle` | | `{"members": n, "member_refs": [...], "goal": 20, "invite": {"code", "link", "joined", "joined_refs"}}`. `member_refs` lets the phone leave members out of the invite list and hide Invite on their cards. |
| `POST /v1/me/invite/redeem` | `{"code": "ABCD2345"}` | `{"redeemed": true}`. Sent once by a new user's app after sign-in. No reward. |
| `GET /i/{code}` | | No sign-in. A page: "Tarun invited you to WhoHas", a Google Play button carrying `referrer=invite%3D<code>` on Android, "on Android for now" on iPhone. |
| `GET /out` | | No sign-in. The opt-out page: SMS sign-in (Identity Platform web SDK), then `/v1/optout`. Warns first when the number has an account. |
| `POST /internal/push`, `/internal/push/evening`, `/internal/push/wake` | | Cloud Scheduler only (OIDC): due silent refreshes every minute, the evening note at 7:30 pm, wake-ups at 10 am, 2 pm and 6 pm IST. |
| `GET /healthz`, `GET /readyz` | | Liveness; readiness including the database |

A **ref** is an opaque 43-character handle for a hashed number. The sync response maps each of the user's numbers to its ref, so the app can show its own contact name on a card.

**Card**

```json
{
  "ref": "Hq2...", "in_contacts": true, "in_call_log": false,
  "total": 122, "total_is_lower_bound": false,
  "known":  [{"id": "6f1c…", "name": "Ravi Kumar", "known": true, "contact_ref": "Zk9..."}],
  "others": [],
  "more": 121,
  "name": "Kiran Gowda", "popular_name": "Kiran Electricals",
  "on_found_with": true, "owner_id": "2b7e…"
}
```

`known` lists every holder the viewer knows (their number is in the viewer's contacts or call log); the phone shows each under its own saved name via `contact_ref`. `others` stays empty with visibility `known` (with `everyone` it is a short sample of the rest). `more` is how many are only a count. `name` is the owner's own WhoHas name ("name they gave"), `popular_name` what at least 3 established people saved ("popular name"); both on cards, search and calls alike. `on_found_with` and `owner_id` say whether the owner takes part (no Invite; Block possible). Labels are worked out on the phone from `total`: Popular number 10 to 49, Highly popular number 50 and up.

**Errors** always look like `{"error": {"code": "...", "message": "...", "details": {...}}}`. The message can be shown to users.

| Status | Code | When |
|---|---|---|
| 401 | `unauthenticated` | Missing or expired token |
| 401 | `reauth_required` | The account was deleted after this sign-in; sign in again with a fresh OTP |
| 403 | `account_suspended` | Suspended after reports |
| 404 | `not_registered` | Signed in but no profile yet; call `POST /v1/me` |
| 404 | `not_found` | Unknown ref, person or block, or a holder list the viewer may not page |
| 404 | `invite_not_found` | No such invite code |
| 409 | `invite_rejected` | `details.reason`: `own_invite`, `too_late` (account older than 7 days) or `already_used` (this number already joined through an invite) |
| 409 | `sync_required` | Sync is off |
| 409 | `consent_required` | Consent version missing or out of date; `details.current_consent_version` has the version to show |
| 409 | `full_sync_required` | Changes sent before the first full sync |
| 409 | `account_exists` | Opt-out of a number with an account, without `delete_account` |
| 426 | `update_required` | `X-App-Version` below `MIN_APP_VERSION` |
| 409 | `limit_reached` | Too many hidden numbers or blocks |
| 413 | `body_too_large` | Request over 1.5 MB |
| 422 | `invalid_number` / `invalid_request` / `name_not_allowed` | Bad input; `details.fields` lists the problems |
| 429 | `quota_exceeded` / `name_change_too_soon` / `rate_limited` | Over a limit; `Retry-After` where it applies |
| 503 | `unavailable` | Busy or restarting; retry after `Retry-After` |

## 8. Operations

- **Nightly maintenance job** (03:17 IST): deletes inactive accounts (at most 5% of accounts per run, with a warning if the cap is hit), retries failed sign-in deletions, purges lookups older than 180 days and expired deleted-account records, recounts holder counts, works out popular names, starts a new day for the evening note, and purges old push tokens and health rows.
- **Push jobs** (Cloud Scheduler, `push_mode = "fcm"`): silent refreshes every minute, the evening note at 19:30 IST, wake-ups at 10:00, 14:00 and 18:00 IST.
- **Phone health**: `--args=-m,foundwith.admin,health,14` prints, per brand, the share of calls the app checked in time over the last 14 days. Strict brands (Xiaomi, Vivo, Oppo, Realme, Tecno…) are expected to be lower until people do the Autostart step.
- **Moderation**: complaints come to the help address and name a number. The moderator hashes it on their own machine (`python -m foundwith.admin hash <number>`, with `NUMBER_PEPPER` from Secret Manager in their shell; the job never gets the pepper), then runs one-off executions of the same job: `number <hash>` (account, holder count, popular-name state), `suspend-number <hash> <reason>`, `hold-name <hash> <reason>`, `release-name <hash>`. Also `reports`, `suspend-report`, `dismiss-report`, `suspend`, `unsuspend`, `health`, and `pilot [days]`: the match rate (calls and searches where someone on WhoHas has the number) and the people each person brings with their link.
- **Alerts**: API down, server errors, slow lookups (p95 above 300 ms), slow API overall, database CPU.
- **If the pepper leaks**: every stored number can be recovered by brute force, because there are only about a billion Indian mobiles. Treat it as a breach: report it, generate a new pepper, deploy it to the API, and delete every row of `user_numbers` (with DELETE, not TRUNCATE, so the counts follow). Accounts re-link to their own number at their next sign-in, and every phone's daily full sync rebuilds its address book under the new pepper. Keep the old pepper only to check opt-outs, because those can't be rehashed without the plain numbers. Blocks and deleted-account records keyed by the old pepper are lost.

## 9. Open items before public launch

1. **Legal review under the DPDP Act**, before anyone outside the test group: v0.4 stores contact names (encrypted) and counts call logs, which reverses two earlier promises; names now show on search (a reverse lookup, as Truecaller does); and anyone whose number is in your call log can see your name next to numbers you both have. The consent text, privacy notice and Play Data safety form must say all of this. The substantive obligations apply from 13 May 2027.
2. **Unfiltered popular names can work as spam labels** ("Fraud", "Loan recovery"). TRAI's TCCCPR regulation 34A covers call apps that offer spam labels or reporting. Ask the lawyer. Today's remedy is `hold-name` after a complaint; if the lawyer asks for more, the smallest fix is a short word list checked when names are stored (the API has the name key; the nightly job doesn't need it).
3. **Google Play.** Ship the `contactsOnly` build first (no Calls tab, no after-call notifications). Submit `full` (call log) only after Play approves the Permissions Declaration. The app targets API 36, shows the Allow card before Android's questions, and links the privacy policy in Help (`foundwith.privacyUrl`, required for release builds). Play also needs an account-deletion page (`/out` deletes accounts too) and a test sign-in for its reviewers.
4. **Real phones.** The after-call trigger and the server wake-ups are untested on Xiaomi/Redmi, Vivo, Oppo/Realme/OnePlus, Tecno/Infinix, Samsung and Motorola. Test one phone per brand, Android 10 to 16, and watch `admin health`.
5. **Demand test** with an honest consent screen and a seeded group of 30–50 people who know each other. Set `POPULAR_NAME_MIN_ACCOUNT_DAYS=0` for it, since every account is new.
6. **Opt-out page set-up**: the Firebase Web API key in Terraform (`firebase_web_api_key`), and the page's domain added to Identity Platform's authorised domains.
7. **One account on two phones.** Each phone's full sync replaces the server's copy with its own address book. Fine while address books are synced through Google Contacts.

## 10. Changes

### v0.4.1 (16 Nov 2026)

- Moderation by number for complaints to the help address: `hash`, `number`, `suspend-number`, `hold-name`, `release-name`. Migration 0006 adds `popular_name_holds`; the nightly job skips held numbers.

### v0.4 (16 Nov 2026)

- Call logs count in "has this number" and in the stored counts (migration 0005 rebuilds them).
- Contact names: sent with contacts, stored encrypted with a keyed hash; popular names worked out nightly from established accounts; shown with a "popular name" tag. The owner's own name shows on search too ("name they gave").
- Cards and lookups: `popular_name`, `on_found_with`, `owner_id`; lookups: `taken_out`. Labels move to the phone.
- Sign-in creates the account without a name; the first name doesn't start the 14-day wait. Cards and search work before taking part (own count first).
- Opt-out deletes the number's account too, after a warning (`account_exists`).
- Push: device tokens, silent refreshes, the evening note (counts only) and wake-ups; `PUT /v1/me/settings`.
- `POST /v1/me/health` and the `health` admin command. `X-App-Version` and `MIN_APP_VERSION` (426 `update_required`).
- Web pages: `/i/{code}` is now a page; new `/out`.
- Removed: the invite reward, the weekly search count, server-side labels, the caller-ID role and panel in the app.
- New secret `NAME_KEY` (Terraform creates it); consent version 2026-11-16.

### v0.3 (13 Nov 2026)

- Home starts with the viewer's own number (`mine` in the cards response), then circle progress until 20 people they know are on WhoHas.
- Invite links (`/i/{code}` to the Play Store with an install referrer), redemption once per number in a new account's first 7 days, and 20 more searches a day for both sides (inviter capped at 100). Search limits include the bonus.
- Invite screen on the phone: contacts not on WhoHas, ranked by calls, then favourites, then name; one tap opens WhatsApp or SMS with the invite typed in. Nothing is ever sent automatically.
- Share a number from its card: saved name, number, counts and the invite link.

### v0.2 (12 Nov 2026), from v0.1

- Visibility switch (`everyone` / `known`); opt-out for anyone; reports, blocks, suspensions and admin commands; display-name rules.
- New-number allowance replaces the hard daily cap: numbers over it are deferred, not rejected. Holder-list paging limited to own cards and recent searches, capped at 500 names.
- Lookup record, deleted-account records, inactive-account deletion, consent-version enforcement.
- Stored holder counts, the changes endpoint, no full read of the users table on lookups, faster card samples, start-up warm-up, a warm instance by default, database timeouts, automatic retry on lock conflicts, and a lookup-latency alert.
- The maintenance job can now run admin commands (its command and arguments were one fixed list, so `--args` couldn't replace them).
- Number parsing handles Indian-script digits and invisible characters.
- Call lookups default to 100 a day (was 300).

## v0.5 (17 Nov 2026): WhoHas

- **Name.** Found With is now WhoHas in everything people see (app, notifications, pushes, web pages). Internal names (package `app.foundwith`, database, service names) stay.
- **Tell.** `POST /v1/tells {"number": "+91…"}` records that the signed-in person told one of their contacts about WhoHas (mobile numbers only; your own is ignored; one row per teller and number, newest wins; kept 90 days, migration 0007). `GET /v1/me` now returns `invited_by` (up to 3 first names of people who told this number, newest first; "A friend" when none of them has a name) and `invited_by_count`. Blocked people either way are left out.
- **Cards** carry `known_count` (everyone the viewer knows who has the number; `known` lists at most 100) and `popular_name_people` (how many people have the popular name for it).
- **Daily summary** text: "1 more person has your number. 3 numbers on your phone got more people." Title "Today on WhoHas".
- **Sign-in** (app side): only a SIM in this phone. The code is accepted only when this phone reads the SMS itself; typed codes only in test builds and for the Play review number (`-Pwhohas.reviewNumber=+91…`, a Firebase test number).
