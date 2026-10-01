import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:message_killer/main.dart';
import 'package:message_killer/native_api.dart';

import 'fake_native_api.dart';

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
    expect(find.text('Clean up now'), findsOneWidget);
  });

  testWidgets('cleanup: confirm, take SMS role, delete, then switch back', (
    tester,
  ) async {
    final api = FakeNativeApi(pending: 3);
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Clean up now'));
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
    expect(find.text('Deleted 3 political texts'), findsOneWidget);
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

    await tester.tap(find.text('Clean up now'));
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

    await tester.tap(find.text('Clean up now'));
    await pumpABit(tester);
    await tester.tap(find.widgetWithText(FilledButton, 'Delete 2'));
    await pumpABit(tester);
    await tester.tap(find.widgetWithText(FilledButton, 'Open Default apps'));
    await pumpABit(tester);
    expect(api.isDefaultSmsApp, isTrue);

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await pumpABit(tester);
    expect(api.calls, contains('deletePending'));
    expect(find.text('Deleted 2 political texts'), findsOneWidget);
  });

  testWidgets('cleanup with nothing to delete shows a message', (tester) async {
    await tester.pumpWidget(MessageKillerApp(api: FakeNativeApi()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Clean up now'));
    await tester.pumpAndSettle();
    expect(
      find.text('No political texts found in 100 messages.'),
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
    'spam folder lists entries and "Not political" allow-lists the sender',
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
        find.widgetWithText(OutlinedButton, 'Not political: allow sender'),
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

    await tester.enterText(
      find.widgetWithText(TextField, 'e.g. a candidate\'s name'),
      'Jane Doe',
    );
    await tester.tap(find.byTooltip('Add').first);
    await tester.pumpAndSettle();
    expect(api.customKeywords, ['Jane Doe']);
    expect(find.widgetWithText(InputChip, 'Jane Doe'), findsOneWidget);

    await tester.enterText(
      find.widgetWithText(TextField, 'Message text'),
      'Give at actblue.com',
    );
    await tester.scrollUntilVisible(
      find.text('Test'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.text('Test'));
    await tester.pumpAndSettle();
    expect(find.text('Would be filtered'), findsOneWidget);
  });

  testWidgets('preview: "Allow sender" keeps that sender\'s texts', (
    tester,
  ) async {
    final api = FakeNativeApi(pending: 2);
    await tester.pumpWidget(MessageKillerApp(api: api));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Clean up now'));
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
}
