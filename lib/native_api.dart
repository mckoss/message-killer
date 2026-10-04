import 'package:flutter/services.dart';

import 'format.dart';

/// Snapshot of permissions, settings and Spam folder counts.
class AppStatus {
  const AppStatus({
    required this.smsPermission,
    required this.notificationAccess,
    this.contactsPermission = false,
    required this.isDefaultSmsApp,
    required this.defaultSmsApp,
    required this.previousDefaultSmsApp,
    required this.liveFilter,
    required this.dailyCleanup,
    required this.totalSpam,
    required this.pendingDelete,
    required this.silencedToday,
    this.restorable = 0,
    this.version = '',
    required this.retentionDays,
  });

  final bool smsPermission;
  final bool notificationAccess;
  final bool contactsPermission;
  final bool isDefaultSmsApp;
  final String? defaultSmsApp;
  final String? previousDefaultSmsApp;
  final bool liveFilter;
  final bool dailyCleanup;
  final int totalSpam;
  final int pendingDelete;
  final int silencedToday;

  /// Deleted texts that no longer count as spam and can be put back.
  final int restorable;

  /// "0.16.0 (build 42)".
  final String version;
  final int retentionDays;

  /// The user's normal messaging app, for "switch back to ..." prompts.
  String get messagingAppName =>
      (isDefaultSmsApp ? previousDefaultSmsApp : defaultSmsApp) ??
      'your messaging app';

  factory AppStatus.fromMap(Map<Object?, Object?> m) {
    final counts = (m['counts'] as Map?) ?? const {};
    return AppStatus(
      smsPermission: m['smsPermission'] == true,
      notificationAccess: m['notificationAccess'] == true,
      contactsPermission: m['contactsPermission'] == true,
      isDefaultSmsApp: m['isDefaultSmsApp'] == true,
      defaultSmsApp: m['defaultSmsApp'] as String?,
      previousDefaultSmsApp: m['previousDefaultSmsApp'] as String?,
      liveFilter: m['liveFilter'] == true,
      dailyCleanup: m['dailyCleanup'] == true,
      totalSpam: (counts['total'] as num?)?.toInt() ?? 0,
      pendingDelete: (counts['pending'] as num?)?.toInt() ?? 0,
      silencedToday: (counts['silencedToday'] as num?)?.toInt() ?? 0,
      restorable: (counts['restorable'] as num?)?.toInt() ?? 0,
      version: m['version'] as String? ?? '',
      retentionDays: (m['retentionDays'] as num?)?.toInt() ?? 90,
    );
  }
}

enum SpamStatus { silenced, pendingDelete, deleted }

/// Kind of spam; keys match the native Classifier.Category keys.
enum SpamCategory {
  political('political', 'Political'),
  commercial('commercial', 'Commercial'),
  phishing('phishing', 'Phishing / scam');

  const SpamCategory(this.key, this.label);

  final String key;
  final String label;

  static SpamCategory? fromKey(Object? key) =>
      values.where((c) => c.key == key).firstOrNull;
}

/// One message in the Spam folder.
class SpamEntry {
  const SpamEntry({
    required this.id,
    required this.source,
    required this.status,
    required this.sender,
    required this.body,
    required this.messageTime,
    required this.filedAt,
    required this.score,
    required this.reasons,
    this.category = SpamCategory.political,
  });

  final int id;
  final String source;
  final SpamStatus status;
  final String sender;
  final String body;
  final DateTime messageTime;
  final DateTime filedAt;
  final double score;
  final List<String> reasons;
  final SpamCategory category;

  String get statusLabel => switch (status) {
    SpamStatus.silenced =>
      'Notification silenced. If it stays like this after a scan, it came '
          'as an RCS chat message, which Android doesn\'t let other apps delete.',
    SpamStatus.pendingDelete => 'Waiting to be deleted from inbox',
    SpamStatus.deleted => 'Deleted from inbox',
  };

