# Message Killer — Technical Evaluation & Implementation Plan

## 1. Goals

Message Killer is a **companion app**. It works alongside Google Messages (or
Samsung Messages) and never replaces it, so RCS chat keeps working.

1. **Filter:** recognize political ads, campaign fundraising, and unsolicited
   donation requests by their *content* (senders keep switching numbers, so
   blocking numbers doesn't work).
2. **Silence:** watch incoming message notifications and cancel the ones that
   are political, so they don't keep interrupting you.
3. **Clean up daily (optional):** once a day, scan the SMS inbox, copy the
   political texts into the app's **Spam folder**, and delete them from the
   inbox.
4. **Spam folder:** visible in the app, with the reasons each message was
   flagged. Entries are kept for **90 days** and then purged automatically.
5. Android first. iOS is a separate, later pass.

---

## 2. Technical evaluation

### 2.1 Android — feasible, with one important catch

**Short answer:** A third-party, sideloaded Android app can do everything we
want. Reading is easy. Deleting works only while our app is the **default SMS
app**, so we have to briefly take over that role.

#### Reading messages — easy

- Messages are stored in the system Telephony provider
  (`content://sms`, `content://mms`, `content://mms-sms/conversations`).
- Any app with the `READ_SMS` runtime permission can query it, including
  history, sender address, timestamp, body, and thread ID.
- Sideloaded apps on Android 13+ may hit **"restricted settings"**: the
  permission toggle is greyed out until the user turns on
  *App info → ⋮ → Allow restricted settings*. This is a one-time step to
  document in onboarding.

#### Deleting messages — only the default SMS app can

- Since Android 4.4 (KitKat), **only the default SMS app can write to or
  delete from** the SMS provider. If any other app calls
  `ContentResolver.delete()`, the call quietly does nothing (it returns 0).
  This is the main constraint on the whole design.
- Options we considered:

| Approach | Works? | Notes |
|----------|--------|-------|
| **A. Become the default SMS app temporarily** (recommended) | Yes | Ask for `RoleManager.ROLE_SMS` (Android 10+), delete, then send the user back to switch to Google Messages / Samsung Messages. Two system dialogs per cleanup. |
| B. Be a full replacement SMS app | Yes | Same mechanism, but we would have to build a complete messaging client (threads, MMS, group chats, RCS is impossible anyway). Far too much scope. |
| C. Accessibility-service automation of Google Messages | Fragile | Breaks whenever the Messages UI changes, is slow, and is a red flag in Play policy. Not recommended. |
| D. Notification listener only | Partial | Can see *new* messages and dismiss their notifications, but cannot delete stored messages. Could be a useful "live mode" later. |
| E. Root / ADB shell | Yes | Not realistic for normal users. |

- **What it takes to qualify as a default SMS app.** Android only shows our
  app in the role picker if the manifest declares all of these:
  - a `BroadcastReceiver` for `SMS_DELIVER` (permission `BROADCAST_SMS`)
  - a `BroadcastReceiver` for `WAP_PUSH_DELIVER` with MIME
    `application/vnd.wap.mms-message` (permission `BROADCAST_WAP_PUSH`)
  - an `Activity` that handles `ACTION_SENDTO` for `sms:`, `smsto:`, `mms:`,
    `mmsto:`
  - a `Service` for `RESPOND_VIA_MESSAGE` (permission
    `SEND_RESPOND_VIA_MESSAGE`)

  These can be minimal, but they **must behave correctly** during the window
  when we are the default app (see risks below).

#### Risks and gotchas on Android

1. **Messages that arrive while we are the default app.** The default app is
   responsible for writing incoming SMS into the provider. Our
   `SMS_DELIVER` receiver must insert the message into `content://sms/inbox`
   and post a notification, or the message is lost. MMS arriving during the
   window is harder (you have to download the PDU). Mitigations: keep the
   window to a few seconds, handle SMS properly, and for MMS save the raw
   WAP push and tell the user.
2. **Google Messages keeps its own database.** Google Messages caches
   messages in its own database and syncs it with the provider. We need to
   confirm on real devices that deleted messages stay gone after Messages
   becomes the default again and re-syncs. This is a **Phase 0 spike**.
3. **RCS chats are not reachable.** Google Messages stores RCS ("Chat")
   messages in its own private storage, not in the Telephony provider. Third
   parties cannot read or delete them. Most political and donation texts are
   plain SMS/MMS from short codes or 10DLC numbers, so this is acceptable, but
   it should be documented.
4. **Samsung Messages and other OEM apps** behave mostly the same way, but
   they need to be tested separately (Samsung also has its own cache).
5. **Restoring the user's default app.** We cannot switch back
   programmatically. We open the role request / default-apps settings and
   ask the user. The UX has to make this step impossible to miss.
6. **Google Play policy.** `READ_SMS` and the SMS role are restricted
   permissions. Publishing on Play requires a Permissions Declaration Form
   and Google's approval. Spam filtering is a plausible case, but approval is
   not guaranteed. **For personal and sideloaded use this does not apply.**
   Plan to sideload and decide on Play distribution later.
7. **Dual-SIM / work profile.** These change `subscription_id` handling.
   Low priority.

**Verdict:** Android is **moderately easy**. Reading and classifying are
straightforward. The "become the default SMS app → delete → switch back" flow
takes the most work and has the most risk, so we prototype it first.

### 2.2 iOS — essentially not possible as specified

- iOS has **no public API to read** the SMS/iMessage database, and **no API to
  delete** messages. Sandboxing makes this impossible without a jailbreak.
- The only hook is a **Message Filter app extension**
  (`IdentityLookup` / `ILMessageFilterExtension`):
  - It runs only on **incoming** messages from **unknown senders** (not
    contacts). It cannot see existing history.
  - It can classify a message as `junk`, `promotion`, `transaction`, or
    `allow`, which sends the message to the matching Messages filter folder.
  - The extension cannot store message contents or share them with the
    containing app. It can only make a deferred network request to a server
    that is tied to the app.
  - The user has to turn it on manually under *Settings → Apps → Messages →
    Unknown & Spam*.
- So on iOS the feature set is: **"filter new political/donation texts into
  the Junk/Promotions folder using the same rules."** No archive, no delete,
  no scan of existing messages. Because the Message Filter extension is
  native-only, this would be a small Swift extension with a Flutter settings
  UI that edits the rule list (shared through an App Group).

**Verdict:** iOS is a separate, much smaller product. Postpone it until
Android is done.

### 2.3 Classification approach

- **v1: rules engine, on-device, explainable.** Each rule adds a weighted
  score, each rule counts at most once, and a message is flagged at
  score ≥ 3. The UI shows *why* a message was flagged.
  - Fundraising platforms (strong): `ActBlue`, `WinRed`, `Anedot`.
  - Donation asks: `donate`, `chip in`, `pitch in`, `contribute`, `$X now`,
    `match` / `triple-match`, `deadline`.
  - Political terms: `paid for by`, `PAC`, `campaign`, `ballot`, `vote`,
    `polling`, `Democrat`/`Republican`/`GOP`, party committees
    (DNC/RNC/DCCC/NRCC/…), offices (Congress, Senate), well-known names.
  - Marketing signatures: `Reply STOP to quit`, `STOP2END`, `txt STOP`.
    These are weak signals on their own.
  - Peer-to-peer style: the message opens with a politician introducing
    themselves ("It's Sherrod Brown.", "Amy Klobuchar here."), alarmist
    openers ("UPDATE:", "BREAKING:"), "Hail Mary request",
    "end-of-quarter" / "legally required deadline", "2X-matching every
    gift". These came from real texts and are covered by unit tests.
  - Sender: short codes (5–6 digits) add a small amount. Most of these texts
    come from ordinary 10-digit numbers, though, so content is what counts.
  - **Safety:** one-time codes / verification messages get a large negative
    score so they are never hidden.
  - User **custom keywords** (flag on their own) and an **allow-list** of
    senders (never flagged).
- **Where it runs:** the notification listener and the daily cleanup have to
  work when the Flutter UI isn't running, so the classifier lives in
  **Kotlin** (`Classifier.kt`, plain JVM code with unit tests). Flutter calls
  it over the platform channel, for example for the "test a message" box.
  The iOS pass would port the same rules.
- **Later (optional):** an on-device ML model trained on the user's
  corrections, and opt-in cloud/LLM classification for borderline messages.

### 2.4 Proposed stack

| Concern | Choice |
|---------|--------|
| UI | Flutter (Material 3), plain `StatefulWidget`s plus one service class for the MVP |
| Native core | Kotlin: classifier, Spam folder store, notification listener, inbox read/delete, default-SMS-role handling, daily worker |
| Storage | Native SQLite (`SQLiteOpenHelper`), so background components can write to it without Flutter running. Flutter reads it over the channel. |
| Background | `WorkManager` periodic job (24 h) |
| Native bridge | One `MethodChannel` (`message_killer/native`) |
| Min Android | API 29 (Android 10) for `RoleManager`; target the latest SDK |

### 2.5 Why not a full replacement SMS app?

We considered making Message Killer the permanent default messaging app,
which would let it file political texts away the moment they arrive.
**Rejected for now because a replacement app can't use RCS.** Android has
no public RCS API for third-party apps, so chats with other Android users
and with iPhones (iOS 18+) would drop back to SMS/MMS. That means losing
typing indicators, read receipts, high-quality media, end-to-end
encryption, reactions, and proper group chats. Also, a dependable MMS and
group-text implementation is months of work if built from scratch.

If this changes, the fastest path is forking Fossify Messages (Kotlin,
GPL-3.0) and adding our classifier, rather than writing a Flutter SMS
client from scratch.

### 2.6 Limits to know about

1. **Silencing happens just after the notification appears.** Android
   tells notification listeners about a notification *after* it is posted,
   so the phone may start its sound or vibration before Message Killer
   cancels it. The notification disappears almost immediately, but it may
   still buzz once.
   *Possible fix later ("quiet mode"):* set Google Messages' notifications to
   silent, and have Message Killer post its own alerting notification for
   messages that are **not** political.
2. **The daily cleanup can't delete without a tap.** Deleting requires
   briefly becoming the default SMS app, and Android always asks the user to
   confirm that. So the daily job finds political texts on its own, copies
   them into the Spam folder, and posts a notification ("12 political texts
   ready to delete"). One tap runs the delete and then guides you back to
   Google Messages.
3. **RCS messages** can be *silenced* (their notifications are visible), but
   they cannot be deleted, because they aren't in Android's SMS store. Their
   copies still go into the Spam folder.

---

## 3. Implementation order

### Phase 0 — Scaffold and pipeline ✅ (with the MVP)
1. Flutter project (`com.mckoss.message_killer`), lints, unit tests.
2. **Phone download pipeline:** `.github/workflows/android-prototype.yml`
   runs analysis and tests, builds the APK, and publishes it to the rolling
   `prototype` release at
   `releases/download/prototype/message-killer.apk`.
3. **Stable signing:** builds are signed with a committed, public prototype
   key (`android/prototype-signing/`), so each new prototype installs as an
   *update* and keeps the Spam folder. **TODO:** replace it with a private
   key stored as the repo secrets `ANDROID_KEYSTORE_BASE64`,
   `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and
   `ANDROID_KEY_PASSWORD`. `build.gradle.kts` uses those automatically when
   present. Switching keys requires one uninstall.

### Phase 1 — MVP ✅ (built; needs validation on a real phone)
The Kotlin code is type-checked and the classifier and UI have tests. Device
behavior is still unverified (see Phase 2).
Native (Kotlin):
4. `Classifier` — rules engine from §2.3, plus custom keywords and an
   allow-list. JVM unit tests.
5. `SpamStore` — SQLite Spam folder. Each entry has a source (`notification`
   or `sms`), a status (`silenced`, `pending_delete`, `deleted`), the sender,
   the text, the score, the reasons, and timestamps. Entries older than **90
   days** are purged at app start and on every daily run. A silenced
   notification and the matching inbox SMS are merged into one entry.
6. `MessageNotificationListener` — listens to the messaging app's
   notifications, reads the individual messages from `MessagingStyle`, and
   classifies them. If every new message in a notification is political, it
   **cancels the notification** (and the leftover group summary) and records
   the messages in the Spam folder.
7. `SmsInbox` — reads `content://sms/inbox` and deletes by id.
8. Default-SMS-app components required by Android (`SmsDeliverReceiver`,
   `MmsWapPushReceiver`, `HeadlessSmsSendService`, `ComposeSmsActivity`).
   While Message Killer briefly holds the role, an incoming SMS is either
   written to the inbox with a notification or, if it is political, filed
   straight into the Spam folder.
9. `DailyCleanupWorker` (WorkManager, every 24 h, optional) — scans the
   inbox, files new political texts as `pending_delete`, purges old entries,
   and posts a "N political texts ready to delete" notification.
10. Platform channel for the UI: status, permissions, scan, Spam folder
    list/remove, cleanup (role request → delete → back to Messages),
    settings, and classify-text.

Flutter UI:
11. **Home:** setup checklist (SMS permission, notification access, "allow
    restricted settings" hint), live-filter status, counts, a **Clean up
    now** button, and the daily cleanup toggle.
12. **Spam folder:** list with sender, time, snippet, and status. Tapping an
    entry shows the full text and the reasons. "Not political" removes the
    entry and allow-lists the sender. Shows the 90-day retention notice.
13. **Settings:** custom keywords, allowed senders, and a "test a message"
    box.
14. **Cleanup flow:** **preview screen** listing every text that will be
    deleted (open any of them, "Not political" to keep it, export a backup)
    → become default SMS app → delete → full-screen
    "switch back to Google Messages" step that opens the Default apps
    settings and checks that the switch happened.

### Phase 2 — Validate on a real phone
15. Confirm deleted texts stay gone after Google Messages becomes the
    default again and re-syncs.
16. Confirm that briefly switching the default app doesn't disturb RCS
    registration.
17. Measure how the notification cancel feels in practice (§2.6.1). Tune
    which messaging-app packages are watched (Google Messages, Samsung
    Messages, the current default).
18. Tune classifier weights against real political texts. Collect false
    positives and false negatives as test fixtures.

### Phase 3 — Spam folder upgrades
19. Restore an entry back into the inbox (needs the default role, same flow).
20. Search and filtering. (✅ Export to Downloads as JSON + CSV is done.)
21. ✅ Contacts are never filtered (`READ_CONTACTS`); "Allow sender" in the
    preview; Allowed senders screen to view/remove.
22. Choose the daily cleanup time. Remind the user to switch back if they're
    still on Message Killer as the default.

### Phase 4 — Hardening
23. ✅ Read, classify, and delete MMS text parts (picture/long messages).
    TODO: handle MMS that arrives
    while we hold the role.
24. Large inboxes (10k+ messages): batching and progress.
25. Dual SIM. App killed mid-delete (resume from a journal).
26. Battery-optimization exemption prompt. Make sure the listener rebinds
    after reboot or an app update.

### Phase 5 — Optional enhancements
27. "Quiet mode" (§2.6.1): Google Messages notifications set to silent;
    Message Killer re-alerts only for messages that aren't political.
28. On-device ML classifier trained on the user's corrections.
29. Opt-in cloud/LLM classification for borderline messages.
30. Shareable rule packs (e.g. updated candidate/PAC names each election
    cycle).

### Phase 6 — iOS (separate pass)
31. Swift `ILMessageFilterExtension` that uses the same rules to send new
    political texts from unknown senders to the Junk folder.
32. Flutter iOS UI limited to rule editing and setup instructions. No
    silencing, Spam folder, or delete on iOS.

---

## 4. Testing strategy

- **Unit (Kotlin, JVM):** classifier rules and scoring against a fixture set
  of political and normal texts.
- **Unit/widget (Dart):** UI screens with a fake platform channel.
- **CI:** `flutter analyze`, `flutter test`, Gradle unit tests, and an APK
  build on every push.
- **Device:** notification silencing, the cleanup round-trip, and receiving
  an SMS while we hold the default role, run manually on a Pixel (Google
  Messages) and, if possible, a Samsung device.
- **Emulator:** use `adb emu sms send <number> <text>` to seed test
  messages.

## 5. Open questions

- Should Message Killer also report senders (e.g. forward to 7726)?
- Is Google Play distribution a goal, or will it stay sideload-only?
