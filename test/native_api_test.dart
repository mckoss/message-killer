import 'package:flutter_test/flutter_test.dart';
import 'package:message_killer/format.dart';
import 'package:message_killer/native_api.dart';

void main() {
  test('AppStatus parses the platform map', () {
    final status = AppStatus.fromMap({
      'smsPermission': true,
      'notificationAccess': false,
      'isDefaultSmsApp': true,
      'defaultSmsApp': 'Message Killer',
      'previousDefaultSmsApp': 'Messages',
      'liveFilter': true,
      'dailyCleanup': false,
      'counts': {'total': 12, 'pending': 4, 'silencedToday': 2},
      'retentionDays': 90,
    });
    expect(status.totalSpam, 12);
    expect(status.pendingDelete, 4);
    expect(status.silencedToday, 2);
    expect(status.messagingAppName, 'Messages');
  });

  test('SpamEntry parses status and reasons', () {
    final entry = SpamEntry.fromMap({
      'id': 7,
      'source': 'sms',
      'status': 'pending_delete',
      'sender': '88022',
      'body': 'Chip in',
      'messageTime': 1700000000000,
      'filedAt': 1700000000000,
      'score': 4.5,
      'reasons': ['Asks for a donation'],
    });
    expect(entry.status, SpamStatus.pendingDelete);
    expect(entry.reasons, ['Asks for a donation']);
  });

  test('date formatting', () {
    final now = DateTime(2026, 10, 1, 12);
    expect(formatShortDate(DateTime(2026, 10, 1, 15, 5), now: now), '3:05 PM');
    expect(formatShortDate(DateTime(2026, 9, 30, 9), now: now), 'Sep 30');
    expect(formatShortDate(DateTime(2025, 1, 2), now: now), 'Jan 2, 2025');
    expect(plural(1, 'text'), '1 text');
    expect(plural(3, 'text'), '3 texts');
  });
}
