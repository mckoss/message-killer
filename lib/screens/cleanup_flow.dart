import 'dart:async';

import 'package:flutter/material.dart';

import '../format.dart';
import '../native_api.dart';
import 'cleanup_preview_screen.dart';

/// Scan → confirm → become default SMS app → delete → switch back.
/// [full]: re-check every message instead of only those since the last scan.
Future<void> runCleanup(
  BuildContext context,
  NativeApi api, {
  bool full = false,
}) async {
  final messenger = ScaffoldMessenger.of(context);
  final navigator = Navigator.of(context);

  final status = await api.getStatus();
  if (!status.smsPermission) {
    messenger.showSnackBar(
      const SnackBar(content: Text('Grant SMS permission first.')),
    );
    return;
  }

  if (!context.mounted) return;
  final scan = await _withProgress(
    context,
    api,
    'Scanning',
    () => api.scanInbox(full: full),
  );
  if (scan.pending == 0) {
    messenger.showSnackBar(
      SnackBar(content: Text('No spam texts to delete. ${scan.summary}.')),
    );
    return;
  }
  if (!context.mounted) return;

  final confirmed = await navigator.push<bool>(
    MaterialPageRoute(
      builder: (_) =>
          CleanupPreviewScreen(api: api, status: status, scan: scan),
    ),
  );
  if (confirmed != true) return;

  var granted = await api.requestDefaultSmsRole();
  if (!granted && context.mounted) {
    granted = await _switchManually(context, api);
  }
  if (!granted) {
    messenger.showSnackBar(
      const SnackBar(
        content: Text(
          'Nothing deleted: Message Killer needs to be the default SMS app briefly to delete texts.',
        ),
      ),
    );
    return;
  }

  if (!context.mounted) return;
  final result = await _withProgress(
    context,
    api,
    'Deleting',
    api.deletePending,
  );
  await navigator.push(
    MaterialPageRoute<void>(
      fullscreenDialog: true,
      builder: (_) => SwitchBackScreen(api: api, result: result),
    ),
  );
}

/// Runs a long native task (scan or delete) behind a dialog that shows the
/// native side's running counts, so it never looks frozen.
Future<T> _withProgress<T>(
  BuildContext context,
  NativeApi api,
  String title,
  Future<T> Function() task,
) async {
  final progress = ValueNotifier('Starting…');
  final timer = Timer.periodic(const Duration(milliseconds: 400), (_) async {
    final text = await api.scanProgress();
    if (text.isNotEmpty) progress.value = text;
  });
  final navigator = Navigator.of(context);
  showDialog<void>(
    context: context,
    barrierDismissible: false,
    builder: (context) => PopScope(
      canPop: false,
      child: AlertDialog(
        title: Text(title),
        content: Row(
          children: [
            const CircularProgressIndicator(),
            const SizedBox(width: 20),
            Expanded(
              child: ValueListenableBuilder<String>(
                valueListenable: progress,
                builder: (_, text, _) => Text(text),
              ),
            ),
          ],
        ),
      ),
    ),
  );
  try {
    return await task();
  } finally {
    timer.cancel();
    navigator.pop();
    progress.dispose();
  }
}

/// Fallback when the system prompt is refused or never appears: send the user to
/// Default apps to pick Message Killer by hand, then check when they come back.
Future<bool> _switchManually(BuildContext context, NativeApi api) async {
  final open = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('Switch manually?'),
      content: const Text(
        'Android didn\'t make Message Killer the default SMS app.\n\n'
        'You can do it yourself: in Default apps, tap "SMS app", choose Message Killer, '
        'then come back here. You\'ll switch back right after the cleanup.',
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, true),
          child: const Text('Open Default apps'),
        ),
      ],
    ),
  );
  if (open != true) return false;
  final resumed = _nextResume();
  await api.openDefaultAppsSettings();
  await resumed;
  return (await api.getStatus()).isDefaultSmsApp;
}

/// Completes the next time the app returns to the foreground.
Future<void> _nextResume() {
  final completer = Completer<void>();
  late final _ResumeObserver observer;
  observer = _ResumeObserver(() {
    WidgetsBinding.instance.removeObserver(observer);
    completer.complete();
  });
  WidgetsBinding.instance.addObserver(observer);
  return completer.future;
}

class _ResumeObserver extends WidgetsBindingObserver {
  _ResumeObserver(this.onResumed);

  final VoidCallback onResumed;

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) onResumed();
  }
}

/// Shown while Message Killer is the default SMS app; hard to dismiss until the user switches back.
class SwitchBackScreen extends StatefulWidget {
  const SwitchBackScreen({super.key, required this.api, this.result});

  final NativeApi api;
  final DeleteResult? result;

  @override
  State<SwitchBackScreen> createState() => _SwitchBackScreenState();
}

class _SwitchBackScreenState extends State<SwitchBackScreen>
    with WidgetsBindingObserver {
  AppStatus? _status;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final status = await widget.api.getStatus();
    if (!mounted) return;
    setState(() => _status = status);
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    final switchedBack = status != null && !status.isDefaultSmsApp;
    final result = widget.result;
    final theme = Theme.of(context);
    final appName = status?.messagingAppName ?? 'your messaging app';

    return PopScope(
      canPop: switchedBack,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Cleanup'),
          automaticallyImplyLeading: switchedBack,
        ),
        body: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (result != null) ...[
                Icon(
                  Icons.check_circle,
                  size: 64,
                  color: theme.colorScheme.primary,
                ),
                const SizedBox(height: 16),
                Text(
                  'Deleted ${plural(result.deleted, 'spam text')}',
                  style: theme.textTheme.headlineSmall,
                  textAlign: TextAlign.center,
                ),
                if (result.failed > 0)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      '${plural(result.failed, 'message')} couldn\'t be deleted and will be retried next time.',
                      textAlign: TextAlign.center,
                    ),
                  ),
                const SizedBox(height: 32),
              ],
              if (switchedBack) ...[
                Text(
                  'All set: $appName is your default messaging app again.',
                  style: theme.textTheme.titleMedium,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 24),
                FilledButton(
                  onPressed: () => Navigator.pop(context),
                  child: const Text('Done'),
                ),
              ] else ...[
                Card(
                  color: theme.colorScheme.errorContainer,
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      'Important: switch your default SMS app back to $appName now. '
                      'Until you do, picture and group messages can\'t be received.',
                      style: TextStyle(
                        color: theme.colorScheme.onErrorContainer,
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                const Text(
                  'In Default apps, tap "SMS app" and choose your messaging app, then come back here.',
                ),
                const SizedBox(height: 24),
                FilledButton.icon(
                  icon: const Icon(Icons.swap_horiz),
                  label: Text('Switch back to $appName'),
                  onPressed: widget.api.openDefaultAppsSettings,
                ),
                TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('Remind me later'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
