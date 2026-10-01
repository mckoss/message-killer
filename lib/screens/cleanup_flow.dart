import 'package:flutter/material.dart';

import '../format.dart';
import '../native_api.dart';

/// Scan → confirm → become default SMS app → delete → switch back.
Future<void> runCleanup(BuildContext context, NativeApi api) async {
  final messenger = ScaffoldMessenger.of(context);
  final navigator = Navigator.of(context);

  final status = await api.getStatus();
  if (!status.smsPermission) {
    messenger.showSnackBar(
      const SnackBar(content: Text('Grant SMS permission first.')),
    );
    return;
  }

  final scan = await api.scanInbox();
  if (scan.pending == 0) {
    messenger.showSnackBar(
      SnackBar(
        content: Text(
          'No political texts found in ${plural(scan.scanned, 'message')}.',
        ),
      ),
    );
    return;
  }
  if (!context.mounted) return;

  final confirmed = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text('Delete ${plural(scan.pending, 'political text')}?'),
      content: Text(
        'They stay in Message Killer\'s Spam folder for ${status.retentionDays} days.\n\n'
        'Android only lets the default SMS app delete messages, so Message Killer will ask '
        'to become your default SMS app for a moment. Right after, you\'ll switch back to '
        '${status.messagingAppName}.',
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: const Text('Not now'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, true),
          child: const Text('Delete'),
        ),
      ],
    ),
  );
  if (confirmed != true) return;

  final granted = await api.requestDefaultSmsRole();
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

  final result = await api.deletePending();
  await navigator.push(
    MaterialPageRoute<void>(
      fullscreenDialog: true,
      builder: (_) => SwitchBackScreen(api: api, result: result),
    ),
  );
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
                  'Deleted ${plural(result.deleted, 'political text')}',
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
