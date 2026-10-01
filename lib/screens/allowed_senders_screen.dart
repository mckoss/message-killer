import 'package:flutter/material.dart';

import '../native_api.dart';

/// View, add, and remove senders that are never filtered.
class AllowedSendersScreen extends StatefulWidget {
  const AllowedSendersScreen({super.key, required this.api});

  final NativeApi api;

  @override
  State<AllowedSendersScreen> createState() => _AllowedSendersScreenState();
}

class _AllowedSendersScreenState extends State<AllowedSendersScreen> {
  List<String>? _senders;
  bool _contactsPermission = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final settings = await widget.api.getSettings();
    final status = await widget.api.getStatus();
    if (!mounted) return;
    setState(() {
      _senders = settings.allowedSenders;
      _contactsPermission = status.contactsPermission;
    });
  }

  Future<void> _save(List<String> senders) async {
    await widget.api.updateSettings(allowedSenders: senders);
    await _load();
  }

  Future<void> _add() async {
    final value = await showDialog<String>(
      context: context,
      builder: (_) => const _AddSenderDialog(),
    );
    final sender = value?.trim() ?? '';
    if (sender.isEmpty) return;
    await widget.api.allowSender(sender);
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final senders = _senders;
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Allowed senders')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _add,
        icon: const Icon(Icons.add),
        label: const Text('Add'),
      ),
      body: senders == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.only(bottom: 88),
              children: [
                ListTile(
                  leading: Icon(
                    _contactsPermission
                        ? Icons.contacts
                        : Icons.contacts_outlined,
                    color: _contactsPermission
                        ? theme.colorScheme.primary
                        : null,
                  ),
                  title: const Text('Everyone in your contacts'),
                  subtitle: Text(
                    _contactsPermission ? 'Always allowed automatically' : 'Allow Contacts access on the home screen so your contacts are never filtered',
                  ),
                ),
                const Divider(),
                if (senders.isEmpty)
                  Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      'No other allowed senders yet. Use "Allow sender" on a flagged text, or tap Add.',
                      style: theme.textTheme.bodyMedium,
                    ),
                  ),
                for (final sender in senders)
                  ListTile(
                    leading: const Icon(Icons.verified_user_outlined),
                    title: Text(sender),
                    trailing: IconButton(
                      tooltip: 'Remove $sender',
                      icon: const Icon(Icons.delete_outline),
                      onPressed: () =>
                          _save(senders.where((s) => s != sender).toList()),
                    ),
                  ),
              ],
            ),
    );
  }
}

/// Owns its text controller so it outlives the dialog's closing animation.
class _AddSenderDialog extends StatefulWidget {
  const _AddSenderDialog();

  @override
  State<_AddSenderDialog> createState() => _AddSenderDialogState();
}

class _AddSenderDialogState extends State<_AddSenderDialog> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Allow a sender'),
      content: TextField(
        controller: _controller,
        autofocus: true,
        keyboardType: TextInputType.phone,
        decoration: const InputDecoration(
          labelText: 'Phone number or name',
          border: OutlineInputBorder(),
        ),
        onSubmitted: (v) => Navigator.pop(context, v),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, _controller.text),
          child: const Text('Allow'),
        ),
      ],
    );
  }
}
