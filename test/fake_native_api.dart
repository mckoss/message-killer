import 'package:message_killer/native_api.dart';

class FakeNativeApi implements NativeApi {
  FakeNativeApi({
    this.smsPermission = true,
    this.notificationAccess = true,
    this.isDefaultSmsApp = false,
    this.pending = 0,
    List<SpamEntry>? spam,
    this.grantRole = true,
  }) : spam = spam ?? [];

  bool smsPermission;
  bool notificationAccess;
  bool isDefaultSmsApp;
  int pending;
  bool grantRole;
  bool liveFilter = true;
  bool dailyCleanup = false;
  List<String> customKeywords = [];
  List<String> allowedSenders = [];
  List<SpamEntry> spam;
  String? launchAction;
  final calls = <String>[];

  @override
  Future<AppStatus> getStatus() async => AppStatus(
    smsPermission: smsPermission,
    notificationAccess: notificationAccess,
    isDefaultSmsApp: isDefaultSmsApp,
    defaultSmsApp: isDefaultSmsApp ? 'Message Killer' : 'Messages',
    previousDefaultSmsApp: 'Messages',
    liveFilter: liveFilter,
    dailyCleanup: dailyCleanup,
    totalSpam: spam.length,
    pendingDelete: pending,
    silencedToday: spam.where((e) => e.status == SpamStatus.silenced).length,
    retentionDays: 90,
  );

  @override
  Future<String?> takeLaunchAction() async {
    final action = launchAction;
    launchAction = null;
    return action;
  }

  @override
  Future<bool> requestPermissions() async {
    calls.add('requestPermissions');
    smsPermission = true;
    return true;
  }

  @override
  Future<void> openNotificationAccessSettings() async =>
      calls.add('openNotificationAccessSettings');

  @override
  Future<void> openAppSettings() async => calls.add('openAppSettings');

  @override
  Future<void> openDefaultAppsSettings() async {
    calls.add('openDefaultAppsSettings');
    isDefaultSmsApp = false;
  }

  @override
  Future<bool> requestDefaultSmsRole() async {
    calls.add('requestDefaultSmsRole');
    isDefaultSmsApp = grantRole;
    return grantRole;
  }

  @override
  Future<ScanResult> scanInbox() async {
    calls.add('scanInbox');
    return ScanResult(scanned: 100, newlyFiled: pending, pending: pending);
  }

  @override
  Future<DeleteResult> deletePending() async {
    calls.add('deletePending');
    final deleted = pending;
    pending = 0;
    return DeleteResult(deleted: deleted, failed: 0);
  }

  @override
  Future<List<SpamEntry>> listSpam() async => List.of(spam);

  @override
  Future<void> removeSpam(int id, {bool allowSender = false}) async {
    final entry = spam.firstWhere((e) => e.id == id);
    spam.remove(entry);
    if (allowSender) allowedSenders.add(entry.sender);
  }

  @override
  Future<FilterSettings> getSettings() async => FilterSettings(
    liveFilter: liveFilter,
    dailyCleanup: dailyCleanup,
    customKeywords: List.of(customKeywords),
    allowedSenders: List.of(allowedSenders),
  );

  @override
  Future<void> updateSettings({
    bool? liveFilter,
    bool? dailyCleanup,
    List<String>? customKeywords,
    List<String>? allowedSenders,
  }) async {
    if (liveFilter != null) this.liveFilter = liveFilter;
    if (dailyCleanup != null) this.dailyCleanup = dailyCleanup;
    if (customKeywords != null) this.customKeywords = customKeywords;
    if (allowedSenders != null) this.allowedSenders = allowedSenders;
  }

  @override
  Future<ClassifyResult> classify(String text, {String? sender}) async {
    final political = text.toLowerCase().contains('actblue');
    return ClassifyResult(
      score: political ? 3 : 0,
      political: political,
      reasons: political
          ? ['Fundraising platform (ActBlue/WinRed/Anedot)']
          : [],
    );
  }
}

SpamEntry spamEntry(
  int id, {
  SpamStatus status = SpamStatus.silenced,
  String sender = '88022',
}) => SpamEntry(
  id: id,
  source: 'notification',
  status: status,
  sender: sender,
  body: 'Chip in \$5 now at actblue.com #$id',
  messageTime: DateTime(2026, 9, 30, 14, 5),
  filedAt: DateTime(2026, 9, 30, 14, 5),
  score: 6,
  reasons: const [
    'Asks for a donation',
    'Fundraising platform (ActBlue/WinRed/Anedot)',
  ],
);
