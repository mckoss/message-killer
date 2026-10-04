import 'dart:async';

import 'package:flutter/material.dart';

import '../format.dart';
import '../native_api.dart';
import 'cleanup_flow.dart';
import 'loading_view.dart';

/// Preview of old texts (90+ days) in conversations you never replied to,
/// grouped by sender, with a checkbox per sender.
class PruneScreen extends StatefulWidget {
  const PruneScreen({super.key, required this.api});

  final NativeApi api;

  @override
  State<PruneScreen> createState() => _PruneScreenState();
}

class _PruneScreenState extends State<PruneScreen> {
  List<PruneGroup>? _groups;
  final _selected = <String>{};
  String _progress = 'Finding old conversations you never replied to…';
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    // Reading every message takes a while on a big inbox: show what's happening.
    _poll = Timer.periodic(const Duration(milliseconds: 400), (_) async {
      final p = await widget.api.scanProgress();
      if (mounted && p.isNotEmpty) setState(() => _progress = p);
    });
    _load();
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    final groups = await widget.api.prunePreview();
    _poll?.cancel();
    if (!mounted) return;
    setState(() {
      _groups = groups;
      _selected
        ..clear()
        ..addAll(groups.map((g) => g.sender));
    });
  }

  @override
  Widget build(BuildContext context) {
    final groups = _groups;
    final theme = Theme.of(context);
    final chosen =
        groups?.where((g) => _selected.contains(g.sender)).toList() ?? const [];
    final total = chosen.fold(0, (n, g) => n + g.count);
    final all = groups?.fold(0, (n, g) => n + g.count) ?? 0;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Prune old messages'),
        actions: [
          if (groups != null && groups.isNotEmpty)
            TextButton(
              onPressed: () => setState(() {
                if (chosen.length == groups.length) {
                  _selected.clear();
                } else {
                  _selected.addAll(groups.map((g) => g.sender));
                }
              }),
              child: Text(chosen.length == groups.length ? 'None' : 'All'),
            ),
        ],
      ),
      body: groups == null
          ? LoadingView(_progress)
          : ListView.separated(
              itemCount: groups.length + 1,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, index) {
                if (index == 0) {
                  return Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      groups.isEmpty
                          ? 'Nothing to prune: no texts older than 90 days in conversations you never replied to.'
                          : '${plural(all, 'text')} from ${plural(groups.length, 'sender')} are older than '
                                '90 days, in conversations you never replied to. Contacts, allowed senders '
                                'and group chats are never included. Copies go to the Spam folder '
                                '("Old messages") for 90 days.',
                      style: theme.textTheme.bodyMedium,
                    ),
                  );
                }
                final g = groups[index - 1];
                return CheckboxListTile(
                  value: _selected.contains(g.sender),
                  onChanged: (on) => setState(() {
                    on == true
                        ? _selected.add(g.sender)
                        : _selected.remove(g.sender);
                  }),
                  title: Text('${g.sender} · ${plural(g.count, 'text')}'),
                  subtitle: Text(
                    '${formatShortDate(g.oldest)} – ${formatShortDate(g.newest)}\n${g.sample}',
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                  ),
                  isThreeLine: true,
                );
              },
            ),
      bottomNavigationBar: groups == null || groups.isEmpty
          ? null
          : SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: FilledButton.icon(
                  icon: const Icon(Icons.delete_sweep),
                  label: Text('Delete ${plural(total, 'old text')}'),
                  onPressed: total == 0
                      ? null
                      : () async {
                          final navigator = Navigator.of(context);
                          final deleted = await runPrune(
                            context,
                            widget.api,
                            chosen.map((g) => g.sender).toList(),
                          );
                          if (deleted > 0) navigator.pop();
                        },
                ),
              ),
            ),
    );
  }
}