  factory SpamEntry.fromMap(Map<Object?, Object?> m) => SpamEntry(
    id: (m['id'] as num).toInt(),
    source: m['source'] as String? ?? '',
    status: switch (m['status']) {
      'pending_delete' => SpamStatus.pendingDelete,
      'deleted' => SpamStatus.deleted,
      _ => SpamStatus.silenced,
    },
    sender: m['sender'] as String? ?? '',
    body: m['body'] as String? ?? '',
    messageTime: DateTime.fromMillisecondsSinceEpoch(
      (m['messageTime'] as num).toInt(),
    ),
    filedAt: DateTime.fromMillisecondsSinceEpoch((m['filedAt'] as num).toInt()),
    score: (m['score'] as num?)?.toDouble() ?? 0,
    reasons: ((m['reasons'] as List?) ?? const []).cast<String>(),
    category: SpamCategory.fromKey(m['category']) ?? SpamCategory.political,
  );
}

class FilterSettings {
  const FilterSettings({
    required this.liveFilter,
    required this.dailyCleanup,
    required this.customKeywords,
    required this.allowedSenders,
    this.categories = const {...SpamCategory.values},
  });

  final bool liveFilter;
  final bool dailyCleanup;
  final List<String> customKeywords;
  final List<String> allowedSenders;

  /// Which kinds of spam are filtered.
  final Set<SpamCategory> categories;

  factory FilterSettings.fromMap(Map<Object?, Object?> m) => FilterSettings(
    liveFilter: m['liveFilter'] == true,
    dailyCleanup: m['dailyCleanup'] == true,
    customKeywords: ((m['customKeywords'] as List?) ?? const []).cast<String>(),
    allowedSenders: ((m['allowedSenders'] as List?) ?? const []).cast<String>(),
    categories: m['categories'] == null
        ? {...SpamCategory.values}
        : {for (final k in m['categories'] as List) ?SpamCategory.fromKey(k)},
  );
}

/// A sender flagged for sending political texts (everything from it is filtered).
class FlaggedSender {
  const FlaggedSender({
    required this.sender,
    required this.flaggedAt,
    required this.messages,
  });

  /// Normalized: last 10 digits of a phone number, or a lower-cased name.
  final String sender;
  final DateTime flaggedAt;

  /// How many of its texts are in the Spam folder.
  final int messages;

  /// "(504) 294-4686" for 10-digit numbers; otherwise as stored.
  String get display {
    final s = sender;
    if (RegExp(r'^\d{10}$').hasMatch(s)) {
      return '(${s.substring(0, 3)}) ${s.substring(3, 6)}-${s.substring(6)}';
    }
    return s;
  }

  factory FlaggedSender.fromMap(Map<Object?, Object?> m) => FlaggedSender(
    sender: m['sender'] as String? ?? '',
    flaggedAt: DateTime.fromMillisecondsSinceEpoch(
      (m['addedAt'] as num?)?.toInt() ?? 0,
    ),
    messages: (m['messages'] as num?)?.toInt() ?? 0,
  );
}

class ClassifyResult {
  const ClassifyResult({
    required this.score,
    required this.spam,
    required this.reasons,
    this.category,
  });

  final double score;
  final bool spam;
  final List<String> reasons;

  /// The winning category when [spam]; null otherwise.
  final SpamCategory? category;
}

class ScanResult {
  const ScanResult({
    required this.scanned,
    required this.newlyFiled,
    required this.pending,
    this.texts = 0,
    this.pictureMessages = 0,
    this.full = true,
  });

  final int scanned;
  final int newlyFiled;
  final int texts;
  final int pictureMessages;

  /// Every message was checked (vs. only ones since the last scan).
  final bool full;

  /// "Checked 27,412 texts + 2,624 picture messages" (+ "since the last scan").
  String get summary {
    final what = pictureMessages > 0
        ? '${plural(texts, 'text')} + ${plural(pictureMessages, 'picture message')}'
        : plural(texts, 'text');
    return 'Checked $what${full ? '' : ' since the last scan'}';
  }

  final int pending;
}

