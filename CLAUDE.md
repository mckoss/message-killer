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

## Checks before pushing

```bash
flutter analyze && flutter test
flutter build apk --release
(cd android && ./gradlew :app:testDebugUnitTest)
```
