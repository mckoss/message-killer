import 'package:flutter/services.dart';

/// Snapshot of permissions, settings and Spam folder counts.
class AppStatus {
  const AppStatus({
    required this.smsPermission,
    required this.notificationAccess,
    required this.isDefaultSmsApp,
    required this.defaultSmsApp,
    required this.previousDefaultSmsApp,
    required this.liveFilter,
    required this.dailyCleanup,
    required this.totalSpam,
    required this.pendingDelete,
    required this.silencedToday,
    required this.retentionDays,
  });

  final bool smsPermission;
  final bool notificationAccess;
  final bool isDefaultSmsApp;
  final String? defaultSmsApp;
  final String? previousDefaultSmsApp;
  final bool liveFilter;
  final bool dailyCleanup;
  final int totalSpam;
  final int pendingDelete;
  final int silencedToday;
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
      isDefaultSmsApp: m['isDefaultSmsApp'] == true,
      defaultSmsApp: m['defaultSmsApp'] as String?,
      previousDefaultSmsApp: m['previousDefaultSmsApp'] as String?,
      liveFilter: m['liveFilter'] == true,
      dailyCleanup: m['dailyCleanup'] == true,
      totalSpam: (counts['total'] as num?)?.toInt() ?? 0,
      pendingDelete: (counts['pending'] as num?)?.toInt() ?? 0,
      silencedToday: (counts['silencedToday'] as num?)?.toInt() ?? 0,
      retentionDays: (m['retentionDays'] as num?)?.toInt() ?? 90,
    );
  }
}

enum SpamStatus { silenced, pendingDelete, deleted }

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

  String get statusLabel => switch (status) {
    SpamStatus.silenced => 'Notification silenced',
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
  );
}

class FilterSettings {
  const FilterSettings({
    required this.liveFilter,
    required this.dailyCleanup,
    required this.customKeywords,
    required this.allowedSenders,
  });

  final bool liveFilter;
  final bool dailyCleanup;
  final List<String> customKeywords;
  final List<String> allowedSenders;

  factory FilterSettings.fromMap(Map<Object?, Object?> m) => FilterSettings(
    liveFilter: m['liveFilter'] == true,
    dailyCleanup: m['dailyCleanup'] == true,
    customKeywords: ((m['customKeywords'] as List?) ?? const []).cast<String>(),
    allowedSenders: ((m['allowedSenders'] as List?) ?? const []).cast<String>(),
  );
}

class ClassifyResult {
  const ClassifyResult({
    required this.score,
    required this.political,
    required this.reasons,
  });

  final double score;
  final bool political;
  final List<String> reasons;
}

class ScanResult {
  const ScanResult({
    required this.scanned,
    required this.newlyFiled,
    required this.pending,
  });

  final int scanned;
  final int newlyFiled;
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
  Future<ScanResult> scanInbox();
  Future<DeleteResult> deletePending();
  Future<List<SpamEntry>> listSpam();
  Future<void> removeSpam(int id, {bool allowSender = false});
  Future<ExportResult> exportSpam();
  Future<FilterSettings> getSettings();
  Future<void> updateSettings({
    bool? liveFilter,
    bool? dailyCleanup,
    List<String>? customKeywords,
    List<String>? allowedSenders,
  });
  Future<ClassifyResult> classify(String text, {String? sender});
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
  Future<ScanResult> scanInbox() async {
    final m = await _map('scanInbox');
    return ScanResult(
      scanned: (m['scanned'] as num?)?.toInt() ?? 0,
      newlyFiled: (m['newlyFiled'] as num?)?.toInt() ?? 0,
      pending: (m['pending'] as num?)?.toInt() ?? 0,
    );
  }

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
  Future<ExportResult> exportSpam() async {
    final m = await _map('exportSpam');
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
  }) => _channel.invokeMethod('updateSettings', {
    'liveFilter': ?liveFilter,
    'dailyCleanup': ?dailyCleanup,
    'customKeywords': ?customKeywords,
    'allowedSenders': ?allowedSenders,
  });

  @override
  Future<ClassifyResult> classify(String text, {String? sender}) async {
    final m = await _map('classify', {'text': text, 'sender': sender});
    return ClassifyResult(
      score: (m['score'] as num?)?.toDouble() ?? 0,
      political: m['political'] == true,
      reasons: ((m['reasons'] as List?) ?? const []).cast<String>(),
    );
  }
}
