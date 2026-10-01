# Message Killer — Technical Evaluation & Implementation Plan

## 1. Goals

1. Read every SMS/MMS message in the phone's messaging store.
2. Flag political ads, campaign fundraising, and other unsolicited donation
   requests.
3. Save flagged messages inside the app (searchable, exportable, restorable).
4. Delete them from the system messaging store so they disappear from the
   user's normal messaging app.
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
  score, and the UI shows *why* a message was flagged.
  - Keywords and phrases: `donate`, `chip in`, `contribute`, `match`,
    `triple-match`, `deadline`, `paid for by`, `PAC`, `campaign`, `ballot`,
    `vote`, `poll`, `survey`, `ActBlue`, `WinRed`, `Anedot`,
    candidate/party names, `Reply STOP to quit`/`STOP2END`.
  - Sender: short codes (5–6 digits), numbers not in contacts, a sender seen
    in many different threads.
  - Links: fundraising domains, link shorteners.
  - User allow-list and block-list, plus user-added keywords.
- **v2 (optional):** a small on-device text classifier (TFLite) trained on
  the messages the user has labeled.
- **v3 (optional, opt-in only):** cloud LLM classification for borderline
  messages, with a clear privacy notice.

### 2.4 Proposed stack

| Concern | Choice |
|---------|--------|
| UI | Flutter (Material 3) |
| State management | Riverpod |
| Local storage | drift (SQLite) for the archive, rules, and scan history |
| Native bridge | Our own Kotlin `MethodChannel` (`SmsChannel`). Existing pub packages (`telephony`, etc.) are unmaintained and do not cover delete or the default-app role. |
| Permissions | `permission_handler` for runtime permissions; native code for `RoleManager` |
| Export | JSON + CSV through `share_plus` |
| Min Android | API 29 (Android 10) for `RoleManager`; target the latest SDK |

---

## 3. Implementation order

Each phase ends with something that runs on a real Android phone.

### Phase 0 — Scaffold and de-risk (do first)
1. `flutter create --org com.mckoss --platforms android,ios message_killer`
   at the repo root. Set up lints (`flutter_lints`/`very_good_analysis`) and
   CI (`flutter analyze`, `flutter test`).
2. **Spike: deletion round-trip on a real device.** Use a throwaway Kotlin
   activity to: request `ROLE_SMS` → delete one test SMS → hand the role back
   to Google Messages → confirm the message is still gone after Messages
   re-syncs. Repeat with Samsung Messages if a Samsung device is available.
   *If this fails, change the plan before building any UI.*

### Phase 1 — Read-only scanner
3. Kotlin `SmsChannel.readMessages(since, limit)` → paged list of
   `{id, threadId, address, body, date, type, read}`. Start with SMS only.
4. Runtime permission flow, including onboarding for "Allow restricted
   settings".
5. Inbox list screen that shows raw messages, grouped by sender.

### Phase 2 — Classifier
6. Pure-Dart rules engine with weighted rules, a threshold, and
   "reasons" output. Unit-test it against a fixture set of real political
   and donation texts as well as normal texts.
7. Scan results screen showing flagged messages with their scores and
   reasons, plus confirm / un-flag actions.
8. Settings for custom keywords, the allow-list (senders/contacts), and the
   sensitivity threshold. Optionally read contacts to auto-allow known
   senders.

### Phase 3 — Archive
9. drift schema with tables `archived_messages`, `rules`, `scan_runs`, and
   `allow_list`.
10. "Archive" action that copies confirmed messages into the DB and checks
    that each write succeeded.
11. Archive browser with search, filtering by sender and date, and JSON/CSV
    export.

### Phase 4 — Delete (default SMS app flow)
12. Add the required default-SMS-app components to the manifest:
    `SmsDeliverReceiver`, `MmsWapPushReceiver`, `HeadlessSmsSendService`, and
    `ComposeSmsActivity`.
    - `SmsDeliverReceiver` **must** write incoming SMS to the provider and
      post a notification.
13. `DefaultSmsRole.request()` / `isDefault()` exposed over the channel.
14. `SmsChannel.deleteMessages(ids)`. It only runs when the app is the
    default, only deletes messages that are already archived, and returns
    per-message results.
15. Guided UX: "Archive & Delete" → role prompt → delete with progress →
    "Restore your messaging app" screen that opens the role picker or default
    apps settings and does not let the user dismiss it until the default app
    has changed.
16. **Restore from archive**: write archived messages back into the provider
    (also requires being the default app). This is the safety net.

### Phase 5 — Polish and hardening
17. MMS support (read text parts and images, delete MMS).
18. Batching and performance for large inboxes (10k+ messages).
19. Edge cases: dual SIM, messages that arrive during the delete window,
    the user cancelling the role dialog, app killed mid-delete (resume from
    a journal).
20. Accessibility, dark mode, app icon, release signing config, and
    versioned APKs published to GitHub Releases.

### Phase 6 — Optional enhancements
21. "Live mode": a notification listener flags new political texts as they
    arrive and batches them for the next cleanup.
22. Scheduled reminders ("You have 37 new political texts — clean up?").
23. On-device ML classifier trained on the user's labels.
24. Opt-in cloud/LLM classification for borderline messages.
25. Shareable community rule packs (e.g. updated candidate/PAC names each
    election cycle).

### Phase 7 — iOS (separate pass)
26. Swift `ILMessageFilterExtension` target that uses the same rule set
    (rules serialized to JSON in an App Group container).
27. Flutter iOS UI limited to rule editing, setup instructions, and a test
    box ("paste a message to see if it would be filtered").
28. No scan, archive, or delete on iOS. Document this clearly in the app and
    the README.

---

## 4. Testing strategy

- **Unit:** classifier rules and scoring, repository/DB logic (drift
  in-memory).
- **Widget:** scan results, review, and archive screens with fake channel
  data.
- **Device:** the deletion round-trip, the default-app handoff, and
  receiving an SMS while we are the default app, run manually on at least a
  Pixel (Google Messages) and a Samsung device (Samsung Messages).
- **Emulator:** use `adb emu sms send <number> <text>` to seed test
  messages.

## 5. Open questions

- Should Message Killer also block or report senders (e.g. forward to 7726)
  in addition to deleting?
- Retention policy for the archive: keep forever, or auto-purge after N
  months?
- Is Google Play distribution a goal, or will it stay sideload-only?
