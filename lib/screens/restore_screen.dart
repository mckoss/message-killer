import 'package:flutter/material.dart';

import '../format.dart';
import '../native_api.dart';
import 'cleanup_flow.dart';

/// Deleted texts that no longer count as spam (e.g. bank alerts removed by an
/// older rule), with a button to put them back in the inbox.
class RestoreScreen extends StatefulWidget {
  const RestoreScreen({super.key, required this.api});

  final NativeApi api;

  @override
  State<RestoreScreen> createState() => _RestoreScreenState();
}

class _RestoreScreenState extends State<RestoreScreen> {
  List<SpamEntry>? _entries;

  /// Also put the senders on the allow list (e.g. your bank).
  bool _allowSenders = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final entries = await widget.api.listRestorable();
    if (mounted) setState(() => _entries = entries);
  }

  @override
  Widget build(BuildContext context) {
    final entries = _entries;
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Restore texts')),
      body: entries == null
          ? const Center(child: CircularProgressIndicator())
          : ListView.separated(
              itemCount: entries.length + 1,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, index) {
                if (index == 0) {
                  return Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      entries.isEmpty
                          ? 'Nothing to restore.'
                          : '${plural(entries.length, 'deleted text')} no longer look like spam '
                                'under the current rules. Restoring puts them back in your '
                                'inbox (picture messages come back as text only).',
                      style: theme.textTheme.bodyMedium,
                    ),
                  );
                }
                final e = entries[index - 1];
                return ListTile(
                  title: Text(
                    e.sender.isEmpty ? 'Unknown sender' : e.sender,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  subtitle: Text(
                    e.body,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                  trailing: Text(formatShortDate(e.messageTime)),
                );
              },
            ),
      bottomNavigationBar: entries == null || entries.isEmpty
          ? null
          : SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      value: _allowSenders,
                      onChanged: (v) =>
                          setState(() => _allowSenders = v ?? true),
                      title: Text(
                        'Always allow ${_senders(entries)}',
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                      subtitle: const Text('Never filter these senders again'),
                    ),
                    FilledButton.icon(
                      icon: const Icon(Icons.restore),
                      label: Text('Restore ${entries.length}'),
                      onPressed: () async {
                        final navigator = Navigator.of(context);
                        final restored = await runRestore(
                          context,
                          widget.api,
                          entries.map((e) => e.id).toList(),
                          afterRestore: (_) async {
                            if (!_allowSenders) return;
                            for (final sender
                                in entries.map((e) => e.sender).toSet()) {
                              if (sender.isNotEmpty) {
                                await widget.api.allowSender(sender);
                              }
                            }
                          },
                        );
                        if (restored > 0) navigator.pop();
                      },
                    ),
                  ],
                ),
              ),
            ),
    );
  }
}

/// "692632" / "692632 and 90999" / "3 senders".
String _senders(List<SpamEntry> entries) {
  final senders = entries
      .map((e) => e.sender)
      .where((s) => s.isNotEmpty)
      .toSet()
      .toList();
  return switch (senders.length) {
    0 => 'these senders',
    1 => senders.first,
    2 => '${senders[0]} and ${senders[1]}',
    _ => '${senders.length} senders',
  };
}
