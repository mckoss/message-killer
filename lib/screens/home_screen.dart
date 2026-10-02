import 'package:flutter/material.dart';

import '../format.dart';
import '../native_api.dart';
import 'cleanup_flow.dart';
import 'restore_screen.dart';
import 'settings_screen.dart';
import 'spam_folder_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.api});

  final NativeApi api;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  AppStatus? _status;
  bool _busy = false;

  NativeApi get api => widget.api;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh(checkLaunchAction: true);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh(checkLaunchAction: true);
  }

  Future<void> _refresh({bool checkLaunchAction = false}) async {
    final status = await api.getStatus();
    if (!mounted) return;
    setState(() => _status = status);
    if (checkLaunchAction &&
        await api.takeLaunchAction() == 'cleanup' &&
        mounted) {
      await _cleanup();
    }
  }

  Future<void> _cleanup() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await runCleanup(context, api);
    } finally {
      if (mounted) setState(() => _busy = false);
      await _refresh();
    }
  }

  Future<void> _open(Widget screen) async {
    await Navigator.of(context)
        .push(MaterialPageRoute<void>(builder: (_) => screen));
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Message Killer'),
        actions: [
          IconButton(
            tooltip: 'Filter settings',
            icon: const Icon(Icons.tune),
            onPressed: () => _open(SettingsScreen(api: api)),
          ),
        ],
      ),
      body: status == null
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  if (status.isDefaultSmsApp) _stillDefaultCard(status),
                  if (status.restorable > 0) _restoreCard(status),
                  if (!status.smsPermission ||
                      !status.notificationAccess ||
                      !status.contactsPermission)
                    _setupCard(status),
                  _liveFilterCard(status),
                  _spamFolderCard(status),
                  _cleanupCard(status),
                  if (status.version.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 16),
                      child: Text(
                        'Message Killer ${status.version}',
                        textAlign: TextAlign.center,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ),
                ],
              ),
            ),
    );
  }

  Widget _restoreCard(AppStatus status) {
    return Card(
      child: ListTile(
        leading: const Icon(Icons.restore),
        title: Text(
          '${plural(status.restorable, 'deleted text')} no longer look like spam',
        ),
        subtitle: const Text('Review and put them back in your inbox'),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => _open(RestoreScreen(api: api)),
      ),
    );
  }

  Widget _stillDefaultCard(AppStatus status) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      color: scheme.errorContainer,
      child: ListTile(
        leading: Icon(Icons.warning_amber, color: scheme.onErrorContainer),
        title: Text(
          'Message Killer is still your default SMS app',
          style: TextStyle(color: scheme.onErrorContainer),
        ),
        subtitle: Text(
          'Switch back to ${status.messagingAppName} so you don\'t miss picture and group messages.',
          style: TextStyle(color: scheme.onErrorContainer),
        ),
        onTap: () => _open(SwitchBackScreen(api: api)),
      ),
    );
  }

  Widget _setupCard(AppStatus status) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Finish setup',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            _SetupStep(
              done: status.smsPermission,
              title: 'Allow access to your texts',
              subtitle: 'Needed to find spam texts already in your inbox.',
              action: 'Allow',
              onPressed: () async {
                await api.requestPermissions();
                await _refresh();
              },
            ),
            _SetupStep(
              done: status.contactsPermission,
              title: 'Never filter your contacts',
              subtitle: 'Texts from anyone in your contacts are never silenced or deleted.',
              action: 'Allow',
              onPressed: () async {
                await api.requestPermissions();
                await _refresh();
              },
            ),
            _SetupStep(
              done: status.notificationAccess,
              title: 'Turn on the live filter',
              subtitle:
                  'Lets Message Killer silence spam texts as they arrive. '
                  'Choose Message Killer and turn it on.',
              action: 'Open',
              onPressed: api.openNotificationAccessSettings,
            ),
            const SizedBox(height: 8),
            Text(
              'If a switch is greyed out ("Restricted setting"), open App info, tap ⋮ → '
              '"Allow restricted settings", then try again.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: api.openAppSettings,
                child: const Text('Open App info'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _liveFilterCard(AppStatus status) {
    final active = status.liveFilter && status.notificationAccess;
    return Card(
      child: SwitchListTile(
        secondary: Icon(
          active ? Icons.notifications_off : Icons.notifications_active,
        ),
        title: const Text('Live filter'),
        subtitle: Text(
          !status.notificationAccess
              ? 'Needs notification access (see setup above)'
              : status.liveFilter
              ? 'Silenced ${plural(status.silencedToday, 'spam text')} in the last 24 hours'
              : 'Off: spam texts will notify you normally',
        ),
        value: status.liveFilter,
        onChanged: (value) async {
          await api.updateSettings(liveFilter: value);
          await _refresh();
        },
      ),
    );
  }

  Widget _spamFolderCard(AppStatus status) {
    return Card(
      child: ListTile(
        leading: const Icon(Icons.folder_special),
        title: const Text('Spam folder'),
        subtitle: Text(
          '${plural(status.totalSpam, 'message')} · kept ${status.retentionDays} days',
        ),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => _open(
          SpamFolderScreen(api: api, retentionDays: status.retentionDays),
        ),
      ),
    );
  }

  Widget _cleanupCard(AppStatus status) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            ListTile(
              leading: const Icon(Icons.cleaning_services),
              title: const Text('Clean up inbox'),
              subtitle: Text(
                status.pendingDelete > 0
                    ? '${plural(status.pendingDelete, 'spam text')} waiting to be deleted'
                    : 'Find spam texts in your inbox, save them to the Spam folder, and delete them',
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: FilledButton.icon(
                icon: _busy
                    ? const SizedBox.square(
                        dimension: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.search),
                label: const Text('Scan now'),
                onPressed: _busy || !status.smsPermission ? null : _cleanup,
              ),
            ),
            SwitchListTile(
              title: const Text('Daily cleanup reminder'),
              subtitle: const Text(
                'Scans once a day and notifies you when spam texts are ready to delete',
              ),
              value: status.dailyCleanup,
              onChanged: (value) async {
                await api.updateSettings(dailyCleanup: value);
                await _refresh();
              },
            ),
          ],
        ),
      ),
    );
  }
}

class _SetupStep extends StatelessWidget {
  const _SetupStep({
    required this.done,
    required this.title,
    required this.subtitle,
    required this.action,
    required this.onPressed,
  });

  final bool done;
  final String title;
  final String subtitle;
  final String action;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Icon(
        done ? Icons.check_circle : Icons.radio_button_unchecked,
        color: done ? Theme.of(context).colorScheme.primary : null,
      ),
      title: Text(title),
      subtitle: Text(subtitle),
      trailing: done
          ? null
          : OutlinedButton(onPressed: onPressed, child: Text(action)),
    );
  }
}
