import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:message_killer/main.dart';
import 'package:message_killer/native_api.dart';

import 'fake_native_api.dart';

/// Scrolls the screen's main list until [finder] is on screen.
Future<void> scrollTo(WidgetTester tester, Finder finder) async {
  await tester.scrollUntilVisible(
    finder,
    200,
    scrollable: find.byType(Scrollable).first,
  );
  await tester.pumpAndSettle();
}

/// Like pumpAndSettle, but tolerates the endless progress spinner shown while a cleanup is running.
Future<void> pumpABit(WidgetTester tester) async {
  for (var i = 0; i < 10; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  testWidgets('shows setup steps until permissions are granted', (
    tester,
  ) async {
    final api = FakeNativeApi(
      smsPermission: false,
      notificationAccess: false,
      contactsPermission: false,
    );
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();

    expect(find.text('Finish setup'), findsOneWidget);
    expect(find.text('Never filter your contacts'), findsOneWidget);
    await tester.tap(find.widgetWithText(OutlinedButton, 'Allow').first);
    await tester.pumpAndSettle();
    expect(api.calls, contains('requestPermissions'));

    await tester.tap(find.widgetWithText(OutlinedButton, 'Open'));
    expect(api.calls, contains('openNotificationAccessSettings'));
  });

  testWidgets('hides setup when everything is granted', (tester) async {
    await tester.pumpWidget(MessageKillerApp(api: FakeNativeApi()));
    await tester.pumpAndSettle();
    expect(find.text('Finish setup'), findsNothing);
    expect(find.text('Live filter'), findsOneWidget);
    expect(find.text('Scan now'), findsOneWidget);
  });

  testWidgets('cleanup: confirm, take SMS role, delete, then switch back', (
    tester,
  ) async {
    final api = FakeNativeApi(pending: 3);
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Scan now'));
    await pumpABit(tester);
    expect(find.text('Review before deleting'), findsOneWidget);
    expect(find.textContaining('3 texts will be deleted'), findsOneWidget);

    await tester.tap(find.widgetWithText(FilledButton, 'Delete 3'));
    await pumpABit(tester);
    expect(
      api.calls,
      containsAllInOrder([
        'scanInbox',
        'requestDefaultSmsRole',
        'deletePending',
      ]),
    );
    expect(find.text('Deleted 3 spam texts'), findsOneWidget);
    expect(find.text('Switch back to Messages'), findsOneWidget);

    await tester.tap(find.text('Switch back to Messages'));
    expect(api.calls, contains('openDefaultAppsSettings'));
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await pumpABit(tester);
    expect(
      find.text('All set: Messages is your default messaging app again.'),
      findsOneWidget,
    );
  });

  testWidgets('cleanup does nothing if SMS role is refused and user cancels', (
    tester,
  ) async {
    final api = FakeNativeApi(pending: 2, grantRole: false);
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Scan now'));
    await pumpABit(tester);
    await tester.tap(find.widgetWithText(FilledButton, 'Delete 2'));
    await pumpABit(tester);
    expect(find.text('Switch manually?'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Cancel'));
    await pumpABit(tester);

    expect(api.calls, isNot(contains('deletePending')));
    expect(find.textContaining('Nothing deleted'), findsOneWidget);
  });

  testWidgets('cleanup falls back to switching manually in Default apps', (
    tester,
  ) async {
    final api = FakeNativeApi(pending: 2, grantRole: false);
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Scan now'));
    await pumpABit(tester);
    await tester.tap(find.widgetWithText(FilledButton, 'Delete 2'));
    await pumpABit(tester);
    await tester.tap(find.widgetWithText(FilledButton, 'Open Default apps'));
    await pumpABit(tester);
    expect(api.isDefaultSmsApp, isTrue);

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await pumpABit(tester);
    expect(api.calls, contains('deletePending'));
    expect(find.text('Deleted 2 spam texts'), findsOneWidget);
  });

  testWidgets('cleanup with nothing to delete shows a message', (tester) async {
    await tester.pumpWidget(MessageKillerApp(api: FakeNativeApi()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Scan now'));
    await tester.pumpAndSettle();
    expect(
      find.text(
        'No spam texts to delete. '
        'Checked 90 texts + 10 picture messages since the last scan.',
      ),
      findsOneWidget,
    );
  });

  testWidgets('warns when still the default SMS app', (tester) async {
    await tester.pumpWidget(
      MessageKillerApp(api: FakeNativeApi(isDefaultSmsApp: true)),
    );
    await tester.pumpAndSettle();
    expect(
      find.text('Message Killer is still your default SMS app'),
      findsOneWidget,
    );
  });

  testWidgets(
    'spam folder lists entries and "Not spam" allow-lists the sender',
    (tester) async {
      final api = FakeNativeApi(
        spam: [
          spamEntry(1, sender: 'Smith for Congress'),
          spamEntry(2, status: SpamStatus.deleted),
        ],
      );
      await tester.pumpWidget(MessageKillerApp(api: api));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Spam folder'));
      await tester.pumpAndSettle();
      expect(find.textContaining('kept here for 90 days'), findsOneWidget);
      expect(find.text('Smith for Congress'), findsOneWidget);

      await tester.tap(find.text('Smith for Congress'));
      await tester.pumpAndSettle();
      expect(find.text('Asks for a donation'), findsOneWidget);

      await tester.tap(
        find.widgetWithText(OutlinedButton, 'Not spam: allow sender'),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Allow sender'));
      await tester.pumpAndSettle();

      expect(api.allowedSenders, ['Smith for Congress']);
      expect(find.text('Smith for Congress'), findsNothing);
    },
  );

  testWidgets('settings: add keyword and test a message', (tester) async {
    final api = FakeNativeApi();
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('Filter settings'));
    await tester.pumpAndSettle();

    await scrollTo(tester, find.text('Custom keywords'));
    await tester.enterText(
      find.widgetWithText(TextField, 'e.g. a candidate\'s name'),
      'Jane Doe',
    );
    await tester.tap(find.byTooltip('Add').first);
    await tester.pumpAndSettle();
    expect(api.customKeywords, ['Jane Doe']);
    expect(find.widgetWithText(InputChip, 'Jane Doe'), findsOneWidget);

    await scrollTo(tester, find.widgetWithText(TextField, 'Message text'));
    await tester.enterText(
      find.widgetWithText(TextField, 'Message text'),
      'Give at actblue.com',
    );
    await scrollTo(tester, find.text('Test'));
    await tester.tap(find.text('Test'));
    await tester.pumpAndSettle();
    expect(find.text('Would be filtered as Political'), findsOneWidget);
  });

  testWidgets('preview: "Allow sender" keeps that sender\'s texts', (
    tester,
  ) async {
    final api = FakeNativeApi(pending: 2);
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Scan now'));
    await pumpABit(tester);
    expect(find.widgetWithText(FilledButton, 'Delete 2'), findsOneWidget);
    await tester.tap(find.byTooltip('Allow sender').first);
    await pumpABit(tester);
    expect(find.text('Always allow 88020?'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Allow sender'));
    await pumpABit(tester);

    expect(api.allowedSenders, ['88020']);
    expect(find.widgetWithText(FilledButton, 'Delete 1'), findsOneWidget);
    await tester.tap(find.widgetWithText(OutlinedButton, 'Cancel'));
    await pumpABit(tester);
    expect(api.calls, isNot(contains('requestDefaultSmsRole')));
    expect(api.calls, isNot(contains('deletePending')));
  });

  testWidgets('allowed senders: shows contacts note, add and remove', (
    tester,
  ) async {
    final api = FakeNativeApi()..allowedSenders = ['(302) 464-8095'];
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Filter settings'));
    await tester.pumpAndSettle();
    await scrollTo(tester, find.text('Allowed senders'));
    await tester.tap(find.text('Allowed senders'));
    await tester.pumpAndSettle();

    expect(find.text('Everyone in your contacts'), findsOneWidget);
    expect(find.text('Always allowed automatically'), findsOneWidget);
    expect(find.text('(302) 464-8095'), findsOneWidget);

    await tester.tap(find.text('Add'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '555-0100');
    await tester.tap(find.widgetWithText(FilledButton, 'Allow'));
    await tester.pumpAndSettle();
    expect(api.allowedSenders, ['(302) 464-8095', '555-0100']);

    await tester.tap(find.byTooltip('Remove (302) 464-8095'));
    await tester.pumpAndSettle();
    expect(api.allowedSenders, ['555-0100']);
    expect(find.text('(302) 464-8095'), findsNothing);
  });

  testWidgets('spam folder export saves to Downloads', (tester) async {
    final api = FakeNativeApi(spam: [spamEntry(1)]);
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Spam folder'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Export to Downloads'));
    await tester.pump();
    await tester.pump();
    expect(api.calls, contains('exportSpam'));
    expect(find.textContaining('Saved 1 message to Downloads'), findsOneWidget);
  });

  testWidgets('review screen exports only texts waiting to be deleted', (
    tester,
  ) async {
    final api = FakeNativeApi(pending: 2, spam: null);
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Scan now'));
    await pumpABit(tester);
    await tester.tap(find.byTooltip('Export to Downloads'));
    await pumpABit(tester);
    expect(api.calls, contains('exportPending'));
    expect(api.calls, isNot(contains('exportSpam')));
    expect(
      find.textContaining('Saved 2 messages to Downloads'),
      findsOneWidget,
    );
  });

  testWidgets('settings: "Rescan all messages" runs a full scan', (
    tester,
  ) async {
    final api = FakeNativeApi();
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Filter settings'));
    await tester.pumpAndSettle();
    await scrollTo(tester, find.text('Rescan all messages'));
    await tester.tap(find.text('Rescan all messages'));
    await pumpABit(tester);
    expect(api.calls, contains('fullScan'));
    expect(
      find.text(
        'No spam texts to delete. Checked 90 texts + 10 picture messages.',
      ),
      findsOneWidget,
    );
  });

  testWidgets('spam folder filters by category', (tester) async {
    final api = FakeNativeApi(
      spam: [
        spamEntry(1, sender: 'Campaign'),
        spamEntry(2, sender: 'CVS', category: SpamCategory.commercial),
        spamEntry(3, sender: 'Toll scam', category: SpamCategory.phishing),
      ],
    );
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Spam folder'));
    await tester.pumpAndSettle();

    expect(find.text('All (3)'), findsOneWidget);
    await tester.tap(find.text('Commercial (1)'));
    await tester.pumpAndSettle();
    expect(find.text('CVS'), findsOneWidget);
    expect(find.text('Campaign'), findsNothing);
    expect(find.text('Toll scam'), findsNothing);
  });

  testWidgets('settings: turning off a category saves it', (tester) async {
    final api = FakeNativeApi();
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Filter settings'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(SwitchListTile, 'Commercial'));
    await tester.pumpAndSettle();
    expect(api.categories, {SpamCategory.political, SpamCategory.phishing});
  });

  testWidgets('flagged senders: list, export, un-flag', (tester) async {
    final api = FakeNativeApi()
      ..flagged = [
        FlaggedSender(
          sender: '5042944686',
          flaggedAt: DateTime(2026, 9, 1),
          messages: 60,
        ),
      ];
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Filter settings'));
    await tester.pumpAndSettle();
    await scrollTo(tester, find.text('Flagged senders'));
    await tester.tap(find.text('Flagged senders'));
    await tester.pumpAndSettle();

    expect(find.text('(504) 294-4686'), findsOneWidget);
    expect(find.textContaining('60 texts in Spam folder'), findsOneWidget);

    await tester.tap(find.byTooltip('Export to Downloads'));
    await tester.pump();
    await tester.pump();
    expect(api.calls, contains('exportFlagged'));
    expect(find.textContaining('Saved 1 sender to Downloads'), findsOneWidget);

    await tester.tap(find.byTooltip('Un-flag (504) 294-4686'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Un-flag'));
    await tester.pumpAndSettle();
    expect(api.calls, contains('unflag:5042944686'));
    expect(find.text('(504) 294-4686'), findsNothing);
  });

  testWidgets('restore: home card lists texts and restores them', (
    tester,
  ) async {
    final api = FakeNativeApi()
      ..restorable = [
        spamEntry(7, status: SpamStatus.deleted, sender: '692632'),
        spamEntry(8, status: SpamStatus.deleted, sender: '692632'),
      ];
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();

    expect(
      find.text('2 deleted texts no longer look like spam'),
      findsOneWidget,
    );
    await scrollTo(tester, find.text('Message Killer 0.16.0 (build 1)'));
    expect(find.text('Message Killer 0.16.0 (build 1)'), findsOneWidget);
    await scrollTo(
      tester,
      find.text('2 deleted texts no longer look like spam'),
    );
    await tester.tap(find.text('2 deleted texts no longer look like spam'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Restore 2'));
    await pumpABit(tester);

    expect(
      api.calls,
      containsAllInOrder(['requestDefaultSmsRole', 'restore:7,8']),
    );
    expect(find.text('Restored 2 texts to your inbox'), findsOneWidget);
    // "Always allow" is off unless switched on per number.
    expect(api.allowedSenders, isEmpty);
  });

  testWidgets('restore: uncheck a text to confirm it as spam', (tester) async {
    final api = FakeNativeApi()
      ..restorable = [
        spamEntry(7, status: SpamStatus.deleted, sender: '692632'),
        spamEntry(9, status: SpamStatus.deleted, sender: '90999'),
      ];
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();
    await tester.tap(find.text('2 deleted texts no longer look like spam'));
    await tester.pumpAndSettle();

    // Uncheck the Red Cross number (all its texts): they're really spam.
    await tester.tap(find.text('90999'));
    await tester.pumpAndSettle();
    expect(find.text('Restore 0 of 1 text'), findsOneWidget);
    // Allow-list BofA only.
    await tester.tap(find.text('Always allow 692632'));
    await tester.pumpAndSettle();
    await tester.tap(
      find.widgetWithText(FilledButton, 'Restore 1 · keep 1 as spam'),
    );
    await pumpABit(tester);

    expect(
      api.calls,
      containsAllInOrder(['confirm:9', 'requestDefaultSmsRole', 'restore:7']),
    );
    expect(api.allowedSenders, ['692632']);
  });

  testWidgets('restore: "None" then keep all as spam', (tester) async {
    final api = FakeNativeApi()
      ..restorable = [
        spamEntry(9, status: SpamStatus.deleted, sender: '90999'),
      ];
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();
    await tester.tap(find.text('1 deleted text no longer looks like spam'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, 'None'));
    await tester.pumpAndSettle();
    await tester.tap(
      find.widgetWithText(FilledButton, 'Keep all 1 text as spam'),
    );
    await tester.pumpAndSettle();

    expect(api.calls, contains('confirm:9'));
    expect(api.calls, isNot(contains('requestDefaultSmsRole')));
    expect(api.restorable, isEmpty);
  });

  testWidgets('prune: preview by sender, deselect one, delete the rest', (
    tester,
  ) async {
    final api = FakeNativeApi()
      ..pruneGroups = [
        PruneGroup(
          sender: '90999',
          count: 120,
          oldest: DateTime(2019, 1, 1),
          newest: DateTime(2026, 5, 1),
          sample: 'Red Cross: give blood',
        ),
        PruneGroup(
          sender: '692632',
          count: 40,
          oldest: DateTime(2020, 1, 1),
          newest: DateTime(2026, 6, 1),
          sample: 'BofA: Credit card charge',
        ),
      ];
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();
    await scrollTo(tester, find.text('Prune old messages'));
    await tester.tap(find.text('Prune old messages'));
    await pumpABit(tester);

    expect(find.textContaining('160 texts from 2 senders'), findsOneWidget);
    await tester.tap(find.text('692632 · 40 texts'));
    await pumpABit(tester);
    await tester.tap(find.widgetWithText(FilledButton, 'Delete 120 old texts'));
    await pumpABit(tester);

    expect(
      api.calls,
      containsAllInOrder(['requestDefaultSmsRole', 'prune:90999']),
    );
    expect(find.text('Deleted 120 old texts'), findsOneWidget);
  });

  testWidgets('spam over time: stacked months, totals and table', (
    tester,
  ) async {
    final api = FakeNativeApi()
      ..monthRows = [
        {'month': '2025-11', 'category': 'political', 'count': 40},
        {'month': '2025-11', 'category': 'commercial', 'count': 5},
        {'month': '2026-02', 'category': 'phishing', 'count': 3},
        {'month': '2026-02', 'category': 'political', 'count': 12},
        {'month': '2026-02', 'category': 'pruned', 'count': 999},
      ];
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Spam over time'));
    await tester.pumpAndSettle();

    expect(find.text('60 spam texts'), findsOneWidget);
    expect(
      find.textContaining('Since November 2025 · busiest: November 2025 (45)'),
      findsOneWidget,
    );
    expect(find.text('Political 52'), findsOneWidget);
    expect(find.text('Commercial 5'), findsOneWidget);
    expect(find.text('Phishing / scam 3'), findsOneWidget);

    // Empty months are filled in.
    await scrollTo(tester, find.text('Dec 2025'));
    expect(find.text('Jan 2026'), findsOneWidget);

    await tester.tap(find.text('Feb 2026'));
    await tester.pumpAndSettle();
    expect(find.text('February 2026: 15'), findsOneWidget);
  });

  test('MonthStats.fromRows fills gaps and drops bad rows', () {
    final months = MonthStats.fromRows([
      {'month': '2024-12', 'category': 'political', 'count': 2},
      {'month': '2025-02', 'category': 'commercial', 'count': 1},
      {'month': 'bogus', 'category': 'political', 'count': 7},
      {'month': '2025-01', 'category': 'unknown', 'count': 7},
    ]);
    expect(
      [for (final m in months) '${m.year}-${m.month}:${m.total}'],
      ['2024-12:2', '2025-1:0', '2025-2:1'],
    );
  });
}