class DeleteResult {
  const DeleteResult({required this.deleted, required this.failed});

  final int deleted;
  final int failed;
}

class ExportResult {
  const ExportResult({required this.count, required this.files});

  final int count;
  final List<String> files;
}

/// Everything the UI needs from the Android side. Abstract so tests can fake it.
abstract class NativeApi {
  Future<AppStatus> getStatus();
  Future<String?> takeLaunchAction();
  Future<bool> requestPermissions();
  Future<void> openNotificationAccessSettings();
  Future<void> openAppSettings();
  Future<void> openDefaultAppsSettings();
  Future<bool> requestDefaultSmsRole();

  /// [full]: re-check every message, not just ones since the last scan.
  Future<ScanResult> scanInbox({bool full = false});

  /// What the running scan or delete is doing right now (empty when idle).
  Future<String> scanProgress();
  Future<DeleteResult> deletePending();
  Future<List<SpamEntry>> listSpam();
  Future<void> removeSpam(int id, {bool allowSender = false});

  /// [pendingOnly]: only texts waiting to be deleted (the review screen).
  Future<ExportResult> exportSpam({bool pendingOnly = false});

  /// Allow-lists [sender] and drops its texts that were waiting to be deleted.
  /// Returns how many texts were kept.
  Future<int> allowSender(String sender);
  Future<FilterSettings> getSettings();
  Future<void> updateSettings({
    bool? liveFilter,
    bool? dailyCleanup,
    List<String>? customKeywords,
    List<String>? allowedSenders,
    Set<SpamCategory>? categories,
  });
  Future<ClassifyResult> classify(String text, {String? sender});

  /// Deleted texts that no longer count as spam under the current rules.
  Future<List<SpamEntry>> listRestorable();

  /// How many of those there are (slow: re-checks every deleted entry).
  Future<int> countRestorable();

  /// Marks entries as really spam: they're never offered for restore again.
  Future<void> confirmSpam(List<int> ids);

  /// Puts Spam folder entries back in the inbox (needs the default SMS role).
  /// Returns how many were restored.
  Future<int> restore(List<int> ids);

  /// Senders flagged for political texts, newest first.
  Future<List<FlaggedSender>> listFlagged();

  /// Un-flags [sender] (normalized, as in [FlaggedSender.sender]).
  Future<void> unflagSender(String sender);

  /// Saves the flagged-senders list to Downloads.
  Future<ExportResult> exportFlagged();
}

class MethodChannelNativeApi implements NativeApi {
  static const _channel = MethodChannel('message_killer/native');

  Future<Map<Object?, Object?>> _map(String method, [Object? args]) async =>
      (await _channel.invokeMethod<Map<Object?, Object?>>(method, args)) ??
      const {};

  @override
  Future<AppStatus> getStatus() async =>
      AppStatus.fromMap(await _map('getStatus'));

  @override
  Future<String?> takeLaunchAction() =>
      _channel.invokeMethod<String>('takeLaunchAction');

  @override
  Future<bool> requestPermissions() async =>
      await _channel.invokeMethod<bool>('requestPermissions') ?? false;

  @override
  Future<void> openNotificationAccessSettings() =>
      _channel.invokeMethod('openNotificationAccessSettings');

  @override
  Future<void> openAppSettings() => _channel.invokeMethod('openAppSettings');

  @override
  Future<void> openDefaultAppsSettings() =>
      _channel.invokeMethod('openDefaultAppsSettings');

  @override
  Future<bool> requestDefaultSmsRole() async =>
      await _channel.invokeMethod<bool>('requestDefaultSmsRole') ?? false;

  @override
  Future<ScanResult> scanInbox({bool full = false}) async {
    final m = await _map('scanInbox', {'full': full});
    return ScanResult(
      scanned: (m['scanned'] as num?)?.toInt() ?? 0,
      newlyFiled: (m['newlyFiled'] as num?)?.toInt() ?? 0,
      pending: (m['pending'] as num?)?.toInt() ?? 0,
      texts: (m['texts'] as num?)?.toInt() ?? 0,
      pictureMessages: (m['pictureMessages'] as num?)?.toInt() ?? 0,
      full: m['full'] != false,
    );
  }

