import 'package:flutter/material.dart';

import '../format.dart';
import '../native_api.dart';
import 'cleanup_flow.dart';

/// Deleted texts that no longer count as spam (e.g. bank alerts removed by an
/// older rule). Check the ones to put back; unchecked ones are confirmed as
/// spam and never offered again.
class RestoreScreen extends StatefulWidget {
  const RestoreScreen({super.key, required this.api});

  final NativeApi api;

  @override
  State<RestoreScreen> createState() => _RestoreScreenState();
}

class _RestoreScreenState extends State<RestoreScreen> {
  List<SpamEntry>? _entries;

  /// Ids checked for restore (all, initially).
  final _selected = <int>{};

  /// Also put the restored texts' senders on the allow list (e.g. your bank).
  bool _allowSenders = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final entries = await widget.api.listRestorable();
    if (!mounted) return;
    setState(() {
      _entries = entries;
      _selected
        ..clear()
        ..addAll(entries.map((e) => e.id));
    });
  }

  Future<void> _apply(List<SpamEntry> entries) async {
    final navigator = Navigator.of(context);
    final keep = entries.where((e) => !_selected.contains(e.id)).toList();
    final restore = entries.where((e) => _selected.contains(e.id)).toList();

    // Unchecked texts are really spam: never offer them again.
    if (keep.isNotEmpty) {
      await widget.api.confirmSpam(keep.map((e) => e.id).toList());
    }
    if (restore.isEmpty) {
      navigator.pop();
      return;
    }
    if (!mounted) return;
    final restored = await runRestore(
      context,
      widget.api,
      restore.map((e) => e.id).toList(),
      afterRestore: (_) async {
        if (!_allowSenders) return;
        for (final sender in restore.map((e) => e.sender).toSet()) {
          if (sender.isNotEmpty) await widget.api.allowSender(sender);
        }
      },
    );
    if (restored > 0) {
      navigator.pop();
    } else {
      await _load(); // restore was cancelled; confirmed ones are gone from the list
    }
  }

  @override
  Widget build(BuildContext context) {
    final entries = _entries;
    final theme = Theme.of(context);
    final restoreCount =
        entries?.where((e) => _selected.contains(e.id)).length ?? 0;
    final keepCount = (entries?.length ?? 0) - restoreCount;
    final restoreSenders =
        entries?.where((e) => _selected.contains(e.id)).toList() ?? const [];

    return Scaffold(
      appBar: AppBar(
        title: const Text('Restore texts'),
        actions: [
          if (entries != null && entries.isNotEmpty)
            TextButton(
              onPressed: () => setState(() {
                if (restoreCount == entries.length) {
                  _selected.clear();
                } else {
                  _selected.addAll(entries.map((e) => e.id));
                }
              }),
              child: Text(restoreCount == entries.length ? 'None' : 'All'),
            ),
        ],
      ),
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
                          : '${plural(entries.length, 'deleted text')} no longer ${entries.length == 1 ? 'looks' : 'look'} like spam '
                                'under the current rules. Check the ones to put back in your inbox. '
                                'Unchecked texts are confirmed as spam and won\'t be offered again. '
                                '(Picture messages come back as text only.)',
                      style: theme.textTheme.bodyMedium,
                    ),
                  );
                }
                final e = entries[index - 1];
                return CheckboxListTile(
                  value: _selected.contains(e.id),
                  onChanged: (on) => setState(() {
                    on == true ? _selected.add(e.id) : _selected.remove(e.id);
                  }),
                  title: Text(
                    '${e.sender.isEmpty ? 'Unknown sender' : e.sender} · '
                    '${formatShortDate(e.messageTime)}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  subtitle: Text(
                    e.body,
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                  ),
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
                    if (restoreCount > 0)
                      CheckboxListTile(
                        contentPadding: EdgeInsets.zero,
                        value: _allowSenders,
                        onChanged: (v) =>
                            setState(() => _allowSenders = v ?? true),
                        title: Text(
                          'Always allow ${_senders(restoreSenders)}',
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                        subtitle: const Text(
                          'Never filter these senders again',
                        ),
                      ),
                    FilledButton.icon(
                      icon: Icon(restoreCount > 0 ? Icons.restore : Icons.done),
                      label: Text(switch ((restoreCount, keepCount)) {
                        (0, _) =>
                          'Keep all ${plural(keepCount, 'text')} as spam',
                        (_, 0) => 'Restore $restoreCount',
                        _ => 'Restore $restoreCount · keep $keepCount as spam',
                      }),
                      onPressed: () => _apply(entries),
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
