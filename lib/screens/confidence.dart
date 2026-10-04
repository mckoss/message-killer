import 'package:flutter/material.dart';

import '../native_api.dart';

/// Yellow / orange / red for unsure / likely / certain spam. Always shown
/// with the word, never color alone.
Color confidenceColor(Confidence c, Brightness b) {
  final dark = b == Brightness.dark;
  return switch (c) {
    Confidence.unsure =>
      dark ? const Color(0xFFFFD34D) : const Color(0xFFF2B705),
    Confidence.likely =>
      dark ? const Color(0xFFFF8A4C) : const Color(0xFFF26B1D),
    Confidence.certain =>
      dark ? const Color(0xFFFF6B5E) : const Color(0xFFD92D20),
  };
}

/// A colored dot (with a hairline edge so yellow shows on white).
class ConfidenceDot extends StatelessWidget {
  const ConfidenceDot(this.confidence, {super.key, this.size = 10});

  final Confidence confidence;
  final double size;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: confidenceColor(confidence, theme.brightness),
        shape: BoxShape.circle,
        border: Border.all(
          color: theme.colorScheme.onSurface.withValues(alpha: 0.25),
          width: 0.5,
        ),
      ),
    );
  }
}

/// "● Unsure · Political · body…", for list subtitles.
Widget confidenceSubtitle(BuildContext context, SpamEntry entry) {
  final c = entry.confidence;
  return Text.rich(
    TextSpan(
      children: [
        if (c != null) ...[
          WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: ConfidenceDot(c),
          ),
          TextSpan(
            text: ' ${c.label} · ',
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
        ],
        TextSpan(text: '${entry.category.label} · ${entry.body}'),
      ],
    ),
    maxLines: 2,
    overflow: TextOverflow.ellipsis,
  );
}

/// "● 12 unsure  ● 30 likely  ● 200 certain" — the legend and counts.
class ConfidenceSummary extends StatelessWidget {
  const ConfidenceSummary(this.entries, {super.key});

  final List<SpamEntry> entries;

  @override
  Widget build(BuildContext context) {
    final counts = <Confidence, int>{};
    for (final e in entries) {
      final c = e.confidence;
      if (c != null) counts[c] = (counts[c] ?? 0) + 1;
    }
    return Wrap(
      spacing: 16,
      runSpacing: 4,
      children: [
        for (final c in Confidence.values)
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              ConfidenceDot(c),
              const SizedBox(width: 6),
              Text('${counts[c] ?? 0} ${c.label.toLowerCase()}'),
            ],
          ),
      ],
    );
  }
}
