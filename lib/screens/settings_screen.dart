import 'package:flutter/material.dart';

import '../native_api.dart';
import 'allowed_senders_screen.dart';
import 'cleanup_flow.dart';
import 'flagged_senders_screen.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.api});

  final NativeApi api;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  FilterSettings? _settings;
  final _testText = TextEditingController();
  final _testSender = TextEditingController();
  ClassifyResult? _testResult;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _testText.dispose();
    _testSender.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final settings = await widget.api.getSettings();
    if (mounted) setState(() => _settings = settings);
  }

  Future<void> _test() async {
    if (_testText.text.trim().isEmpty) return;
    final sender = _testSender.text.trim();
    final result = await widget.api.classify(
      _testText.text,
      sender: sender.isEmpty ? null : sender,
    );
    if (mounted) setState(() => _testResult = result);
  }

  @override
  Widget build(BuildContext context) {
    final settings = _settings;
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Filter settings')),
      body: settings == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Text('What to filter', style: theme.textTheme.titleMedium),
                const SizedBox(height: 4),
                Text(
                  'Changing these re-checks all your messages on the next scan.',
                  style: theme.textTheme.bodySmall,
                ),
                for (final c in SpamCategory.values)
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(c.label),
                    subtitle: Text(switch (c) {
                      SpamCategory.political =>
                        'Campaigns, PACs, donation asks, political polls',
                      SpamCategory.commercial => 'Sales, coupons and promotions (judged per message, never by sender)',
                      SpamCategory.phishing =>
                        'Fake toll, delivery, account and prize texts',
                    }),
                    value: settings.categories.contains(c),
                    onChanged: (on) async {
                      final next = {...settings.categories};
                      on ? next.add(c) : next.remove(c);
                      await widget.api.updateSettings(categories: next);
                      await _load();
                    },
                  ),
                const SizedBox(height: 16),
                _EditableList(
                  title: 'Custom keywords',
                  description: 'Any text containing one of these words or phrases is always filtered.',
                  hint: 'e.g. a candidate\'s name',
                  items: settings.customKeywords,
                  onChanged: (items) async {
                    await widget.api.updateSettings(customKeywords: items);
                    await _load();
                  },
                ),
                const SizedBox(height: 24),
                Card(
                  child: ListTile(
                    leading: const Icon(Icons.verified_user_outlined),
                    title: const Text('Allowed senders'),
                    subtitle: Text(
                      'Contacts + ${settings.allowedSenders.length} other '
                      '${settings.allowedSenders.length == 1 ? 'sender' : 'senders'}',
                    ),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () async {
                      await Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => AllowedSendersScreen(api: widget.api),
                        ),
                      );
                      await _load();
                    },
                  ),
                ),
                Card(
                  child: ListTile(
                    leading: const Icon(Icons.flag_outlined),
                    title: const Text('Flagged senders'),
                    subtitle: const Text(
                      'Numbers that sent political texts; view, un-flag, or export',
                    ),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => FlaggedSendersScreen(api: widget.api),
                      ),
                    ),
                  ),
                ),
                Card(
                  child: ListTile(
                    leading: const Icon(Icons.manage_search),
                    title: const Text('Rescan all messages'),
                    subtitle: const Text(
                      'Scans normally check only new texts. A full rescan happens '
                      'automatically when filter rules change.',
                    ),
                    onTap: () => runCleanup(context, widget.api, full: true),
                  ),
                ),
                const SizedBox(height: 24),
                Text('Test a message', style: theme.textTheme.titleMedium),
                const SizedBox(height: 4),
                Text(
                  'Paste a text to see whether Message Killer would filter it.',
                  style: theme.textTheme.bodySmall,
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: _testText,
                  minLines: 3,
                  maxLines: 6,
                  decoration: const InputDecoration(
                    border: OutlineInputBorder(),
                    labelText: 'Message text',
                  ),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: _testSender,
                  decoration: const InputDecoration(
                    border: OutlineInputBorder(),
                    labelText: 'Sender (optional)',
                  ),
                ),
                const SizedBox(height: 8),
                Align(
                  alignment: Alignment.centerRight,
                  child: FilledButton.tonal(
                    onPressed: _test,
                    child: const Text('Test'),
                  ),
                ),
                if (_testResult case final result?) ...[
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: Icon(
                      result.spam ? Icons.block : Icons.check_circle_outline,
                      color: result.spam
                          ? theme.colorScheme.error
                          : theme.colorScheme.primary,
                    ),
                    title: Text(
                      result.spam
                          ? 'Would be filtered as ${result.category?.label ?? 'spam'}'
                          : 'Would not be filtered',
                    ),
                    subtitle: Text(
                      'Score ${result.score.toStringAsFixed(1)} (filtered at 3.0)',
                    ),
                  ),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      for (final reason in result.reasons)
                        Chip(label: Text(reason)),
                    ],
                  ),
                ],
              ],
            ),
    );
  }
}

class _EditableList extends StatefulWidget {
  const _EditableList({
    required this.title,
    required this.description,
    required this.hint,
    required this.items,
    required this.onChanged,
  });

  final String title;
  final String description;
  final String hint;
  final List<String> items;
  final ValueChanged<List<String>> onChanged;

  @override
  State<_EditableList> createState() => _EditableListState();
}

class _EditableListState extends State<_EditableList> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _add() {
    final value = _controller.text.trim();
    if (value.isEmpty) return;
    _controller.clear();
    widget.onChanged([...widget.items, value]);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(widget.title, style: theme.textTheme.titleMedium),
        const SizedBox(height: 4),
        Text(widget.description, style: theme.textTheme.bodySmall),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final item in widget.items)
              InputChip(
                label: Text(item),
                onDeleted: () => widget.onChanged(
                  widget.items.where((i) => i != item).toList(),
                ),
              ),
          ],
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: TextField(
                controller: _controller,
                decoration: InputDecoration(
                  isDense: true,
                  border: const OutlineInputBorder(),
                  hintText: widget.hint,
                ),
                onSubmitted: (_) => _add(),
              ),
            ),
            IconButton(
              tooltip: 'Add',
              icon: const Icon(Icons.add),
              onPressed: _add,
            ),
          ],
        ),
      ],
    );
  }
}
