import 'package:flutter/material.dart';

import '../format.dart';
import '../native_api.dart';
import 'loading_view.dart';
import 'export_action.dart';

/// Senders flagged for political texts: everything they send is filtered.
class FlaggedSendersScreen extends StatefulWidget {
  const FlaggedSendersScreen({super.key, required this.api});

  final NativeApi api;

  @override
  State<FlaggedSendersScreen> createState() => _FlaggedSendersScreenState();
}

class _FlaggedSendersScreenState extends State<FlaggedSendersScreen> {
  List<FlaggedSender>? _senders;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final senders = await widget.api.listFlagged();
    if (mounted) setState(() => _senders = senders);
  }

  Future<void> _unflag(FlaggedSender s) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Un-flag ${s.display}?'),
        content: const Text(
          'Its texts will only be filtered when their content looks like spam. '
          'Texts waiting to be deleted only because of the flag stay in your inbox. '
          'To never filter this sender at all, use "Allow sender" instead.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Un-flag'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await widget.api.unflagSender(s.sender);
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final senders = _senders;
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Flagged senders'),
        actions: [
          ExportSpamButton(
            api: widget.api,
            export: widget.api.exportFlagged,
            noun: 'sender',
          ),
        ],
      ),
      body: senders == null
          ? const LoadingView('Loading flagged senders…')
          : ListView.builder(
              itemCount: senders.length + 1,
              itemBuilder: (context, index) {
                if (index == 0) {
                  return Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      senders.isEmpty
                          ? 'No flagged senders yet.'
                          : '${plural(senders.length, 'sender')} sent political texts, so everything '
                                'they send is filtered (except verification codes). '
                                'The download icon saves a report of every spam sender: texts per category, '
                                'first and last date, texts per day, and busiest day (JSON + CSV).',
                      style: theme.textTheme.bodyMedium,
                    ),
                  );
                }
                final s = senders[index - 1];
                return ListTile(
                  leading: const Icon(Icons.flag_outlined),
                  title: Text(s.display),
                  subtitle: Text(
                    'Flagged ${formatShortDate(s.flaggedAt)} · '
                    '${plural(s.messages, 'text')} in Spam folder',
                  ),
                  trailing: IconButton(
                    tooltip: 'Un-flag ${s.display}',
                    icon: const Icon(Icons.outlined_flag),
                    onPressed: () => _unflag(s),
                  ),
                );
              },
            ),
    );
  }
}
