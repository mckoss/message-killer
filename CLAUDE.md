# Message Killer – working notes

Flutter UI (`lib/`) over a Kotlin core (`android/app/src/main/kotlin/…`). The
classifier, Spam folder store, notification listener, and scan/delete logic
are native so they run without the Flutter UI. See `plan.md` for the design.

## Versioning (required on every push)

- `pubspec.yaml` `version:` is semantic (`MAJOR.MINOR.PATCH+build`). **Bump it
  in every push that will publish**: PATCH for fixes and rule tweaks, MINOR
  for new features. The `+build` part is ignored; CI sets the build number.
- CI (`.github/workflows/android-prototype.yml`) refuses to publish a version
  that already has a `v<version>` tag, then tags the release. The app shows
  the version at the bottom of the home screen.
- Pushes to `main` and `ccr-*` branches publish the APK to the rolling
  `prototype` release. Put `[skip ci]` in the head commit message to push
  without publishing.

## Classifier changes

- Rules live in `Classifier.kt` (`BUILT_IN_RULES`), each tagged with
  categories (political / commercial / phishing). Safety rules (verification
  codes, bank/card alerts, prescriptions/orders/appointments) have large
  negative weights so they are never filtered, even from a flagged sender.
- Changing rules changes `Classifier.fingerprint`, which makes the next scan a
  full rescan (and rebuilds flagged senders). Bump `SCAN_LOGIC_VERSION` for
  scan-logic changes the rule list doesn't capture.
- Add every missed or wrongly-flagged real text to `ClassifierTest.kt`
  (strip names/numbers/link tokens). Re-run exported corpora locally before
  shipping rule changes; **never commit users' exported messages**.

## Performance

- Inboxes can hold 30k+ messages: no O(n²) work. Never query or scan a
  table per message; use indexed lookups, `IN (…)` batches, and sets/maps.
- Cache repeated work: the classifier memoizes rule matches per body,
  `ContactsChecker.get` and `AppSettings` cache contacts / the allow list,
  and `Cleanup` caches results keyed by `SpamStore.version`, the rules
  fingerprint and `ContactsChecker.generation`.
- Long operations update `Cleanup.progress` often (small batches), and
  Flutter lists of messages use lazy builders.

## Branches

- Work on `main` and push to `main` (the owner's request); don't create
  session branches. CI publishes from `main`.

## Checks before pushing

Only push when every check passes (stop on the first failure).

```bash
flutter analyze && flutter test
flutter build apk --release
(cd android && ./gradlew :app:testDebugUnitTest)
```

In cloud sessions the environment's setup script installs Flutter to
`/opt/flutter` and the Android SDK to `/opt/android-sdk` (not on PATH: use
`/opt/flutter/bin/flutter`). If they're missing, the classifier can still be
compiled and its tests run with Gradle's bundled Kotlin compiler and JUnit:

```bash
L=/opt/gradle-8.14.3/lib
java -cp "$L/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath $L/kotlin-stdlib-2.0.21.jar:$L/junit-4.13.2.jar -d /tmp/t.jar \
  android/app/src/main/kotlin/com/mckoss/message_killer/Classifier.kt \
  android/app/src/test/kotlin/com/mckoss/message_killer/ClassifierTest.kt
java -cp /tmp/t.jar:$L/kotlin-stdlib-2.0.21.jar:$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar \
  org.junit.runner.JUnitCore com.mckoss.message_killer.ClassifierTest
```

For rule changes, also compare old vs new rules on the owner's latest
Spam folder export (ask for a fresh upload; never commit it).