  @override
  Future<String> scanProgress() async =>
      await _channel.invokeMethod<String>('scanProgress') ?? '';

  @override
  Future<DeleteResult> deletePending() async {
    final m = await _map('deletePending');
    return DeleteResult(
      deleted: (m['deleted'] as num?)?.toInt() ?? 0,
      failed: (m['failed'] as num?)?.toInt() ?? 0,
    );
  }

  @override
  Future<List<SpamEntry>> listSpam() async {
    final list =
        await _channel.invokeMethod<List<Object?>>('listSpam') ?? const [];
    return list
        .map((e) => SpamEntry.fromMap(e! as Map<Object?, Object?>))
        .toList();
  }

  @override
  Future<void> removeSpam(int id, {bool allowSender = false}) => _channel
      .invokeMethod('removeSpam', {'id': id, 'allowSender': allowSender});

  @override
  Future<int> allowSender(String sender) async =>
      await _channel.invokeMethod<int>('allowSender', {'sender': sender}) ?? 0;

  @override
  Future<ExportResult> exportSpam({bool pendingOnly = false}) async {
    final m = await _map('exportSpam', {'pendingOnly': pendingOnly});
    return ExportResult(
      count: (m['count'] as num?)?.toInt() ?? 0,
      files: ((m['files'] as List?) ?? const []).cast<String>(),
    );
  }

  @override
  Future<FilterSettings> getSettings() async =>
      FilterSettings.fromMap(await _map('getSettings'));

  @override
  Future<void> updateSettings({
    bool? liveFilter,
    bool? dailyCleanup,
    List<String>? customKeywords,
    List<String>? allowedSenders,
    Set<SpamCategory>? categories,
  }) => _channel.invokeMethod('updateSettings', {
    'liveFilter': ?liveFilter,
    'dailyCleanup': ?dailyCleanup,
    'customKeywords': ?customKeywords,
    'allowedSenders': ?allowedSenders,
    'categories': ?categories?.map((c) => c.key).toList(),
  });

  @override
  Future<ClassifyResult> classify(String text, {String? sender}) async {
    final m = await _map('classify', {'text': text, 'sender': sender});
    return ClassifyResult(
      score: (m['score'] as num?)?.toDouble() ?? 0,
      spam: m['spam'] == true,
      reasons: ((m['reasons'] as List?) ?? const []).cast<String>(),
      category: SpamCategory.fromKey(m['category']),
    );
  }

  @override
  Future<List<SpamEntry>> listRestorable() async {
    final list =
        await _channel.invokeMethod<List<Object?>>('listRestorable') ??
        const [];
    return list
        .map((e) => SpamEntry.fromMap(e! as Map<Object?, Object?>))
        .toList();
  }

  @override
  Future<int> countRestorable() async =>
      await _channel.invokeMethod<int>('countRestorable') ?? 0;

  @override
  Future<void> confirmSpam(List<int> ids) =>
      _channel.invokeMethod('confirmSpam', {'ids': ids});

  @override
  Future<int> restore(List<int> ids) async {
    final m = await _map('restore', {'ids': ids});
    return (m['restored'] as num?)?.toInt() ?? 0;
  }

  @override
  Future<List<FlaggedSender>> listFlagged() async {
    final list =
        await _channel.invokeMethod<List<Object?>>('listFlagged') ?? const [];
    return list
        .map((e) => FlaggedSender.fromMap(e! as Map<Object?, Object?>))
        .toList();
  }

  @override
  Future<void> unflagSender(String sender) =>
      _channel.invokeMethod('unflagSender', {'sender': sender});

  @override
  Future<ExportResult> exportFlagged() async {
    final m = await _map('exportFlagged');
    return ExportResult(
      count: (m['count'] as num?)?.toInt() ?? 0,
      files: ((m['files'] as List?) ?? const []).cast<String>(),
    );
  }
}
