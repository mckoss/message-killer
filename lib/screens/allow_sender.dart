import 'package:flutter/material.dart';

import '../native_api.dart';

/// Confirms, then allow-lists the entry's sender. Texts from that sender that
/// were waiting to be deleted stay in the inbox; an already-silenced or deleted
/// entry is removed from the Spam folder. Returns true if anything changed.
Future<bool> confirmAllowSender(
  BuildContext context,
  NativeApi api,
  SpamEntry entry,
) async {
  final sender = entry.sender.isEmpty ? 'this sender' : entry.sender;
  final pending = entry.status == SpamStatus.pendingDelete;
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text('Always allow $sender?'),
      content: Text(
        'Texts from $sender will never be filtered again. You can undo this under '
        'Filter settings → Allowed senders.\n\n'
        '${pending ? 'Its texts waiting to be deleted will stay in your inbox.' : 'This entry is removed from the Spam folder.'}'
        '${entry.status == SpamStatus.deleted ? ' It was already deleted from your inbox, so copy the text first if you need it.' : ''}',
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, true),
          child: const Text('Allow sender'),
        ),
      ],
    ),
  );
  if (confirmed != true) return false;
  if (pending) {
    await api.allowSender(entry.sender);
  } else {
    await api.removeSpam(entry.id, allowSender: true);
  }
  return true;
}
