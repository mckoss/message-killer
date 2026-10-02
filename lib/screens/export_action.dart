import 'package:flutter/material.dart';

import '../format.dart';
import '../native_api.dart';

/// App-bar button that saves the Spam folder to Downloads as JSON + CSV.
class ExportSpamButton extends StatefulWidget {
  const ExportSpamButton({
    super.key,
    required this.api,
    this.pendingOnly = false,
    this.export,
    this.noun = 'message',
  });

  final NativeApi api;
  final bool pendingOnly;

  /// Overrides what gets exported (default: the Spam folder).
  final Future<ExportResult> Function()? export;

  /// What the exported rows are, for the confirmation ("Saved 12 senders").
  final String noun;

  @override
  State<ExportSpamButton> createState() => _ExportSpamButtonState();
}

class _ExportSpamButtonState extends State<ExportSpamButton> {
  bool _busy = false;

  Future<void> _export() async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      final result =
          await (widget.export?.call() ??
              widget.api.exportSpam(pendingOnly: widget.pendingOnly));
      messenger.showSnackBar(
        SnackBar(
          duration: const Duration(seconds: 8),
          content: Text(
            'Saved ${plural(result.count, widget.noun)} to Downloads:\n${result.files.join('\n')}',
          ),
        ),
      );
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('Export failed: $e')));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return IconButton(
      tooltip: 'Export to Downloads',
      icon: _busy
          ? const SizedBox.square(
              dimension: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : const Icon(Icons.download),
      onPressed: _busy ? null : _export,
    );
  }
}
