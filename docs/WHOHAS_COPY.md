# WhoHas: the words on every screen

Approved by Y on 17 Nov 2026 (with the starting screens below). Replaces every sentence in the app, the notifications, the messages people send and the two web pages.

Updated the same night after talking it through: "has" instead of "saved" (call logs count too), contacts before call-log people on a card, honest label text, the **Tell them who has their number** card, counts that are always real, and sign-in with a SIM in this phone only. The screens for all of this are on the WhoHas screens design canvas.

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
| Name 3+ people have for a number | **5 people know it as "Raju Plumber"** | popular name, saved as |
| 10–49 people | **Many people have this number** · often a business, or someone who calls a lot of people | popular number, "usually a shop" |
| 50+ people | **Lots of people have this number** · often a business, or someone who calls a lot of people | highly popular number |

**No bare numbers in one-line summaries.** Someone you know only from your call log, with no WhoHas name, still counts ("You know 1 of them") and is listed on the card with their last call, but is never named as a number in "You know …", "Known to …" or the main Ask button.

**"Has" means contacts or call log.** WhoHas counts a number when it is in someone's contacts *or* their call log, so never say "saved" for that. Use "save" only for Android's own **Save to contacts** button. Names come only from contacts (a call log entry has no name), so for names say "know it as".

---

## Starting screens and sign-in

