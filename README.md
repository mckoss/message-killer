# Message Killer

Message Killer is a cross-platform (Flutter) mobile app that cleans political
ads, campaign fundraising texts, and other unsolicited donation requests out of
your SMS inbox.

It scans the messages on your phone, flags the ones that look like political or
donation spam, saves them inside the app (so nothing is lost), and then deletes
them from your phone's messaging app.

> **Status:** Pre-alpha / planning. The Flutter project has not been generated
> yet. See [`plan.md`](plan.md) for the implementation order and the technical
> feasibility evaluation.

## Platform support

| Platform | Read SMS | Classify | Archive in app | Delete from inbox |
|----------|----------|----------|----------------|-------------------|
| Android  | Yes      | Yes      | Yes            | Yes, while Message Killer is temporarily the default SMS app |
| iOS      | No       | Incoming messages from unknown senders only (filter extension) | No | No (can only route to the Junk folder) |

Android is the primary target. iOS is a later, separate pass with a much smaller
feature set because of platform restrictions — see `plan.md` for details.

## How it works (Android)

1. **Scan** – reads the SMS/MMS inbox through Android's SMS content provider
   (`READ_SMS` permission).
2. **Classify** – each message is scored by on-device rules: keywords
   ("chip in", "donate", "paid for by", "ActBlue", "WinRed", "Reply STOP to
   quit"), sender patterns (short codes and other numbers that aren't in your
   contacts), and link patterns. Everything runs on the phone; nothing is
   uploaded.
3. **Review** – you look over the flagged messages and confirm or un-flag
   them. You can also add your own keywords and "never flag" senders.
4. **Archive** – the confirmed messages are copied into the app's own local
   database and can be searched or exported (JSON/CSV) later.
5. **Delete** – Android only lets the *default SMS app* delete messages. Message
   Killer asks you to make it the default SMS app for a moment, deletes the
   archived messages, and then sends you back to switch your regular app
   (e.g. Google Messages) back to the default.

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

The release page with build notes is
<https://github.com/mckoss/message-killer/releases/tag/prototype>. To build a
branch that isn't `main`, go to *Actions → Android prototype APK → Run
workflow* and pick the branch.

> The link starts working once the first prototype has been built (Phase 1 in
> `plan.md`). Until then the workflow skips the build.

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

1. Grant the **SMS** permission when asked. On Android 13+, if the permission
   switch is greyed out for a sideloaded app, open
   *Settings → Apps → Message Killer → ⋮ → Allow restricted settings*, then
   try again.
2. (Optional) Grant **Contacts** permission so messages from people you know
   are never flagged.
3. Tap **Scan**, review the results, then tap **Archive & Delete**.
4. When asked, choose Message Killer as the default SMS app. When it finishes,
   follow the prompt to switch back to your usual messaging app.

> **Tip:** Back up your messages before the first delete (for example with
> Google One backup or "SMS Backup & Restore"). Deleted messages can be put back
> from the Message Killer archive, but a separate backup is cheap insurance.

## Project structure (planned)

```
message-killer/
├── lib/
│   ├── main.dart
│   ├── app/                 # routing, theme
│   ├── features/
│   │   ├── scan/            # inbox scan + results UI
│   │   ├── review/          # confirm / un-flag messages
│   │   ├── archive/         # saved messages, search, export
│   │   └── settings/        # rules, allow-list, default-app flow
│   ├── classifier/          # rule engine + scoring
│   ├── data/                # local DB (drift/SQLite), repositories
│   └── platform/            # Dart side of the SMS platform channel
├── android/
│   └── app/src/main/kotlin/…/
│       ├── SmsChannel.kt    # read / delete / restore via ContentResolver
│       ├── DefaultSmsRole.kt
│       ├── SmsDeliverReceiver.kt    # required to be a default SMS app
│       ├── MmsWapPushReceiver.kt    # required to be a default SMS app
│       ├── HeadlessSmsSendService.kt
│       └── ComposeSmsActivity.kt
├── ios/                     # later pass – Message Filter extension
├── test/                    # unit tests (classifier, repositories)
├── README.md
└── plan.md
```

## Development

```bash
flutter analyze          # static analysis / lints
flutter test             # unit + widget tests
dart format .            # formatting
```

## Privacy

- All classification happens on the device by default.
- Archived messages are stored only in the app's private storage on your phone.
- Any optional cloud/LLM classification will be strictly opt-in and clearly
  labeled.

## Disclaimer

Message Killer deletes data from your phone. Use it at your own risk and keep
a backup. It is meant for personal, sideloaded use. Publishing to Google Play
would need Google's SMS-permission approval (see `plan.md`).

## License

TBD.
