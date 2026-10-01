import 'package:flutter/material.dart';

import '../format.dart';
import '../native_api.dart';
import 'export_action.dart';
import 'spam_folder_screen.dart';

/// Lists exactly which texts a cleanup will delete. Pops with true to proceed.
class CleanupPreviewScreen extends StatefulWidget {
  const CleanupPreviewScreen({
    super.key,
    required this.api,
    required this.status,
  });

  final NativeApi api;
  final AppStatus status;

  @override
  State<CleanupPreviewScreen> createState() => _CleanupPreviewScreenState();
}

class _CleanupPreviewScreenState extends State<CleanupPreviewScreen> {
  List<SpamEntry>? _pending;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final all = await widget.api.listSpam();
    if (!mounted) return;
    setState(
      () => _pending = all
          .where((e) => e.status == SpamStatus.pendingDelete)
          .toList(),
    );
  }

  Future<void> _open(SpamEntry entry) async {
    final removed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) => SpamEntryScreen(api: widget.api, entry: entry),
      ),
    );
    if (removed == true) await _load();
  }

  @override
  Widget build(BuildContext context) {
    final pending = _pending;
    final theme = Theme.of(context);
    final count = pending?.length ?? 0;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Review before deleting'),
        actions: [ExportSpamButton(api: widget.api)],
      ),
      body: pending == null
          ? const Center(child: CircularProgressIndicator())
          : ListView.separated(
              itemCount: pending.length + 1,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, index) {
                if (index == 0) {
                  return Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      pending.isEmpty
                          ? 'Nothing left to delete.'
                          : '${plural(count, 'text')} will be deleted from your inbox. '
                                'Copies stay in the Spam folder for ${widget.status.retentionDays} days.\n\n'
                                'Tap a text to see why it was flagged, or mark it "Not political" to keep it. '
                                'Tip: export a backup first (download icon above).',
                      style: theme.textTheme.bodyMedium,
                    ),
                  );
                }
                final entry = pending[index - 1];
                return ListTile(
                  title: Text(
                    entry.sender.isEmpty ? 'Unknown sender' : entry.sender,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  subtitle: Text(
                    entry.body,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                  trailing: Text(formatShortDate(entry.messageTime)),
                  onTap: () => _open(entry),
                );
              },
            ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Android only lets the default SMS app delete texts, so Message Killer will '
                'briefly become your default SMS app, then help you switch back to '
                '${widget.status.messagingAppName}.',
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () => Navigator.pop(context, false),
                      child: const Text('Cancel'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      onPressed: count == 0
                          ? null
                          : () => Navigator.pop(context, true),
                      child: Text('Delete $count'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
