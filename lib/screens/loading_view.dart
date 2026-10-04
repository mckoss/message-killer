import 'package:flutter/material.dart';

/// Shown while a screen's data loads, so nothing ever looks blank or frozen.
class LoadingView extends StatelessWidget {
  const LoadingView(this.message, {super.key});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(width: 200, child: LinearProgressIndicator()),
            const SizedBox(height: 16),
            Text(message, textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}
