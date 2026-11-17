# WhoHas: the final design (v0.4, 16 Nov 2026)

> **Renamed WhoHas on 17 Nov 2026.** The words on every screen are now in [WHOHAS_COPY.md](WHOHAS_COPY.md), which replaces the wording below ("Found with …", "popular number", "name they gave"). The screens are on the WhoHas screens design canvas.

What the Android app shows and says, screen by screen. Agreed with Y over the design rounds and built as written. Rules that need numbers live in code and are unit-tested (`android/app/src/main/java/app/foundwith/core/`); the server's side is `docs/SPEC.md`.

## What it is

For any phone number, WhoHas shows who on WhoHas has it in their contacts or call log. People you know are named; everyone else is a count. English only. Android only for now.

WhoHas is **never** the phone's caller ID or phone app. It never draws over other apps and never asks for the Phone permission. It tells you about a caller **after** the call, from the call log, the way Truecaller works on phones where it isn't the default app.

## One rule for every number

Every number looks the same wherever it appears (Home tabs, search, after a call, a notification):

| Part | Rule |
|---|---|
| Name | Your own contact name first. If you haven't saved it: the name its owner gave, tagged **name they gave**. Then the **popular name** (what at least 3 established people saved), tagged **popular name**. Otherwise the number itself. |
| Line | "Found with Ravi Kumar, Priya Sharma and 3 others": people you know by name, the rest as a count. Counts never include you. |
| Empty | "Only you have this number" for a contact nobody else has. "Found with no one yet" for anything else. |
| Label | **Popular number** at 10–49 people, **Highly popular number** at 50+. Nothing under 10. Worked out on the phone. |
| Today | A green line when the count went up today: "Today: 3 more people · now a Popular number". |
| What it means | Tapping the line opens a sheet explaining the count and the label. |

## Screens

**Sign-in.** Welcome ("Never messages or calls your contacts.", an example card), then Android's own number picker (both SIMs). If the picker shows the wrong number or none: "Type your number". The SMS code fills in by itself. Separate screens for a wrong code, a code that didn't arrive (resend timer), too many tries and no internet. A person who came from an invite sees "X invited you". Sign-in creates the account with no name; the name is asked for later, on Home, and can be skipped.

**Home.** "Whose number is this?" search at the top. The first time: your own number's card and the **Allow** card (contacts, and in the full build the call log), shown before Android's questions. Then three tabs at the bottom:

- **Updates**: your number, calls from numbers you haven't saved, and contacts whose count went up ("Now found with 12 · 3 more"). Green dots clear by themselves once seen.
- **Calls** (full build only): recent calls, each with its line.
- **Contacts**: your contacts A–Z with their lines.

Banners: offline, notifications off, and the phone fix (only after calls keep being reached late). A "Your name on WhoHas" card asks once, with Not now.

**Search.** Typing a name filters contacts; a full mobile number shows its card; an incomplete or non-mobile number says so.

**A number's card.** The name, line, label and today's line. Then the people you know who have it, in this order: people you talk to often (3+ calls), favourites, contacts A–Z, then call-log-only. Buttons, each only when it applies:

- **Ask** next to a person: only on numbers you haven't saved. Opens WhatsApp with the message typed in.
- **Invite**: only on saved mobiles whose owner isn't on WhoHas.
- **Call back**: only after a call from a number you haven't saved.
- **⋮ menu**: Share this number; Save to contacts (if not saved); Block (only for people on WhoHas; blocks the person, and their number never shows them to you again).

Blocked and taken-out numbers have their own states.

**Invite friends.** Share your link, then the people you call most who aren't on WhoHas, each with Invite. No reward.

**Settings.** Permissions, Notifications (the evening note on or off, Android's channel settings), Your name, Your data (download, blocked people, sign out, delete account), Help (email), How it works, Phone fix.

**Phone fix.** For Xiaomi, Vivo, Oppo, Realme, OnePlus, Tecno, Infinix, Huawei, Samsung and Asus: one tap to the brand's Autostart or battery page, then Battery settings, then Done.

**Gates.** "Update WhoHas" when the server says the app is too old. "Your account is suspended" with sign out and delete.

**Removed during design**: the visibility switch, Leave out, Take out (in the app), Report name, the incoming-call panel, the caller ID role, the invite reward, Hindi and other languages.

## Messages

They speak to the person who receives them, never greet by name (names are saved as "Dr. Anand", "Plumber Raju"), put the number on its own line, and never say who has a number.

- **Ask**: "Hi, do you know whose number this is?\n+91 98450 31207" (+ "It shows as Raju Plumber." when there's a popular name). When the owner is known: "Hi, do you know Raju?\n+91 …".
- **Invite**: "Hi, I'm using WhoHas. 3 other people on WhoHas have your number too. See which of them you know:\n<link>". The count only when someone besides the sender has the number.
- **Share this number**: the name, the number, "N other people on WhoHas have this number too", the link.

## After a call

| What happened | Notification |
|---|---|
| Missed call from a number you haven't saved that someone has, or that has a name | **With sound** |
| Answered call from such a number; a call from a saved contact whose count went up | Silent |
| Calls you made, hidden numbers, blocked people, saved contacts with no change, unsaved numbers nobody has | None |
| Anything between 9 pm and 9 am | Never a sound |

Repeat calls update one notification ("2 missed calls: …"). Calls found more than an hour late aren't notified; they show in Calls and count as late for the phone fix and the brand health report.

## From the server

- **Evening note** at 7:30 pm, "Today on WhoHas", counts only: "3 numbers on your phone are found with more people today. Your number: 1 more person." Sent only when something changed; switchable in Settings.
- **Wake-ups** three times a day, so phones that stop apps in the background still catch up on calls.
- **Opt-out page** (`/out`): anyone can take their number out without the app; if it has an account, it is deleted too, after a warning.
- **Invite page** (`/i/<code>`): "X invited you", a Google Play button; on iPhone, "Android for now".

## Release order

The **contactsOnly** build goes to Google Play first (no call log, no Calls tab, no after-call notifications). The **full** build follows once Play approves the call-log declaration.