| Where | Now | New |
|---|---|---|
| Welcome title | Found With | **WhoHas** |
| Welcome headline | See who has your number. | **Unknown number calling?** See which of your people know it. |
| Benefit 1 | (example card) | **Know your callers** · After a call, see which people you know have that number. |
| Benefit 2 | | **Check any number** · See how many people have it, and ask someone you know. |
| Benefit 3 | | **See who has your number** · Find out how many people have your number, and when it goes up. |
| Trust line | Never messages or calls your contacts. | WhoHas never messages or calls anyone. |
| Buttons | Continue · Type your number | **Get started** (opens Android's number picker, which lists the SIMs in this phone) · under it: Use the number of a SIM in this phone. |
| Number picker | (Android's own) | Android's sheet with SIM 1 and SIM 2. Picking one sends the code straight away. **None of the above** opens the number screen. |
| Invited chip | A friend invited you | Priya invited you (name when known, else "A friend invited you") |
| After sign-in, invited | (none) | **Priya and 2 others invited you** · 8 people on WhoHas have your number (names everyone who tapped Tell for this number) |
| After deleting | Your account is deleted. | Your account is deleted. |
| Number screen (only after None of the above) | Your mobile number | **Your mobile number** · Use the number of a SIM in this phone. We'll send it an SMS to check. · Send code |
| Wrong number | 10 digits, starting with 6, 7, 8 or 9. | Enter a 10-digit mobile number starting with 6, 7, 8 or 9. |
| Sending | Checking +91 98450 31207 · Reading the code from the SMS… | Sending a code to +91 98450 31207… |
| Code screen | Enter the code · Sent to … · Change | **Checking your SIM** · We sent an SMS to +91 98450 31207. WhoHas reads the code on this phone by itself. · If Android asks, tap **Allow** so WhoHas can read that one SMS. · Send again in 0:25 · Change number. No boxes to type the code. |
| Code didn't reach (after 60 seconds) | (none) | **The code didn't reach this phone** · WhoHas works only with a SIM that's in this phone. Check that the SIM for +91 98450 31207 is in this phone, then send the code again. · Send again · Use another number |
| Wrong code (test builds and the Play review number only) | That code doesn't match. Check the SMS. | Wrong code. Check the SMS and try again. |
| Resend | Resend in 0:25 · Resend code | Send again in 0:25 · Send code again |
| Too many tries | Too many tries · For safety, wait a while and try again. | Too many tries · Wait 15 minutes and try again. |
| Couldn't send | We couldn't send the code. Try again. | Couldn't send the code. Try again in a minute. |
| No internet | No internet · Connect and try again. | No internet · Connect and try again. |

**Only a SIM in this phone.** The code is accepted only when this phone reads the SMS itself: first silently (Google Play services' SMS Retriever, through Firebase), else through Android's own "Allow WhoHas to read this message?" prompt. That proves the SIM is in this phone, because the SMS has to land here. A code typed by hand is not accepted, except in test builds and for the one number given to Google Play's reviewers. WhoHas keeps listening for 5 minutes, so an SMS that comes after the "didn't reach" screen still signs the person in.

## First time on Home

| Where | Now | New |
|---|---|---|
| Your number (before allowing) | Your number · Found with 23 people | Your number · **23 people have it** · Allow contacts to see who they are. |
| Allow card (Y's words, 17 Nov) | See who knows your contacts · Allow contacts, and each one shows who else has the number. · Allow | **See who has each number** · Allow contacts and call log. Then, for every number on your phone: · ✓ See how many people on WhoHas have it · ✓ After a call, see who has the caller's number · ✓ Know when more people have your number · **Allow** |
| Privacy line | Stored encrypted · How it works | No one sees your contact list. If a friend checks a number you have, they see your name. · How WhoHas works |
| Contacts-only build | (same card) | Allow contacts. Then, for every number in your contacts: · ✓ See how many people on WhoHas have it · ✓ Know when more people have your number |
| Checking | Finding who you know… | Checking your contacts… This takes a few seconds. |
| Name card | Your name on Found With · People who haven't saved your number see this name. | **Add your name** · People who don't have you in their contacts will see this name. · Save · Later |
| Notifications card | (banner) Notifications are off. You won't hear after calls. | **Turn on notifications** · Get told who knows a caller right after a missed call. · Turn on |
| First empty Updates | You're all caught up | **Your updates will show here** · New people with your number, friends who join, and changes in your contacts. |

## Home

| Where | Now | New |
|---|---|---|
| Top bar | Found With | **WhoHas** · *Live · checked just now* (green dot) |
| Offline | Offline · last updated 9:40 pm | No internet · showing what we had at 9:40 pm |
| Search box | Whose number is this? | Search a number or name |
| Your number card (hero) | Your number · Found with Priya, Ravi and 21 others | Your number · **23 people have it** · You know Priya, Meena and 4 more · **+5 this week** |
| Nobody yet | No one here has your number yet | No one on WhoHas has your number yet · Invite friends |
| Before allowing | See who knows your contacts · Allow contacts, and each one shows who else has the number. | **See who has your number** · Allow contacts. WhoHas checks them against other users. · Allow |
| Under Allow | Stored encrypted · How it works | Stored safely · How WhoHas works |
| Finding | Finding who you know… | Checking your contacts… |
| Failed | Couldn't finish. Trying again by itself. · Try now | Couldn't finish. We'll keep trying. · Try now |
| Name card | Your name on Found With · People who haven't saved your number see this name. · Save · Not now | **Add your name** · People who don't have you in their contacts will see this name. · Save · Later |
| Notifications off | Notifications are off. You won't hear after calls. · Turn on | Notifications are off. You won't know who called. · Turn on |
| Late calls | Found With couldn't check your last 3 callers. · Fix | Your phone stopped WhoHas. Last 3 calls weren't checked. · Fix |
| Tabs | Updates · Calls · Contacts | Updates · Calls · Contacts |

### Updates (only what changed)

| Change | Line |
|---|---|
| Your count went up | **2 more people have your number** · One is Meena |
| A contact's count went up | **Raju Plumber** · 3 more people have this number · now 12 |
| A contact joined | **Priya joined WhoHas** · Has your number (or nothing, if not) |
| A number in your calls got known | **+91 98765 43210** · Called you · now known to Kiran Sir |
| Count passes 10 or 50 | **Raju Plumber** · Many people have this number now |
| Section titles | Today · Yesterday · This week · Earlier |
| Nothing changed | **No changes since this morning** · When more people get your number, or your contacts' numbers, it shows here. |
| First day | **Your updates will show here** · We check every few hours. |

### Tell them who has their number (card at the top of Updates)

The people you call most who aren't on WhoHas, with their real count, so you can tell them.

| Where | New |
|---|---|
| Title | **Tell them who has their number** |
| Subtitle | People you call most · not on WhoHas yet |
| Row | Ravi Kumar · **7 people have this number** · [Tell] |
| Row, nobody else | Suresh Bhaiya · No one on WhoHas has it yet · [Tell] |
| After tapping Tell | Told · drops off the list for 30 days |
| Footer | See all 10 · Hide (hides the card for 7 days) |
| Who is listed | The 10 contacts you call most in the last 60 days (at least 3 calls), Indian mobiles only, not on WhoHas. Highest real count first, then most calls. |
| Never | A notification about it. A padded count. |

### Calls

| Where | Now | New |
|---|---|---|
| Row | Missed call · 10:42 am · Found with Priya and 2 others | Missed call · 10:42 am · **Known to Priya and 2 more** |
| Nobody has it | Found with no one yet | No one on WhoHas has this number |
| Hidden number | Private number · Hidden numbers can't be checked | Private number · Can't check hidden numbers |
| Empty | No calls yet · Calls show here with who knows the number. | No calls yet · Your calls show here, with who has each number. |
| Call log off | See who knows your callers · Allow the call log, and every recent call shows who knows the number. | **See who has your callers' numbers** · Allow the call log. · Allow |
| Turned off in Settings | Call log is off · In Settings, tap Permissions, then Call logs, then Allow. | Call log is off · Open Settings › Permissions › Call logs › Allow. · Open Settings |

### Contacts

| Where | Now | New |
|---|---|---|
| Row | Found with Ravi Kumar, Priya Sharma and 2 others | **4 people have it** · You know Ravi and Priya |
| Only you | Only you have this number | No one else on WhoHas has it yet |
| Section | Only you have these | No one else has these yet |
| Empty | No contacts on this phone · Contacts you save show here with who else has the number. | No contacts on this phone · Contacts you save show here. |
| Contacts off | Contacts are off · In Settings, tap Permissions, then Contacts, then Allow. | Contacts are off · Open Settings › Permissions › Contacts › Allow. · Open Settings |

### Search

| Where | Now | New |
|---|---|---|
| Name search heading | From your phone | Your contacts |
| No match | No one called that · Letters search your own contacts. Type a number to search everyone. | No contact called "Raju" · Type a number to search everyone. |
| Part of a number | Type the full number · Like 98451 72046 | Type all 10 digits · Like 98451 72046 |
| Not a mobile | Type a 10-digit mobile number · Search works with mobile numbers. | Only mobile numbers can be searched · Like 98451 72046 |
| Checking | Checking +91 98450 31207… | Checking +91 98450 31207… |
| Daily limit | Today's search limit is over · Search again tomorrow. Calls and contacts keep updating. | Today's searches are over · Search again tomorrow. |
| No internet | No internet · Search needs the internet. Home still shows your last update. | No internet · Search needs the internet. |

## A number's card

Order on screen: who it is → how many have it → people you know → one main action → the list → "What does this mean?".

| Where | Now | New |
|---|---|---|
| Not-a-contact subtitle | (none) | Missed call · 10:42 am · not in your contacts |
| Owner's own name | Raju Sharma (name they gave) | **Their own name: Raju Sharma** (chip) |
| Name 3+ people have for it | Raju Plumber (popular name) | **5 people know it as "Raju Plumber"** |
| Main line | Found with Priya, Ravi and 21 others | **12 people** have this number · **You know 3 of them** |
| You know none | Found with 12 people | 12 people have this number · You don't know any of them |
| Only you | Only you have this number | No one else on WhoHas has this number yet |
| No one | Found with no one yet | No one on WhoHas has this number yet |
| Your own number | Found with … | **23 people have your number** · You know 6 of them |
| Label 10–49 | Popular number | Many people have this number · often a business, or someone who calls a lot of people |
| Label 50+ | Highly popular number | Lots of people have this number · often a business, or someone who calls a lot of people |
| Today | Today: 3 more people · now a Popular number | **+3 today** |
| Main action (not a contact) | Ask (per person) | **Ask Priya who this is** (WhatsApp), small "Ask" on each person below. Picks someone in your contacts first; a call-log person only when no contact has it. |
| Main action (contact not on WhoHas) | Ravi Kumar isn't on Found With yet · Invite | **Ravi Kumar isn't on WhoHas yet** · Let them know 7 people on WhoHas have their number. (With nobody: Let them know about WhoHas. Then you can ask each other about unknown callers.) · **Let them know on WhatsApp** (whole name, never the first word: "Dr." and "Plumber" don't stand alone) |
| After a missed call | Call back | Call back |
| List title | People you know who have it | People you know who have it · contacts first, then call log |
| Each person | Contact · In your call log | **In your contacts** · **In your call log · called you yesterday** / **you called on 12 Nov** (someone only in your call log shows their own WhoHas name, else their number; the last call helps you place them) |
| Rest | Also have it. They aren't on your phone, so you see only how many. | +9 more people you don't know |
| See all | See all 14 | See all 14 |
| Menu | Share this number · Save to contacts · Block Ravi | Share number · Save to contacts · Block Ravi |
| Block dialog | Block Ravi? · You and Ravi won't see each other on Found With. Calls and messages aren't affected. | **Block Ravi?** · You won't see each other on WhoHas. Calls and messages still work. · Block · Cancel |
| Blocked | Blocked · You and Ravi don't see each other on Found With. · Unblock | Blocked · You don't see each other on WhoHas. · Unblock |
| Taken out | Not shown on Found With · The owner took this number out. | **Hidden by the owner** · They removed this number from WhoHas. |
| Link | What it means (info icon) | What does this mean? |

### "What does this mean?" sheet

> **12 people have this number.** It's in their contacts or call log.
> **You know 3 of them.** They're in your contacts or call log, so you see their names.
> **9 you don't know.** You only see how many.
> **Many people have it.** It's often a business, or someone who calls a lot of people.
>
> [OK]

## Messages people send

Opened in WhatsApp with the text typed in; the person sends it themselves.

| Message | New text |
|---|---|
| Ask (owner unknown) | Hi, do you know whose number this is?<br>+91 98450 77120 |
| Ask (with a known name) | Hi, do you know whose number this is?<br>+91 98450 77120<br>Some people know it as "Raju Plumber". |
| Ask (owner gave a name) | Hi, do you know Raju Sharma?<br>+91 98450 77120 |
| Tell / Invite (2+ others have their number) | Hi! I'm on WhoHas, and 7 more people there have your number. See who they are:<br>{link} |
| Tell / Invite (1 other) | Hi! I'm on WhoHas, and 1 more person there has your number. See who:<br>{link} |
| Tell / Invite (no one else) | Hi! I'm on WhoHas. If an unknown number calls either of us, we can see if the other knows them. Join here:<br>{link} |
| Share a number | +91 98450 77120<br>5 people on WhoHas have this number. See who:<br>{link} |
| Share my link | Hi! I'm on WhoHas. If an unknown number calls either of us, we can see if the other knows them. Join here:<br>{link} |

## Notifications

| Kind | Now | New |
|---|---|---|
| Missed call, not a contact, with a name | Missed call: +91 98450 77120 · Raju Sharma (name they gave) · Found with Priya and 2 others | **Missed call: +91 98450 77120** · Their own name: Raju Sharma · Known to Priya and 2 more |
| Missed call, not a contact, no name | Missed call: +91 … · Found with 4 people | **Missed call: +91 98450 77120** · 4 people on WhoHas have this number |
| Contact, count went up | Missed call: Ravi · Now found with 12 · 3 more | **Missed call: Ravi** · 3 more people have Ravi's number · now 12 |
| Repeat calls | 2 missed calls: +91 … | 2 missed calls: +91 98450 77120 |
| Evening note | Today on Found With · 3 numbers on your phone are found with more people today. Your number: 1 more person. | **Today on WhoHas** · 1 more person has your number. 3 of your contacts got more people. |
| Friend joined | (none) | **Priya joined WhoHas** · Has your number |
| Channel names (Android settings) | Callers you haven't saved · Calls, quietly · Evening note | Calls from unknown numbers · Other calls (silent) · Daily summary |

## Settings

| Where | Now | New |
|---|---|---|
| Rows | Contacts On · Call log On · Notifications On · Your name · Invite friends · Your data and account · Help | Contacts On · Call log On · Notifications On · Your name · Invite friends · Your data · Help |
| Footnote | Turning Contacts or Call log off in Android settings also takes those numbers out of Found With at the next sync. | If you turn off Contacts or Call log in phone settings, WhoHas removes those numbers at the next sync. |
| Notifications page | After calls · Callers you haven't saved, and contacts whose list changed | **After calls** · Tells you who has the number of anyone who calls. |
| | Evening note · Once a day, only if something changed | **Daily summary** · Once a day, only when something changed. |
| | Silent from 9 pm to 9 am. | No sound from 9 pm to 9 am. |
| | Not getting them after calls? | Not getting notifications after calls? |
| Name page | Your name on Found With · People who haven't saved your number see this name when they look it up. · Saved. | **Your name on WhoHas** · People who don't have you in their contacts see this name. · Name updated |
| Data page title | Your data and account · Found With stores these, encrypted | **Your data** · Stored safely on WhoHas |
| Data rows | Your number · Your name · Contacts · Call log numbers · Blocked people · Download my data | Your number · Your name · Contacts · Call log numbers · Blocked people · Download my data |
| Sign out | Sign out? · Your account stays. Sign in again with your number any time. | **Sign out?** · Your account stays. Sign in again any time with your number. · Sign out · Cancel |
| Delete | Delete your account? · Your name, contacts and call log numbers are deleted from Found With. This can't be undone. | **Delete your account?** · Your name, contacts and call log numbers will be deleted from WhoHas. You can't undo this. · Delete · Cancel |
| Blocked page | No one is blocked. | You haven't blocked anyone. |
| Help | How it works · Contact us · Privacy policy | How WhoHas works · Write to us · Privacy policy |

### How WhoHas works

> **1. You allow contacts and call log.** WhoHas checks those numbers against other users.
> **2. You see who has any number.** People you know are shown by name. Others are a count.
> **3. Others see you the same way.** If someone who knows you looks up a number you have, they see your name.
>
> We never message or call anyone. Everything is stored safely, and you can delete it any time in Settings › Your data.

## Share in a group

| Where | New |
|---|---|
| Card on Updates (under Tell) | **Share WhoHas in your groups** · The more of your people on WhoHas, the more callers you can recognise. · **Share in a group** |
| Invite friends page | **Share in a group** (replaces "Share your link") |
| What's shared | The Welcome screen picture with "Get WhoHas · Free on Android · link in the message", and: Hi all! I'm using WhoHas. When an unknown number calls, it shows which people you know have that number, so you can ask them who it is. The more of us on it, the more callers we can recognise for each other. Get it here: {link} |

## Invite friends

| Where | Now | New |
|---|---|---|
| Title | Invite friends · When a friend joins, you see if they have your number. | **Invite friends** · When they join, you'll see if they have your number. |
| Button | Share your link | Share your link |
| List | People you call most · Invite | People you call most · 7 people have this number · **Tell** (same list and rules as the Updates card, all of them) |

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

## Web pages

| Page | New |
|---|---|
| Invite page | **Priya invited you to WhoHas** · See who has any number, even when someone unknown calls. · Get it on Google Play · (iPhone) WhoHas is on Android for now. · WhoHas never messages or calls anyone. |
| Remove-my-number page | **Remove your number from WhoHas** · Works even if you don't use the app. People on WhoHas won't see who has your number. · Send code · … · **Done. Your number is removed.** · People on WhoHas won't see who has it. |

## Still to decide

1. **The help email and web address.** found-with.app needs a WhoHas replacement (e.g. whohas.app or whohas.in); I'll check what's free.
2. **No he/she anywhere.** WhoHas doesn't know anyone's gender, so the words use names instead ("Invite Ravi", "Their own name: Raju Sharma"). The mockup's "Calls himself" and "Invite him" are replaced by these.
