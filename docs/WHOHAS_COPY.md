# WhoHas: the words on every screen

Approved by Y on 17 Nov 2026 (with the starting screens below). Replaces every sentence in the app, the notifications, the messages people send and the two web pages.

**Updated 22 Nov 2026 to the final design (49 screens, as built):** copy any number, press and hold for Copy number / Search in Truecaller / Call back / Open card, levels for your own number (Not popular, Popular, Highly popular) with tags on other numbers, Tell (one person) vs Share in group (a group), notifications for every new or changed thing (no evening summary), and Delete my account in the app and on the web.

**Card order, 22 Nov (Y):** the count first, then who you know, inside the same card; no big "Ask who this is" button (Ask is on each person); a copy icon beside the number instead of a Copy number button; Truecaller and Call back in one row; the level box below them as "How popular it is".

Updated the same night after talking it through: "has" instead of "saved" (call logs count too), contacts before call-log people on a card, honest label text, the **Let them know who has their number** list (Share button), a **Share in group** button on the top line, counts that are always real, sign-in with a SIM in this phone only, charcoal and gold with Atkinson Hyperlegible Next, Updates that list everything found, three tabs (Updates, Contacts, Call log) with the call log's symbols, and Indian numbers only. The screens for all of this are on the WhoHas screens design canvas.

## The rules

1. **The number comes first.** "12 people have this number", not "Found with 12 people".
2. **One idea per line.** If a line needs "and" twice, split it.
3. **Say what to do, not how it works.** Explanations live behind "What does this mean?", never in the way.
4. **Say how it helps first.** Every screen that asks for something says what the person gets: "Allow contacts. WhoHas will show names of people you know."
5. **Indian English, plain words.** "Mobile number", "contacts", "SMS", "Settings", "WhatsApp". No "awesome", no "please", no exclamation marks except in messages people send.
6. **Same words everywhere.** "Have this number" for people; "you know" for people in your phone; "on WhoHas" for people using the app.
7. **Counts are always real.** No count is ever rounded up, padded or made up, on screen or in a message. When a count is low, change the hook, not the number.

## Words we use

