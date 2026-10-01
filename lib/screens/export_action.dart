import 'package:flutter/material.dart';

import '../format.dart';
import '../native_api.dart';

/// App-bar button that saves the Spam folder to Downloads as JSON + CSV.
class ExportSpamButton extends StatefulWidget {
  const ExportSpamButton({super.key, required this.api});

  final NativeApi api;

  @override
  State<ExportSpamButton> createState() => _ExportSpamButtonState();
}

class _ExportSpamButtonState extends State<ExportSpamButton> {
  bool _busy = false;

  Future<void> _export() async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      final result = await widget.api.exportSpam();
      messenger.showSnackBar(
        SnackBar(
          duration: const Duration(seconds: 8),
          content: Text(
            'Saved ${plural(result.count, 'message')} to Downloads:\n${result.files.join('\n')}',
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
