import 'package:flutter/material.dart';

import '../format.dart';
import '../native_api.dart';
import 'loading_view.dart';
import 'cleanup_flow.dart';

/// Deleted texts that no longer count as spam (e.g. bank alerts removed by an
/// older rule), grouped by sender. Check the ones to put back (per text or per
/// number); unchecked ones are confirmed as spam and never offered again. Each
/// number can separately be put on the allow list.
class RestoreScreen extends StatefulWidget {
  const RestoreScreen({super.key, required this.api});

  final NativeApi api;

  @override
  State<RestoreScreen> createState() => _RestoreScreenState();
}

class _RestoreScreenState extends State<RestoreScreen> {
  List<SpamEntry>? _entries;

  /// [_entries] grouped by sender, computed once per load.
  List<List<SpamEntry>> _groups = const [];

  /// Ids checked for restore (all, initially).
  final _selected = <int>{};

  /// Senders to put on the allow list (e.g. your bank); off by default.
  final _allow = <String>{};

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
      _groups = _bySender(entries);
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
        // Only numbers you switched on, and only if something of theirs was restored.
        final restoredSenders = restore.map((e) => e.sender).toSet();
        for (final sender in _allow.intersection(restoredSenders)) {
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

  /// One sender's header (restore-all checkbox, "Always allow" switch) and texts.
  List<Widget Function()> _senderSection(List<SpamEntry> group) {
    final sender = group.first.sender;
    final theme = Theme.of(context);
    return [
      () => const Divider(height: 1),
      () {
        final ids = group.map((e) => e.id).toSet();
        final checked = ids.where(_selected.contains).length;
        return CheckboxListTile(
          tileColor: theme.colorScheme.surfaceContainerHighest,
          tristate: true,
          value: checked == ids.length ? true : (checked == 0 ? false : null),
          onChanged: (_) => setState(() {
            checked == ids.length
                ? _selected.removeAll(ids)
                : _selected.addAll(ids);
          }),
          title: Text(
            sender.isEmpty ? 'Unknown sender' : sender,
            style: theme.textTheme.titleMedium,
          ),
          subtitle: Text('Restore $checked of ${plural(ids.length, 'text')}'),
        );
      },
      if (sender.isNotEmpty)
        () => SwitchListTile(
          dense: true,
          contentPadding: const EdgeInsets.only(left: 32, right: 16),
          title: Text('Always allow $sender'),
          subtitle: const Text('Never filter this number again'),
          value: _allow.contains(sender),
          onChanged: (on) => setState(() {
            on ? _allow.add(sender) : _allow.remove(sender);
          }),
        ),
      for (final e in group)
        () => CheckboxListTile(
          contentPadding: const EdgeInsets.only(left: 32, right: 16),
          value: _selected.contains(e.id),
          onChanged: (on) => setState(() {
            on == true ? _selected.add(e.id) : _selected.remove(e.id);
          }),
          title: Text(
            formatShortDate(e.messageTime),
            style: theme.textTheme.bodySmall,
          ),
          subtitle: Text(e.body, maxLines: 3, overflow: TextOverflow.ellipsis),
        ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final entries = _entries;
    final theme = Theme.of(context);
    // _selected only ever holds ids from _entries.
    final restoreCount = _selected.length;
    final keepCount = (entries?.length ?? 0) - restoreCount;

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
          ? const LoadingView('Re-checking deleted texts…')
          : Builder(
              builder: (context) {
                // Built lazily: a long list only builds the rows on screen.
                final rows = <Widget Function()>[
                  () => Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      entries.isEmpty
                          ? 'Nothing to restore.'
                          : '${plural(entries.length, 'deleted text')} no longer '
                                '${entries.length == 1 ? 'looks' : 'look'} like spam under the current rules. '
                                'Check the texts (or whole numbers) to put back in your inbox; unchecked '
                                'texts are confirmed as spam and won\'t be offered again. Switch on '
                                '"Always allow" for numbers you never want filtered. '
                                '(Picture messages come back as text only.)',
                      style: theme.textTheme.bodyMedium,
                    ),
                  ),
                  for (final group in _groups) ..._senderSection(group),
                ];
                return ListView.builder(
                  itemCount: rows.length,
                  itemBuilder: (_, i) => rows[i](),
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

/// Entries grouped by sender, biggest groups first.
List<List<SpamEntry>> _bySender(List<SpamEntry> entries) {
  final groups = <String, List<SpamEntry>>{};
  for (final e in entries) {
    groups.putIfAbsent(e.sender, () => []).add(e);
  }
  return groups.values.toList()..sort((a, b) => b.length.compareTo(a.length));
}
