import 'package:message_killer/native_api.dart';

class FakeNativeApi implements NativeApi {
  FakeNativeApi({
    this.smsPermission = true,
    this.notificationAccess = true,
    this.contactsPermission = true,
    this.isDefaultSmsApp = false,
    int pending = 0,
    List<SpamEntry>? spam,
    this.grantRole = true,
  }) : spam =
           spam ??
           [
             for (var i = 0; i < pending; i++)
               spamEntry(
                 i + 1,
                 status: SpamStatus.pendingDelete,
                 sender: '8802$i',
               ),
           ];

  bool smsPermission;
  bool notificationAccess;
  bool contactsPermission;
  bool isDefaultSmsApp;
  int get pending =>
      spam.where((e) => e.status == SpamStatus.pendingDelete).length;
  bool grantRole;
  bool liveFilter = true;
  bool dailyCleanup = false;
  List<String> customKeywords = [];
  List<String> allowedSenders = [];
  Set<SpamCategory> categories = {...SpamCategory.values};
  List<FlaggedSender> flagged = [];
  List<SpamEntry> spam;
  String? launchAction;
  final calls = <String>[];

  @override
  Future<AppStatus> getStatus() async => AppStatus(
    smsPermission: smsPermission,
    notificationAccess: notificationAccess,
    contactsPermission: contactsPermission,
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
    contactsPermission = true;
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
    // The user picks whichever app wasn't default: Message Killer before a
    // manual switch, their messaging app when switching back.
    isDefaultSmsApp = !isDefaultSmsApp;
  }

  @override
  Future<bool> requestDefaultSmsRole() async {
    calls.add('requestDefaultSmsRole');
    isDefaultSmsApp = grantRole;
    return grantRole;
  }

  @override
  Future<ScanResult> scanInbox({bool full = false}) async {
    calls.add(full ? 'fullScan' : 'scanInbox');
    return ScanResult(
      scanned: 100,
      newlyFiled: pending,
      pending: pending,
      texts: 90,
      pictureMessages: 10,
      full: full,
    );
  }

  @override
  Future<String> scanProgress() async => 'Reading your messages…';

  @override
  Future<DeleteResult> deletePending() async {
    calls.add('deletePending');
    final deleted = pending;
    spam = [
      for (final e in spam)
        e.status == SpamStatus.pendingDelete
            ? SpamEntry(
                id: e.id,
                source: e.source,
                status: SpamStatus.deleted,
                sender: e.sender,
                body: e.body,
                messageTime: e.messageTime,
                filedAt: e.filedAt,
                score: e.score,
                reasons: e.reasons,
              )
            : e,
    ];
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
  Future<int> allowSender(String sender) async {
    calls.add('allowSender');
    if (!allowedSenders.contains(sender)) allowedSenders.add(sender);
    final before = spam.length;
    spam.removeWhere(
      (e) => e.status == SpamStatus.pendingDelete && e.sender == sender,
    );
    return before - spam.length;
  }

  @override
  Future<ExportResult> exportSpam({bool pendingOnly = false}) async {
    calls.add(pendingOnly ? 'exportPending' : 'exportSpam');
    return ExportResult(
      count: pendingOnly ? pending : spam.length,
      files: const ['spam.json', 'spam.csv'],
    );
  }

  @override
  Future<FilterSettings> getSettings() async => FilterSettings(
    liveFilter: liveFilter,
    dailyCleanup: dailyCleanup,
    customKeywords: List.of(customKeywords),
    allowedSenders: List.of(allowedSenders),
    categories: {...categories},
  );

  @override
  Future<void> updateSettings({
    bool? liveFilter,
    bool? dailyCleanup,
    List<String>? customKeywords,
    List<String>? allowedSenders,
    Set<SpamCategory>? categories,
  }) async {
    if (categories != null) this.categories = categories;
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
      spam: political,
      category: political ? SpamCategory.political : null,
      reasons: political
          ? ['Fundraising platform (ActBlue/WinRed/Anedot)']
          : [],
    );
  }

  @override
  Future<List<FlaggedSender>> listFlagged() async => List.of(flagged);

  @override
  Future<void> unflagSender(String sender) async {
    calls.add('unflag:$sender');
    flagged.removeWhere((f) => f.sender == sender);
  }

  @override
  Future<ExportResult> exportFlagged() async {
    calls.add('exportFlagged');
    return ExportResult(count: flagged.length, files: const ['flagged.json']);
  }
}

SpamEntry spamEntry(
  int id, {
  SpamStatus status = SpamStatus.silenced,
  String sender = '88022',
  SpamCategory category = SpamCategory.political,
}) => SpamEntry(
  category: category,
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