| Idea | Say | Don't say |
|---|---|---|
| Someone has a number in their contacts or call log | **has this number** | found with, saved, holds |
| Your number is in someone's contacts or call log | **has your number** | saved you, saved your number |
| Someone in your contacts or call log | **someone you know** | known person, your circle |
| Using the app | **on WhoHas** | taking part, registered |
| Name the owner typed in | **Their own name: Raju Sharma** | name they gave |
| Name 3+ people have for a number | **5 people know it as "Raju Plumber"** | popular name, saved as **Off since 23 Nov 2026** (Google Play's contacts policy): not shown, and saved names are no longer sent. |
| Fewer than 10 people | **Not popular** (only on cards and on your own number) | unpopular, low |
| 10–49 people | **Popular** · often a business, or someone who calls a lot of people | "usually a shop" |
| 50+ people | **Highly popular** · often a business, or someone who calls a lot of people | viral, famous |
| Telling one person | **Tell Ravi on WhatsApp** → Told | Share (that's for a group) |

**No bare numbers in one-line summaries.** Someone you know only from your call log, with no WhoHas name, still counts ("You know 1 of them") and is listed on the card with their last call, but is never named as a number in "You know …" or "Known to …".

**No "known as" names (23 Nov 2026).** Google Play's contacts policy doesn't allow showing names other people saved for a number without that person's say-so, so "People know it as …" is off, and the names saved in contacts stay on the phone. Names shown are the person's own WhoHas name ("Their own name") and the names in your own phone.

**"Has" means contacts or call log.** WhoHas counts a number when it is in someone's contacts *or* their call log, so never say "saved" for that. Use "save" only for Android's own **Save to contacts** button. Names come only from contacts (a call log entry has no name), so for names say "know it as".

## The look (Facebook style, approved by Y, 23 Nov)

Light, like Facebook. **A white page (Y, 23 Nov)**, with lists full width and a thin line between rows; boxes (a number's card) keep a hairline edge. (Was: page #F0F2F5 with white cards.) text #050505, grey text #65676B, blue #0862F5 for buttons, links, "Known to …", the count on your number and the new dot (Facebook's blue, a shade deeper so blue text on the grey page passes 4.5:1). Secondary buttons (Truecaller, Call back) are grey #E4E6EB with black text. Buttons have 8 dp corners. The Updates count is a red badge. Red #D21F3C only for missed and rejected calls and for warnings, green #188038 only for received calls, joins and a count going up. Every text colour is at least 4.5:1.

**Dark mode follows the phone.** When the phone is in dark mode: one dark page #18191A (raised bits #242526), text #E4E6EB, grey text #B0B3B8, blue #5AA2FF with dark text on blue buttons. Same layout. The website pages (privacy, terms, delete, invites) follow the same two looks.

Icon: the three people in white over a light blue stroke, on blue. Not Facebook's "f" or any Facebook logo or name.

Font: **Atkinson Hyperlegible Next** (made so 1, l and I, and 0 and O, never look alike), bundled in the app: Regular, SemiBold and Bold only, every digit the same width. Body text 16, small text 13, nothing under 12; sizes follow the phone's font setting. Section labels in normal case.

**Every kind of number (Y, 23 Nov).** WhoHas matches every number in contacts and call logs: Indian mobiles and landlines, toll-free (1800, 1860), short and service numbers (121, 198, 56161), landlines saved without their STD code, and other countries' numbers written with + or 00 (+971 50 123 4567). Shown as "+91 98450 31207", "1800 425 1234", "121", "+971501234567". The first full number in a field counts ("tel:", "(home)", ",204" and a second number are ignored), and two numbers are never joined into one; a short number counts only when it is the field's one number, and USSD codes (*121#) never. Sign-in, Share, Tell and Let them know stay mobile-only (an SMS or WhatsApp must reach the number). (Was: Indian numbers only.)

---

## Starting screens and sign-in

| Where | Now | New |
|---|---|---|
| Welcome title | Found With | **WhoHas** |
| Welcome headline | See who has your number. | **Unknown number calling?** See which of your people know it. |
| Benefit 1 | (example card) | **Know your callers** · After a call, see which people you know have that number. |
| Benefit 2 | | **Check any number** · See how many people have it, and ask someone you know. |
| Benefit 3 | | **See who has your number** · Find out how many people have your number, and when it goes up. |
| Contacts-only build | | Example: +91 98450 77120 · You searched this number · Known to Priya, Meena and 10 more · Headline line: **Search it. See which of your people know it.** · **Check any number** · Search a number that called you. See which people you know have it. · **See who has your contacts** · Each contact shows how many people on WhoHas have their number. · (Benefit 3 as above) |
| Trust line | Never messages or calls your contacts. | WhoHas never messages or calls anyone. |
| Buttons | Continue · Type your number | **Get started** (opens Android's number picker, which lists the SIMs in this phone) |
| Under Get started (23 Nov) | | By continuing, you agree to the **Terms of use** and **Privacy policy**. (both links) |
| Number picker | (Android's own) | Android's sheet with SIM 1 and SIM 2. Picking one goes straight to Enter the code while the code is sent. **None of the above** opens the number screen. |
| Invited chip | A friend invited you | Priya invited you (name when known, else "A friend invited you") |
| After sign-in, invited | (none) | **Priya and 2 others invited you** · 8 people on WhoHas have your number (names everyone who tapped Share for this number) |
| After deleting | Your account is deleted. | A screen of its own: ✓ **Your account is deleted** · Your number, name, contacts and call log numbers are gone from WhoHas. · **Done** (contacts-only: Your number, name and contact numbers are gone from WhoHas.) |
| Number screen (only after None of the above) | Your mobile number | **Your mobile number** · We'll send an SMS with a 6-digit code. · Send code |
| Wrong number | 10 digits, starting with 6, 7, 8 or 9. | Enter a 10-digit mobile number starting with 6, 7, 8 or 9. |
| Code screen (22 Nov) | Enter the code · Sent to … · Change | **Enter the code** · Sent by SMS to +91 98450 31207 · **Change** · six boxes, keyboard open · ⟳ Waiting for the SMS. The code fills in by itself. · Send again in 0:25 |
| Code filled in | | the boxes fill with the code · ⟳ Signing you in… (no tap needed) |
| No code after 10 seconds | | blue box: ✉ **Check your messages for the code** (the boxes stay ready to type) |
| Wrong code | That code doesn't match. Check the SMS. | Wrong code. Check the SMS and try again. (red, under the boxes) |
| Resend | Resend in 0:25 · Resend code | Send again in 0:25 · Send code again |
| Too many tries | Too many tries · For safety, wait a while and try again. | Too many tries · Wait 15 minutes and try again. |
| Couldn't send | We couldn't send the code. Try again. | Couldn't send the code. Try again in a minute. |
| No internet | No internet · Connect and try again. | No internet · Connect and try again. |

**The code fills in by itself (Y, 22 Nov).** No "Allow WhoHas to read this message?" prompt. Google reads the code silently when it can (Firebase's SMS Retriever); otherwise the keyboard or Google's autofill offers the code from the SMS (the field is marked as an SMS code); otherwise the person types it, helped by the blue "Check your messages for the code" box after 10 seconds. Typing is allowed for everyone, so sign-in no longer proves the SIM is in this phone (the 17 Nov rule): the SMS to that number is still needed.

## First time on Home

| Where | Now | New |
|---|---|---|
| Your number (before allowing) | Your number · Found with 23 people | Your number · **23 people have it** · Allow contacts to see who they are. · then your level (tag and its line). At 0: No one on WhoHas has your number yet · Not popular |
| Allow card (Y's words, 17 Nov) | See who knows your contacts · Allow contacts, and each one shows who else has the number. · Allow | **See who has each number** · Allow contacts and call log. Then you can: · ✓ Find out who has your number · ✓ See who has each number on your phone · ✓ Know when something new happens or changes · ✓ After a call, get a notification if someone has the caller's number · **Allow** |
| Data notice (23 Nov; Google Play's prominent disclosure, two lines right above the button, no box) | | WhoHas sends your contact and call log numbers to its server to match them. People who have your number see your name. (Contacts-only: "your contact numbers") · Button: **Agree and allow** |
| Privacy line | Stored encrypted · How it works | No one sees your contact list. If a friend checks a number you have, they see your name. · How WhoHas works |
| Contacts-only build | (same card) | Allow contacts. Then you can: · ✓ Find out who has your number · ✓ See who has each number on your phone · ✓ Know when something new happens or changes |
| Checking | Finding who you know… | Checking your contacts… This takes a few seconds. |
| Name card | Your name on Found With · People who haven't saved your number see this name. | **Add your name** · People who don't have you in their contacts will see this name. · Save · Later |
| Notifications card | (banner) Notifications are off. You won't hear after calls. | **Turn on notifications** · Get told who knows a caller right after a missed call. · Turn on |
| First empty Updates | You're all caught up | **None of your numbers are with people on WhoHas yet** · When people you know join, every number in your contacts and calls that they have shows here, with who has it. · Share in group (contacts-only: "every number in your contacts") |

## Home

| Where | New |
|---|---|
| Top bar | **WhoHas** · *Live · just now* (green dot) · **⤴ Share in group** (solid blue, on every Home screen) · Settings |
| Offline | No internet · showing what we had at 9:40 pm |
| Search box | Search a number or name |
| Your number (22 Nov: fixed just above the tabs, the same on Call log, Contacts and Updates; a tap opens your own card) | 👤 **23** people have your number · **+1** (green, when it went up this week) · [Popular] (no arrow, the whole bar opens your card) · at 0: No one on WhoHas has your number yet · [Not popular] |
| Next level, under it (Y, 23 Nov: built) | a thin blue line (how far to the next level) · **2 more → Popular** · **⤴ Share** (Share in group). At 23: 27 more → Highly popular. At Highly popular: no line. |
| Your level (inside the number card) | **[Not popular]** Share WhoHas so people you know can find you. |
| | **[Popular]** More people means more callers you can recognise. |
| | **[Highly popular]** (tag only). No bar, no "N more people", no link and no pooling line in the card (Y, 22 Nov: they took too much room); "Share in group" is the top button. |
| Levels | Not popular: under 10 people · Popular: 10 to 49 · Highly popular: 50 and up. Your own number only; counts are real; never compared with named people. |
| The day you reach a level (top of Updates, one day) | **You're Popular now** · 10 people have your number. 40 more and you're Highly popular. · Share in group |
| Nobody yet | No one on WhoHas has your number yet · **[Not popular]** Share WhoHas so people you know can find you. |
| Press and hold any number (Updates, Contacts, Call log, search) | **+91 90080 11223** · Copy number · Search in Truecaller · Call back · Open card. A tap opens the card. |
| Copied | Android 13 and later show their own "Copied" box; older phones: Number copied. Always the plain 10 digits. |
| Notifications off | Notifications are off. You won't know who called. · Turn on |
| Late calls | Your phone stopped WhoHas. Last 3 calls weren't checked. · Fix |
| Tabs | **Call log · Contacts · Updates** (Y, 22 Nov). The app opens on Call log (contacts-only build: **Contacts · Updates**, opens on Contacts). Back from another tab goes to Call log. |
| Updates badge | A red count on the Updates tab: numbers that changed since you last looked at Updates (99+ above 99). Opening Updates clears it; those rows stay under New, light blue, for that visit. "Open Updates" and the level notifications open Updates. |
| Scrolling (22 Nov) | The WhoHas name and Share in group scroll away; the **search box stays pinned at the top** on every tab. Each tab keeps its own place. Tapping the tab you're on jumps to its top. |

### Updates: one list, latest change on top (Y, 23 Nov)

Every number from your contacts and calls that someone on WhoHas has, each once, in one list. The number that changed most recently is on top, so nobody has to look through Call log and Contacts for what's new. No "From your calls" or "Your contacts" sections and no See all: the whole list scrolls.

| Where | New |
|---|---|
| What moves a number up | More people have it · someone you know now has a caller's number · a contact joins WhoHas · a call from a number not in your contacts. (A call from one of your contacts is no news by itself.) |
| **New** (since you last looked at Updates; light blue rows, time in blue and the dot) | [call symbol] +91 90080 11223 · Missed call · today, 10:42 am · **Now known to Kiran Sir** · 10:42 am |
| | Priya Sharma · In your contacts · **Joined WhoHas · has your number** (green) · 9:05 am |
| Joined with your link (23 Nov) | Ravi Kumar · In your contacts · **Joined with your link · has your number** (green) · 1 more and you're Popular · **⤴ Share in group** (under New only). Someone not on your phone: a note on top, **Someone joined with your link** · 9 people have your number now · 1 more and you're Popular · Share in group (it counts in the red number on Updates). |
| | Raju Plumber · In your contacts [Popular] · **2 more people have it · now 14** · 8:30 am |
| **Earlier** (everything else, newest first) | [call symbol] +91 98765 43210 · Received · Mon, 6:15 pm · 4 min · **Known to Priya and 1 more** · Mon |
| | Kiran Sir · In your contacts · **6 people have it** · You know Priya (no time: nothing changed lately) |
| Order of numbers with no change lately | People you know have it first, then the most people |
| Time on the right | When the latest change happened: 10:42 am today, Yesterday, Fri, 3 Nov |
| First open | Nothing is marked New the first time; the list is still newest first |
| Updates badge | The number of rows under New (also as a red count beside **New**, and on the app icon through the notifications) |
| Call log and Contacts badges (Y, 23 Nov) | A red count on each tab too: how many numbers in that tab changed and haven't been seen yet (in Updates or on their card) |
| Marks in Call log and Contacts (23 Nov) | The same changed numbers are marked there too: light blue, the dot, and what changed (in Call log on the number's latest call). Call log stays by date, Contacts A to Z. |
| Opening a marked number | Its card shows what changed on top in light blue (**2 more people have it · now 14 · 4:30 pm**; **New ·** beside someone you know who now has it). After that the change is seen: the mark goes from all three tabs, the red count goes down by one, and its notification goes. Leaving Updates clears all the marks. |
| Nothing found yet | **None of your numbers are with people on WhoHas yet** · When people you know join, every number in your contacts and calls that they have shows here, with who has it. · **Share in group** |
| No counts line | Never "18 numbers are with people on WhoHas" (Y, 17 Nov) |

### Call log

Every call under its day (Today, Yesterday, Friday, 12 Nov), each with the phone's own call symbols.

| Symbol | Words |
|---|---|
| Phone with ✕, red | Missed call · 10:42 am |
| Phone with arrow in, green | Received · 9:15 am · 4 min |
| Phone with arrow out, blue | You called · 6:10 pm · 1 min |
| Phone with arrow out, grey | You called · not answered · 7:30 pm |
| Phone with a slash, red | Rejected · 8:02 am |
| Second line | Known to Priya and 2 more · 3 people have it · No one on WhoHas has this number |
| Hidden number | Private number · Can't check hidden numbers |
| Another country's number | +971501234567 · checked like any number (23 Nov) |
| A short code (121) | WhoHas can't check this number |
| Call log off | **See who has your callers' numbers** · Allow the call log. · Allow |

### Contacts

| Where | New |
|---|---|
| List | Everyone in your phone, A to Z, under letter headings |
| Row | Raju Plumber · **12 people have it · You know Priya and 2 more** |
| Nobody else has it | Suresh Bhaiya · No one else on WhoHas has it yet (no button on the row; the card has **Tell Suresh Bhaiya on WhatsApp**) |
| Tags on rows | **Popular** or **Highly popular** under the name, after "In your contacts" (so a long name is never cut short). No tag under 10 people ("Not popular" shows only on cards and on your own number). |
| Another country's number | checked like any number (23 Nov) |
| Contacts off | Contacts are off · Open Settings › Permissions › Contacts › Allow. · Open Settings |

### Search

| Where | Now | New |
|---|---|---|
| Name search heading | From your phone | Your contacts |
| No match | No one called that · Letters search your own contacts. Type a number to search everyone. | No contact called "Raju" · Type a number to search everyone. |
| Part of a number | Type the full number · Like 98451 72046 | Type all 10 digits · Like 98451 72046 |
| Checking | Checking +91 98450 31207… | Checking +91 98450 31207… |
| Daily limit | Today's search limit is over · Search again tomorrow. Calls and contacts keep updating. | Today's searches are over · Search again tomorrow. |
| No internet | No internet · Search needs the internet. Home still shows your last update. | No internet · Search needs the internet. |
| Any other number (23 Nov) | | A landline is searched as soon as its 10 digits are typed; a short number (121) or another country's (+971 50 123 4567) shows **121 · Search** and is looked up on the button, so a half-typed number never uses up a search. Not a number yet: **Type the number** · Like 98451 72046, 121 or +971 50 123 4567 |

## A number's card

Order on screen (22 Nov 2026): who it is, with a copy icon beside the number → one card: how many have it, then the people you know who have it → Truecaller · Call back → How popular it is → your calls with it → "What does this mean?". No big Ask button: Ask is on each person.

| Where | Now | New |
|---|---|---|
| Not-a-contact subtitle | (none) | Missed call · 10:42 am · not in your contacts |
| The number | | A copy icon beside the number (blue, "Copy number" for screen readers); tapping the number copies it too (the plain 10 digits). Android 13+ shows its own "Copied"; older phones: Number copied |
| Buttons under the card | | **Truecaller** (23 Nov: copies the number, then opens Truecaller straight on it: its "search with Truecaller" action, else its page for the number, else its dial pad with the number typed, else Truecaller itself; without Truecaller, truecaller.com search. Test builds show which in a short note: Opened Truecaller search / …) · **Call back** beside it after a call from a number not in your contacts |
| Owner's own name | Raju Sharma (name they gave) | **Their own name: Raju Sharma** (chip) |
| Name 3+ people have for it | Raju Plumber (popular name) | **5 people know it as "Raju Plumber"** **Off since 23 Nov 2026** (Google Play's contacts policy): not shown, and saved names are no longer sent. |
| Main line | Found with Priya, Ravi and 21 others | **12 people** have this number · **You know 3 of them** |
| You know none | Found with 12 people | 12 people have this number · You don't know any of them |
| Only you | Only you have this number | No one else on WhoHas has this number yet |
| No one | Found with no one yet | No one on WhoHas has this number yet |
| While looking it up | (said "No one…" until the answer came) | ⟳ **Checking…** (a card opened from a call's notification shows the notification's count straight away, with no second lookup) |
| Couldn't look it up | | **Couldn't check this number** · under the number: No internet. Connect and try again. / Today's searches are over. Try again tomorrow. (never "No one has it") |
| Your own number (23 Nov: what grows your count, built) | Found with … | **8 people have your number** · You know 2 of them · **+2 this week** · [Not popular] ━━━━━━░░ [Popular] · **2 more people and you're Popular** (at the top: Top level. 52 people have your number) |
| What grows your count (a box under it; each row only when it applies) | | **Ravi and Asha joined with your link** · Both have your number: +2 (only what WhoHas shows; else Each one who has your number adds 1.) · **38 people you call aren't on WhoHas** › (opens Invite friends; with no call log: 38 of your contacts aren't on WhoHas) · They likely have your number. Each one who joins can add 1. · **What Popular shows** · At 10 people, the Popular tag shows beside your number for everyone who has it. |
| Your own number, buttons | Invite friends | **⤴ Share in group** (blue) · **Invite friends** (outlined). No separate "How popular your number is" box any more. |
| How popular it is (section title, under the buttons) | Many people have this number | A box: This number **[Highly popular]** · You **[Not popular]** · **More popular than your number. 2 more people and you catch up.** / **Same level as your number.** / **Your number is ahead of this one.** |
| Today | Today: 3 more people · now a Popular number | **+3 today** |
| Asking (not a contact) | Ask (per person) | No big button: a small **Ask** on each person in the card (WhatsApp, else SMS). |
| Main action (contact not on WhoHas) | Ravi Kumar isn't on Found With yet · Invite | **Ravi Kumar isn't on WhoHas yet** · Their number is with 7 people on WhoHas. Let them know. (With nobody: Tell them about WhoHas, so you can ask each other about unknown callers.) · **Tell Ravi Kumar on WhatsApp** → Told (whole name, never the first word: "Dr." and "Plumber" don't stand alone) |
| People you know | People you know who have it | No title: inside the count card, under "You know 3 of them" · contacts first, then call log · first 3, then See all |
| Each person | Contact · In your call log | **In your contacts** · **In your call log · called you yesterday** / **you called on 12 Nov** (someone only in your call log shows their own WhoHas name, else their number; the last call helps you place them) |
| Rest | Also have it. They aren't on your phone, so you see only how many. | +9 more people you don't know |
| See all | See all 14 | See all 14 |
| Menu | Share this number · Save to contacts · Block Ravi | A contact on WhoHas: Share number · Block Ravi. A caller not in contacts: Share number · Save to contacts. |
| Block dialog | Block Ravi? · You and Ravi won't see each other on Found With. Calls and messages aren't affected. | **Block Ravi?** · You won't see each other on WhoHas. Calls and messages still work. · Block · Cancel |
| Blocked | Blocked · You and Ravi don't see each other on Found With. · Unblock | Blocked · You don't see each other on WhoHas. · Unblock |
| Taken out | Not shown on Found With · The owner took this number out. | **Hidden by the owner** · They removed this number from WhoHas. |
| Your calls with it | (none) | **Your calls with this number** (or "with Ravi Kumar"): every call, newest first, each with its symbol · Missed call · Today, 10:42 am · Received · Mon, 4:05 pm · 2 min · See all 9 |
| Link | What it means (info icon) | What does this mean? |

### "What does this mean?" sheet

> **12 people have this number.** It's in their contacts or call log.
> **You know 3 of them.** They're in your contacts or call log, so you see their names.
> **9 you don't know.** You only see how many.
> **Not popular.** Fewer than 10 people have it.
> **Popular.** 10 to 49 people have it. Often a business, or someone who calls a lot of people.
> **Highly popular.** 50 or more people have it. Often a business, or someone who calls a lot of people.
>
> [OK]

## Messages people send

Opened in WhatsApp with the text typed in; the person sends it themselves.

| Message | New text |
|---|---|
| Ask (owner unknown) | Hi, do you know whose number this is?<br>+91 98450 77120 |
| Ask (with a known name) | Hi, do you know whose number this is?<br>+91 98450 77120<br>Some people know it as "Raju Plumber". (off with "known as" names) |
| Ask (owner gave a name) | Hi, do you know Raju Sharma?<br>+91 98450 77120 |
| Share / Invite (2+ others have their number) | Hi! I'm on WhoHas, and 7 more people there have your number. See who they are:<br>{link} |
| Share / Invite (1 other) | Hi! I'm on WhoHas, and 1 more person there has your number. See who:<br>{link} |
| Share / Invite (no one else) | Hi! I'm on WhoHas. If an unknown number calls either of us, we can see if the other knows them. Join here:<br>{link} |
| Share a number | +91 98450 77120<br>5 people on WhoHas have this number. See who:<br>{link} |
| Share my link | Hi! I'm on WhoHas. If an unknown number calls either of us, we can see if the other knows them. Join here:<br>{link} |

## Notifications

**As fast as possible, with no extra permission (Y, 23 Nov).** While WhoHas is running it watches the call log itself and checks a call about half a second after it ends; otherwise Android wakes it when the call log changes (asked for within 2 seconds). Only the caller's contact is looked up, not the whole address book. Every call notification pops up on the screen; only a missed call from a number not in your contacts makes a sound. Phones report, by brand only, how many calls were told within 10 seconds and within a minute.

**A notification for every change (Y, 23 Nov).** Each change gets its own notification, popping up without sound: a contact joined, more people have a number, someone you know now has a caller's number, more people have your number. A later change to the same number replaces its notification. Four or more stack under WhoHas (Android does this). Tapping one opens that number's card. Nothing pops up while WhoHas is open; the change goes into Updates with the count.

Rules (Y, 21–22 Nov): a call (received, missed or dialled) is told only when someone on WhoHas has the number and something is new or changed. A number nobody has stays silent. A repeat call from the same number is told again only when more people have it. Only a missed call from a number not in your contacts makes a sound, and never from 9 pm to 9 am. A dialled call is told quietly after it ends. Changes with no call are merged into one notification. Nothing is shown while WhoHas is open; Updates shows it. There is no evening summary.

| Kind | Title · text · buttons |
|---|---|
| Missed call, not a contact | **Missed call: +91 98450 77120** · Known to Priya and 2 more (or: 4 people on WhoHas have this number; Their own name: Raju Sharma · …; People know it as "Raju Plumber" · …) · Copy number · Truecaller |
| Received | **Call from +91 80950 66231** · 4 people on WhoHas have this number · Copy number · Truecaller |
| Dialled | **You called +91 97311 20456** · Known to Priya and Meena · Copy number · Truecaller |
| Repeat missed calls | **2 missed calls: +91 98450 77120** |
| Every other kind of call (Y, 24 Nov: every call counts) | **Blocked call: +91 16001 13814** · **Voicemail: …** · **Call from … · answered on another device** · **Call: …** (a kind Android doesn't name). Same lines as above, without sound. Call log: Blocked · Voicemail · Answered on another device. |
| A contact whose count went up | **Missed call: Raju Plumber** (or Call from / You called) · 3 more people have Raju Plumber's number · now 12 |
| Friend joined | **Priya joined WhoHas** · Has your number |
| Joined with your link (23 Nov, on the Your level channel, silent) | **Ravi Kumar joined with your link** (or Someone joined with your link) · Has your number. 9 people have it now: 1 more and you're Popular. (when WhoHas doesn't show that they have it: 9 people have your number. 1 more and you're Popular.) · Share in group. Not told again as "joined WhoHas". |
| A contact's count went up (no call) | **Raju Plumber** · 2 more people have this number · now 14 |
| A caller now known to someone you know | **+91 90080 11223** · Now known to Kiran Sir |
| Your number went up | **1 more person has your number** · Now 23 |
| Several changes | one notification each; four or more stack under **WhoHas · 5 changes** |
| Reached a level | **You're Popular now** · 10 people have your number. 40 more and you're Highly popular. · Share in group |
| Close to a level (at most once a week) | **2 more people and you're Popular** · 8 people have your number. Share WhoHas in a group. · Share in group |
| Channels (Android settings) | Calls from unknown numbers (pops up, with sound) · Other calls (pops up, no sound) · Changes to your numbers (pops up, no sound) · Friends who join (pops up, no sound) · Your level |

## Settings

| Where | Now | New |
|---|---|---|
| Rows | Contacts On · Call log On · Notifications On · Your name · Invite friends · Your data and account · Help | Contacts On · Call log On · Notifications On · Your name · Invite friends · Your data · Help |
| Footnote | Turning Contacts or Call log off in Android settings also takes those numbers out of Found With at the next sync. | If you turn off Contacts or Call log in phone settings, WhoHas removes those numbers at the next sync. |
| Notifications page | After calls · Callers you haven't saved, and contacts whose list changed | **Calls from unknown numbers** · A missed call from a number not in your contacts, when someone has it. |
| | | **Other calls** · Received and dialled calls, quietly, when something is new. (These two are hidden in the contacts-only build.) |
| | | **Changes to your numbers** · Someone new has your number, or a count went up. |
| | | **Friends who join** · Someone in your contacts joins WhoHas. |
| | | **Your level** · When you reach Popular or Highly popular, are close, or someone joins with your link. |
| | Silent from 9 pm to 9 am. | No sound from 9 pm to 9 am. |
| | Not getting them after calls? | Not getting notifications after calls? |
| Name page | Your name on Found With · People who haven't saved your number see this name when they look it up. · Saved. | **Your name on WhoHas** · People who don't have you in their contacts see this name. · Name updated |
| Data page title | Your data and account · Found With stores these, encrypted | **Your data** · Stored safely on WhoHas |
| Data rows | Your number · Your name · Contacts · Call log numbers · Blocked people · Download my data | Your number · Your name · Contacts · Call log numbers · Blocked people · Download my data |
| Sign out | Sign out? · Your account stays. Sign in again with your number any time. | **Sign out?** · Your account stays. Sign in again any time with your number. · Sign out · Cancel |
| Delete | Delete your account? · … | Row: **Delete my account** (red). Dialog: **Delete your account?** · This removes, from WhoHas: • Your number and name • Your contacts and call log numbers • Your blocked list and settings · Other people's counts go down by one. Your phone's own contacts and call log are not touched. You can't undo this. · **Delete my account** · Keep my account (contacts-only: "Your contact numbers", "Your phone's own contacts are not touched") |
| Blocked page | No one is blocked. | You haven't blocked anyone. |
| Help | How it works · Contact us · Privacy policy | How WhoHas works · Write to us · **Delete my account without the app** (opens the web page) · Privacy policy · **Terms of use** · **Grievance officer** (opens the officer section of the terms) |

### How WhoHas works

> **1. You allow contacts and call log.** WhoHas checks those numbers against other users.
> **2. You see who has any number.** People you know are shown by name. Others are a count.
> **3. Others see you the same way.** If someone who knows you looks up a number you have, they see your name.
>
> We never message or call anyone. Everything is stored safely, and you can delete it any time in Settings › Your data.

## Share in a group

| Where | New |
|---|---|
| Top bar, first line next to WhoHas (every tab) | **⤴ Share in group** (solid blue) |
| Invite friends page | **Share in group** · Adds the most value: the more of your group join, the more callers you can recognise. |
| Why a group (the value, never a count) | Share in a group so everyone pools their numbers. Then you can find who has any number that calls you. (Used in the share message and Invite friends; no longer inside your number's card.) |
| Share in group vs Tell | **Share in group** is for a group. For one person it is **Tell Ravi on WhatsApp**, and the Invite list buttons read **Tell** and **Told**. |
| What's shared | A picture of WhoHas in the Facebook-style look (a missed call known to Priya, Meena and 10 more; "Unknown number calling?"; a blue band "Get WhoHas · Free on Android · link in the message"), and: Hi all! I'm using WhoHas. When an unknown number calls, it shows which people you know have that number, so you can ask them who it is. The more of us on it, the more callers we can recognise for each other. Get it here: {link} |

## Invite friends

| Where | Now | New |
|---|---|---|
| Title | Invite friends · When a friend joins, you see if they have your number. | **Invite friends** · a blue box: **38** people you call aren't on WhoHas · They likely have your number. Each one who joins can add 1 to your 8. · **Ravi and Asha already joined with your link.** (23 Nov) · Adds the most value: the more of your group join, the more callers you can recognise. · **Share in group** |
| Button | Share your link | **Share in group** |
| List | People you call most · Invite | **Let them know who has their number** · These people you call most aren't on WhoHas yet. Let them know. · Their number is with 7 people on WhoHas · **Tell** (the people you call most in the last 60 days, at least 3 calls, mobiles only, not on WhoHas; **Told** leaves the list for 30 days) |

## Phone settings fix (Xiaomi, Vivo, Oppo and others)

| Where | Now | New |
|---|---|---|
| Title | Let Found With work after calls | **Let WhoHas check your calls** |
| Line | Your phone closes apps to save battery. Then Found With can't tell you who knows a caller. | Your phone stops apps to save battery. Turn this on so WhoHas can check every call. |
| Brand steps | For Found With, in your phone's settings: Auto launch… | **Turn on Autostart for WhoHas** · Then set Battery to "No restrictions". (one line per brand) |
| Buttons | Open settings · Battery settings · Done | Open settings · Battery settings · Done |

## Other screens

| Where | Now | New |
|---|---|---|
| Update needed | Update Found With · This version is too old. Update to keep using it. | **Update WhoHas** · This version is too old. Update from the Play Store. |
| Suspended | This account is suspended · It broke Found With's rules. If that's a mistake, write to … | **Your account is suspended** · It broke WhoHas rules. If this is a mistake, write to help@… |
| Generic error | Something went wrong. Try again. | Something went wrong. Try again. |
| Opening the app (22 Nov) | (a first-time Home flashed for a moment) | No words: the WhoHas icon on the page colour (light grey, or dark grey in dark mode) stays until the saved data is read (at most 3 seconds), then Home opens as it is. |

## Web pages

| Page | New |
|---|---|
| Terms of use (whohas-app.web.app/terms, 23 Nov) | Who can use WhoHas (18+, your own Indian number, one account) · What WhoHas shows, and its limits (names can be wrong; it doesn't confirm who anyone is) · What you agree not to do (others' contacts, harassment, stalking, spam, bulk lookups or bots, pretending, unlawful names or reports) · Names and reports · Your account · The service ("as is") · Responsibility · Law and courts (India; Bengaluru) · **Grievance officer** (IT Rules 2021): name, email, acknowledged within 24 hours, resolved within 15 days. Linked from the home page, the privacy page, Help and the welcome screen. |
| Home page (whohas-app.web.app) | **WhoHas** · Unknown number calling? See which of your people have that number, so you can ask them who it is. People you know are shown by name; everyone else is a count. On Android. · Get it on Google Play · Privacy policy · Delete your account |
| Invite page (whohas-app.web.app/i/CODE) | **Priya invited you to WhoHas** · See who has any number, even when someone unknown calls. · Get it on Google Play · (iPhone) WhoHas is on Android for now. · WhoHas never messages or calls anyone. |
| Delete my account (whohas-app.web.app/delete) | **Delete your WhoHas account** · You don't need the app. Enter the mobile number you signed up with. We send a code to confirm it is yours. · Send code · **What is deleted:** your number, name, the contact and call log numbers WhoHas holds for you, blocked list and settings. Counts on other people's cards go down by one. What is kept after that, and for how long, is in the privacy policy. → **Enter the code** · **Delete my account** · This can't be undone. You can sign up again later with a fresh start. → **Your account is deleted** (or: No WhoHas account uses this number · use Remove your number). It deletes the account only; the number is not put on the remove list. |
| Remove-my-number page | **Remove your number from WhoHas** · Works even if you don't use the app. People on WhoHas won't see who has your number. · Send code · … · **Done. Your number is removed.** · People on WhoHas won't see who has it. |

## Still to decide

0. **Open from 22 Nov:** "Not popular" or the softer "Not popular yet"; whether to keep the progress line under the tag; the real retention to state in the privacy policy before the delete page is published. The design drew the "Why a group" box inside Android's share sheet; Android doesn't allow that, so the same words are on Home and Invite friends instead.

1. **The help email.** Links moved to **whohas-app.web.app** on 22 Nov (whohas.web.app is taken): invites (/i/CODE), /privacy, /delete, and a home page at /. help@found-with.app is gone; the pages name the contact email once there is one (until then: "the contact address on our Google Play listing"). A bought domain can replace whohas-app.web.app later.
2. **No he/she anywhere.** WhoHas doesn't know anyone's gender, so the words use names instead ("Invite Ravi", "Their own name: Raju Sharma"). The mockup's "Calls himself" and "Invite him" are replaced by these.
