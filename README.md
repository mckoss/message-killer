# Message Killer

Message Killer is a **companion app** for Google Messages (or Samsung Messages)
that gets political ads, campaign fundraising texts, and other unsolicited
donation requests out of your way. It doesn't replace your messaging app, so
RCS chat keeps working.

- **Live filter:** when a political text arrives, Message Killer cancels its
  notification and files a copy in its **Spam folder**.
- **Cleanup:** on demand, or with a daily reminder, it finds political texts in
  your SMS inbox, saves them to the Spam folder, and deletes them from the
  inbox.
- **Spam folder:** every filtered text is kept for **90 days** along with the
  reasons it was flagged. "Not political" removes it and allow-lists the
  sender. **Export** (download icon) saves everything to your Downloads
  folder as JSON and CSV.
- **Preview:** before any delete, you see exactly which texts will go and can
  keep any of them.

> **Status:** MVP / prototype. See [`plan.md`](plan.md) for the roadmap and
> the technical evaluation.

## Platform support

| Platform | Live filter | Spam folder | Delete from inbox |
|----------|-------------|-------------|-------------------|
| Android  | Yes (notification access) | Yes, 90 days | Yes, with one tap per cleanup (Message Killer is briefly the default SMS app) |
| iOS      | Planned: new texts from unknown senders → Junk only | No | No |

## How it works (Android)

1. **Classify:** on-device rules score each text, looking for fundraising
   platforms (ActBlue, WinRed), donation asks ("chip in", "2X-matching every
   gift"), deadline pressure ("end-of-quarter deadline"), politicians
   introducing themselves ("It's Sherrod Brown."), party, election and
   office terms, and bulk-text "Reply STOP" footers. A text is filtered at a
   score of 3. Verification codes are never filtered. You can add your own
   keywords and allowed senders. Nothing leaves the phone.
2. **Silence (live filter):** a notification listener reads incoming message
   notifications. If a text is political, it cancels the notification and
   files a copy in the Spam folder. Your phone may still buzz once before the
   notification is removed.
3. **Clean up:** Android only lets the *default SMS app* delete texts. When
   you tap **Clean up now** (or the daily reminder):
   1. Message Killer files the political texts in your inbox.
   2. It asks to become the default SMS app for a moment.
   3. It deletes those texts.
   4. It takes you to *Default apps* to switch back to Google Messages.

   RCS chats can be silenced but not deleted, because Android doesn't give
   other apps access to them.

## Requirements

- [Flutter SDK](https://docs.flutter.dev/get-started/install) (stable channel,
  3.x) with Dart 3
- Android Studio or the Android command-line tools (Android SDK 34+), JDK 17
- An Android phone running Android 10 (API 29) or newer
- USB debugging turned on for the phone
  (Settings → About phone → tap *Build number* 7 times → Developer options →
  *USB debugging*)

Check your setup with:

```bash
flutter doctor
```

## Install the prototype on your phone (no computer needed)

Each push to `main` builds an APK automatically (GitHub Actions →
[`android-prototype.yml`](.github/workflows/android-prototype.yml)) and posts
it to the rolling **prototype** release. On your Android phone, open this link
in the browser:

**https://github.com/mckoss/message-killer/releases/download/prototype/message-killer.apk**

1. Download the file, then tap it in the notification or in *Files → Downloads*.
2. The first time, Android will ask you to allow your browser to
   *install unknown apps*. Allow it, then go back and tap **Install**.
3. If Play Protect warns about an unrecognized app, tap *More details →
   Install anyway*.
4. To update later, open the same link again and install over the old version.
   Builds are signed with a fixed prototype key (see
   `android/prototype-signing/`), so updates keep your Spam folder and
   settings.

The release page with build notes is
<https://github.com/mckoss/message-killer/releases/tag/prototype>. To build a
branch that isn't `main`, go to *Actions → Android prototype APK → Run
workflow* and pick the branch.

> Development branches (`ccr-*`) also publish to this link, so it always has
> the newest build.

## Building from source

```bash
git clone https://github.com/mckoss/message-killer.git
cd message-killer
flutter pub get
```

### Run on a connected Android phone

```bash
flutter devices          # confirm the phone is listed
flutter run              # debug build, hot reload enabled
```

### Build and install a release APK

```bash
flutter build apk --release
adb install -r build/app/outputs/flutter-apk/app-release.apk
```

You can also copy the APK to the phone and open it there. You will have to allow
installing apps from that source.

### First launch on the phone

1. **Allow access to your texts** (the SMS permission). If the switch is greyed
   out ("Restricted setting"), open *App info → ⋮ → Allow restricted
   settings*, then try again. Sideloaded apps on Android 13+ need this.
2. **Turn on the live filter:** tap *Open*, choose Message Killer, and allow
   notification access. This step may also need "Allow restricted settings".
3. Tap **Clean up now** to clear the backlog. When asked, choose Message Killer
   as the default SMS app. Then use **Switch back** to make Google Messages
   the default again.
4. Optional: turn on **Daily cleanup reminder**.
5. Use **Filter settings** (top right) to add keywords, allow senders, or paste
   a text to see whether it would be filtered.

> **Tip:** Back up your messages before the first cleanup (for example with
> Google One backup). Deleted texts are kept in the Spam folder for 90 days,
> but restoring them to the inbox isn't built yet.

## Project structure

```
message-killer/
├── lib/                             # Flutter UI
│   ├── main.dart
│   ├── native_api.dart              # typed wrapper around the platform channel
│   ├── format.dart
│   └── screens/
│       ├── home_screen.dart         # setup, live filter, cleanup, daily toggle
│       ├── cleanup_flow.dart        # scan → default-app switch → delete → switch back
│       ├── spam_folder_screen.dart  # 90-day Spam folder + detail
│       └── settings_screen.dart     # keywords, allow list, "test a message"
├── android/app/src/main/kotlin/com/mckoss/message_killer/
│   ├── Classifier.kt                # rules engine (plain JVM, unit-tested)
│   ├── SpamStore.kt                 # SQLite Spam folder, 90-day retention
│   ├── MessageNotificationListener.kt  # live filter
│   ├── SmsInbox.kt / Cleanup.kt     # read, scan, delete
│   ├── DailyCleanupJob.kt           # daily scan + reminder notification
│   ├── DefaultSmsComponents.kt      # components Android requires of an SMS app
│   ├── Notifications.kt / AppSettings.kt
│   └── MainActivity.kt              # platform channel, permissions, role request
├── android/app/src/test/…/ClassifierTest.kt
├── test/                            # Dart unit + widget tests (fake native API)
├── README.md
└── plan.md
```

## Development

```bash
flutter analyze                                 # static analysis / lints
flutter test                                    # Dart unit + widget tests
(cd android && ./gradlew :app:testDebugUnitTest)  # classifier tests
dart format .                                   # formatting
```

## Privacy

- All classification happens on the device by default.
- Spam folder copies are stored only in the app's private storage on your
  phone and are purged after 90 days.
- Any future cloud/LLM classification will be strictly opt-in and clearly
  labeled.

## Disclaimer

Message Killer deletes data from your phone. Use it at your own risk and keep
a backup. It is meant for personal, sideloaded use. Publishing to Google Play
would need Google's SMS-permission approval (see `plan.md`).

## License

TBD.
