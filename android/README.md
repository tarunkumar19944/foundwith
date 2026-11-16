# Found With for Android

Kotlin and Jetpack Compose. Runs on Android 8 and later, on phones, small phones, tablets and foldables, light and dark, with any font size. It follows the final design (90 screens, project doc `claude/found-with-design.md`).

| Part | What it does |
|---|---|
| Sign-in | Android's own sheet offers the phone's numbers (both SIMs). The SMS code is read by itself, and Home opens. Typing the number, a wrong number or code, a code that didn't come, too many tries and no internet each have their own screen. |
| Home | "Whose number is this?" at the top. First time: your number's count and the Allow card. After that: **Updates**, **Calls** and **Contacts** as tabs at the bottom. Banners for offline, notifications off and the phone-settings fix. |
| A number's card | The same everywhere: name (yours, then "name they gave", then "popular name"), "Found with Priya, Ravi and 3 others", the label, today's change, the people you know (Ask on numbers you haven't saved), Invite, Call back, and ⋮ Share this number / Save to contacts / Block. Tap the line for "What it means". |
| After a call | A notification: who knows the caller. Sound only for missed calls from unsaved numbers that someone has; silent for the rest; nothing for calls you made, hidden numbers, blocked people and numbers nobody has. Silent 9 pm to 9 am. |
| Invite friends, Settings | Your link and the people you call most; permissions, notifications, your name, your data (download, blocked people, sign out, delete), Help and How it works. |
| Phone fix | For Xiaomi, Vivo, Oppo, Realme, OnePlus, Tecno, Infinix, Huawei and Asus: a one-tap link to the brand's Autostart or battery screen, offered only after calls keep being reached late. |

Found With is **never** the phone's caller ID or phone app, never draws over other apps and never asks for the Phone permission.

There are two builds of the same app:

- **full** also reads the call log: the Calls tab, call-log numbers in "Found with", and the after-call notifications.
- **contactsOnly** never asks for the call log: no Calls tab and no after-call notifications. Use it for Google Play until Play approves the call-log declaration (see below).

## 1. Try it on the emulator with your laptop's backend

No Firebase needed. Sign-in uses `dev:` tokens instead of SMS.

1. Start the API on your laptop as in the main README, section "Run the API on your laptop" (it uses `AUTH_MODE=dev`).
2. In `android/gradle.properties`, set `foundwith.devSignIn=true`. Leave `foundwith.apiUrl=http://10.0.2.2:8080`: that address is your laptop as seen from the emulator.
3. Open the `android` folder in Android Studio (Meerkat or newer; the build uses Android Gradle plugin 8.9.1), pick the **fullDebug** build variant, and run it on an emulator with Android 8 or newer. Make a call to or from the emulator (the Extended controls, Phone) to see an after-call notification.
4. Enter any mobile number. No SMS is sent; you're signed in as that number.

To get cards, sign in as two or three numbers that have each other saved. The emulator's Contacts app is the quickest way to add them. Use different emulators, or sign out in Settings between accounts.

**On a real phone over USB:** run `adb reverse tcp:8080 tcp:8080` and set `foundwith.apiUrl=http://localhost:8080`. Plain `http` is only allowed to `10.0.2.2` and `localhost`, and only in debug builds.

## 2. Connect it to your GCP deployment

Terraform has already turned on phone sign-in and limited SMS to India. The app also needs the Firebase side of the same project.

1. Go to [console.firebase.google.com](https://console.firebase.google.com), choose **Add project**, and pick your existing GCP project (the `project_id` in `terraform.tfvars`). Don't create a new one.
2. Add an Android app with package name `app.foundwith`.
3. Add your signing key's **SHA-1 and SHA-256** under Project settings, Your apps. For the debug key:
   ```bash
   cd android && ./gradlew signingReport      # look for "Variant: fullDebug"
   ```
   Firebase needs SHA-256 to check the app with Play Integrity, and SHA-1 for the reCAPTCHA check it falls back to.
4. Download `google-services.json` and put it in `android/app/`. It's in `.gitignore`.
5. In `android/gradle.properties`, set:
   ```properties
   foundwith.apiUrl=https://found-with-api-xxxxx-el.a.run.app   # terraform output -raw api_url
   foundwith.devSignIn=false
   ```
6. Build and run. Sign in with a real number, or with one of the `test_phone_numbers` from `terraform.tfvars` and its fixed code (no SMS is sent for those).

`google-services.json` also turns on push messages (Firebase Cloud Messaging) for the server's wake-ups, refreshes and the evening note; there's nothing else to set up for them in Firebase. Terraform turns on the FCM API on the server side.

Without `google-services.json` the app still builds, but SMS sign-in and push are off; the sign-in screen tells you when you try to send a code, and after-call notifications still work from the call-log trigger and when the app opens.

## 3. Build on GitHub instead

`.github/workflows/android.yml` runs on every push that touches `android/`. It runs the unit tests, builds both debug APKs, and keeps them for 14 days: open the run in the **Actions** tab and download **found-with-debug-apks**.

Optional settings, under the repository's Settings, Secrets and variables, Actions:

| Name | Kind | What it's for |
|---|---|---|
| `FOUNDWITH_API_URL` | Variable | Your Cloud Run URL. Without it the APK expects a backend on the emulator's host. |
| `GOOGLE_SERVICES_JSON` | Secret | The whole contents of `google-services.json`, for SMS sign-in. |
| `DEBUG_KEYSTORE_BASE64` | Secret | `base64 -w0 ~/.android/debug.keystore`. Every CI build is then signed with the same key, whose SHA-1 and SHA-256 you added to Firebase. Without it, each run makes a new key and SMS sign-in falls back to reCAPTCHA. |

## 4. Release to Google Play

The app targets Android 16 (API 36), which Google Play requires for new apps and updates from 31 August 2026.

1. In `android/gradle.properties`, set the three release settings. A release build refuses to build without the first two.
   ```properties
   foundwith.apiUrl=https://found-with-api-xxxxx-el.a.run.app
   foundwith.privacyUrl=https://your-site.example/privacy
   foundwith.versionCode=1        # raise by 1 for every upload; Play rejects a repeat
   foundwith.versionName=1.0.0
   ```
2. In Android Studio, choose Build, **Generate Signed App Bundle**, variant **contactsOnlyRelease**, and create an upload key. Keep the key and its passwords out of the repository.
3. Upload the bundle in Play Console. Turn on Play App Signing, then copy Play's **app signing key** SHA-1 and SHA-256 (on Play Console's App integrity page) into Firebase. Play re-signs the app, so without this SMS sign-in fails for people who install from Play.
4. Fill in the Play Console forms:
   - **Data safety:** phone number, name, contacts and call log numbers with the names they're saved under are collected to run the app, and stored encrypted. Names are shared with other users only as the "popular name" (what 3 or more people saved). The invite code from an install link is sent once. A push token (Device or other IDs) is kept for wake-ups, and the phone's brand and Android version are sent with daily counts of calls checked (no numbers). Data is encrypted in transit and users can delete it. Have the lawyer check this against the privacy notice.
   - **Privacy policy:** the same address as `foundwith.privacyUrl`.
   - **Account deletion:** Play asks for a web page where people can delete their account without the app: `https://<api>/out` does it (it takes the number out, and the account with it, after an SMS code and a warning).
   - **App access:** give Play's reviewers one of your `test_phone_numbers` and its code, so they can sign in without an SMS.
5. **Call log.** Play only lets an app read the call log for approved uses, through the Permissions Declaration form. Ship **contactsOnly** first. Submit **full** only after the declaration is approved.
6. Crashes and freezes from Play installs appear in Play Console under **Android vitals**, readable because the bundle carries R8's mapping file. No crash SDK is needed for launch.

## 5. After calls

Found With learns about calls from the call log, the way Truecaller does on phones where it isn't the default app, in three ways; whichever comes first handles a call, once (`call/AfterCall.kt`):

1. **The call-log trigger.** WorkManager wakes the app a few seconds after the call log changes. No extra permission.
2. **When the app opens.** Every time.
3. **The server's wake-up**, three times a day (`"why": "wake"` push). For phones that stop apps in the background.

A call is notified about only within the hour. Later than that it just shows in Calls and counts as **late**: two late calls in a week bring up the phone-settings fix, and every day the app reports calls seen and calls checked in time, by brand only (`POST /v1/me/health`; `admin health` shows the numbers).

The rules (`core/AfterCall.kt`, unit-tested):

- **With sound:** a missed call from a number you haven't saved, when someone has it or it has a name.
- **Silent:** answered calls from such numbers; calls from saved contacts whose list grew since you last looked ("Now found with 12 · 3 more").
- **Nothing:** calls you made, hidden numbers, blocked people, saved contacts with no change, and unsaved numbers nobody has.
- From 9 pm to 9 am nothing makes a sound. Repeat calls update one notification ("2 missed calls").

Three notification channels, so people can quieten any of them in Android's settings: Callers you haven't saved, Calls quietly, Evening note. The evening note is sent by the server (counts only) and shown by Android even when the app isn't running.

**Known limits.** On Xiaomi, Vivo, Oppo, Realme, Tecno and similar phones, the call-log trigger and the wake-ups may not run until the person allows Autostart (or the brand's equivalent); the phone-fix screen links straight to it. Truecaller's own help pages ask for the same steps. WhatsApp calls aren't in the phone's call log, so they never show.

## 6. Invites

- **The link.** Each person has one invite link, `https://<api>/i/<CODE>`: a page with a Google Play button carrying `referrer=invite%3D<CODE>` (on an iPhone, "on Android for now").
- **After install.** On first start the app asks Google Play which link it was installed from (the Play Install Referrer library) and, once the person has signed in, sends the code to `POST /v1/me/invite/redeem`. No reward: the inviter just hears that they joined.
- **Invite, Ask and Share** open WhatsApp (WhatsApp Business, then Messages, then the share sheet) with the message typed in; nothing is ever sent by itself. The messages (`core/Messages.kt`) speak to the reader, don't greet by name (names are saved as "Dr. Anand" or "Plumber Raju"), put the number on its own line, and never name who has a number.
- **Testing it.** The referrer only exists for installs from Google Play: use the internal testing track and open `https://play.google.com/store/apps/details?id=app.foundwith&referrer=invite%3D<CODE>` on the tester's phone.

## 7. How sync works

- **Full sync**: when the person taps Allow, then once a week. It sends every contact with the name it's saved under, and the call log numbers (in **full**), and replaces what the server has. The server stores names encrypted.
- **Changes**: seconds after contacts or the call log change, and in the daily background run, only what changed is sent. Renaming a contact sends it again with the new name. More than 500 changes, or a server request, means a full sync instead.
- The app keeps a private map from the server's references to the phone's numbers and contact names, the cards, the last counts seen (for "3 more" in Updates) and blocked people, so Home opens instantly and works offline.
- When the server answers 429 or 503, background sync waits as long as `Retry-After` says. Every request carries `X-App-Version`; a 426 shows "Update Found With".

## 8. What has been tested

- `app/src/test/.../core` covers every rule the screens use: number parsing (3,020 numbers checked against the server's own parser), the sync diff with names, the card wording and labels, names and tags, the Ask order, every message, the after-call rules (sound, silent, none, quiet hours, repeats, catch-up), brand fixes, search input, change tracking and Updates. Run them with `./gradlew testFullDebugUnitTest`.
- **Every push builds the app on GitHub** (`.github/workflows/android.yml`): both builds compile, the unit tests run, and lint reports no errors. The run's Summary step prints the test count and every lint warning.
- **Not yet tried on real phones**: see SPEC section 9, item 4.

## 9. Where things are

| Path | What's there |
|---|---|
| `core/` | Plain Kotlin, no Android: number parsing, sync diff, wording and labels, names, Ask order, messages, after-call rules, brands, search, changes. Unit-tested. |
| `data/ApiClient.kt` | Every API call. A 401 is retried once with a fresh token. |
| `data/AppModel.kt` | The app's state in one place: profile, cards, contacts, calls, changes, blocks, set-up and gates (update needed, suspended) |
| `data/AuthManager.kt` | SMS sign-in through Firebase, and dev sign-in for debug builds |
| `data/SyncEngine.kt`, `data/DeviceBook.kt` | Reading the phone's contacts (with names, labels, favourites) and call log, full and change syncs |
| `data/LocalStore.kt` | Cards, number labels, the sync snapshot, change log and call state, in the app's private storage (excluded from backups) |
| `data/Invites.kt` | Reading the invite code from the Play install, and redeeming it |
| `call/AfterCall.kt`, `call/Notifier.kt` | The after-call engine and the notifications |
| `push/MessagingService.kt` | Push messages: wake-ups, refreshes, the evening note |
| `work/SyncWork.kt` | Background sync with WorkManager |
| `ui/` | Every screen: Login, Home (tabs and search), CardScreen, Invite, Settings pages, PhoneFix, navigation |
| `app/build.gradle.kts` | Flavours, the API URL, dev sign-in, versions |
