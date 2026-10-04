import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../format.dart';
import '../native_api.dart';
import 'loading_view.dart';
import 'allow_sender.dart';
import 'cleanup_flow.dart';
import 'export_action.dart';

class SpamFolderScreen extends StatefulWidget {
  const SpamFolderScreen({
    super.key,
    required this.api,
    required this.retentionDays,
  });

  final NativeApi api;
  final int retentionDays;

  @override
  State<SpamFolderScreen> createState() => _SpamFolderScreenState();
}

class _SpamFolderScreenState extends State<SpamFolderScreen> {
  List<SpamEntry>? _entries;

  /// Category filter; null = all.
  SpamCategory? _filter;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final entries = await widget.api.listSpam();
    if (mounted) setState(() => _entries = entries);
  }

  Future<void> _openEntry(SpamEntry entry) async {
    final removed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) => SpamEntryScreen(api: widget.api, entry: entry),
      ),
    );
    if (removed == true) await _load();
  }

  @override
  Widget build(BuildContext context) {
    final all = _entries;
    final entries = all
        ?.where((e) => _filter == null || e.category == _filter)
        .toList();
    return Scaffold(
      appBar: AppBar(
        title: const Text('Spam folder'),
        actions: [ExportSpamButton(api: widget.api)],
      ),
      body: entries == null
          ? const LoadingView('Loading the Spam folder…')
          : RefreshIndicator(
              onRefresh: _load,
              child: ListView.separated(
                itemCount: entries.length + 1,
                separatorBuilder: (_, _) => const Divider(height: 1),
                itemBuilder: (context, index) {
                  if (index == 0) {
                    return Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Wrap(
                            spacing: 8,
                            children: [
                              for (final c in <SpamCategory?>[
                                null,
                                ...SpamCategory.values,
                              ])
                                ChoiceChip(
                                  label: Text(
                                    '${c?.label ?? 'All'} '
                                    '(${all!.where((e) => c == null || e.category == c).length})',
                                  ),
                                  selected: _filter == c,
                                  onSelected: (_) =>
                                      setState(() => _filter = c),
                                ),
                            ],
                          ),
                          const SizedBox(height: 8),
                          Text(
                            all!.isEmpty
                                ? 'Nothing filtered yet. Silenced and deleted texts will show up here.'
                                : 'Filtered texts are kept here for ${widget.retentionDays} days, then removed for good.',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ],
                      ),
                    );
                  }
                  final entry = entries[index - 1];
                  return ListTile(
                    leading: Icon(_statusIcon(entry.status)),
                    title: Text(
                      entry.sender.isEmpty ? 'Unknown sender' : entry.sender,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    subtitle: Text(
                      '${entry.category.label} · ${entry.body}',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                    trailing: Text(formatShortDate(entry.messageTime)),
                    onTap: () => _openEntry(entry),
                  );
                },
              ),
            ),
    );
  }
}

IconData _statusIcon(SpamStatus status) => switch (status) {
  SpamStatus.silenced => Icons.notifications_off,
  SpamStatus.pendingDelete => Icons.schedule,
  SpamStatus.deleted => Icons.delete_outline,
};

class SpamEntryScreen extends StatelessWidget {
  const SpamEntryScreen({super.key, required this.api, required this.entry});

  final NativeApi api;
  final SpamEntry entry;

  Future<void> _allowSender(BuildContext context) async {
    final navigator = Navigator.of(context);
    if (await confirmAllowSender(context, api, entry)) navigator.pop(true);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(entry.sender.isEmpty ? 'Unknown sender' : entry.sender),
        actions: [
          IconButton(
            tooltip: 'Copy text',
            icon: const Icon(Icons.copy),
            onPressed: () {
              Clipboard.setData(ClipboardData(text: entry.body));
              ScaffoldMessenger.of(context)
                  .showSnackBar(const SnackBar(content: Text('Copied')));
            },
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(
            formatFullDate(entry.messageTime),
            style: theme.textTheme.bodySmall,
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: SelectableText(
                entry.body,
                style: theme.textTheme.bodyLarge,
              ),
            ),
          ),
          const SizedBox(height: 16),
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: Icon(_statusIcon(entry.status)),
            title: Text(entry.statusLabel),
            subtitle: Text(entry.category.label),
          ),
          Text(
            'Why it was flagged (score ${entry.score.toStringAsFixed(1)})',
            style: theme.textTheme.titleSmall,
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final reason in entry.reasons) Chip(label: Text(reason)),
            ],
          ),
          const SizedBox(height: 24),
          if (entry.status == SpamStatus.deleted) ...[
            FilledButton.tonalIcon(
              icon: const Icon(Icons.restore),
              label: const Text('Restore to inbox'),
              onPressed: () async {
                final navigator = Navigator.of(context);
                if (await runRestore(context, api, [entry.id]) > 0) {
                  navigator.pop(true);
                }
              },
            ),
            const SizedBox(height: 8),
          ],
          OutlinedButton.icon(
            icon: const Icon(Icons.verified_user_outlined),
            label: const Text('Not spam: allow sender'),
            onPressed: () => _allowSender(context),
          ),
        ],
      ),
    );
  }
}
