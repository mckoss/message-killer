import 'package:flutter/material.dart';

import '../format.dart';
import '../native_api.dart';
import 'confidence.dart';
import 'export_action.dart';
import 'loading_view.dart';

/// Debug view: the newest texts the last scan looked at and how each scored.
class ScanDebugScreen extends StatefulWidget {
  const ScanDebugScreen({super.key, required this.api});

  final NativeApi api;

  @override
  State<ScanDebugScreen> createState() => _ScanDebugScreenState();
}

class _ScanDebugScreenState extends State<ScanDebugScreen> {
  bool _loaded = false;
  ScanDebug? _debug;

  @override
  void initState() {
    super.initState();
    widget.api.lastScanDebug().then((d) {
      if (mounted) {
        setState(() {
          _debug = d;
          _loaded = true;
        });
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final debug = _debug;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Last scan details'),
        actions: [
          if (debug != null)
            ExportSpamButton(
              api: widget.api,
              export: widget.api.exportScanDebug,
              noun: 'text',
              tooltip: 'Export as JSON',
            ),
        ],
      ),
      body: !_loaded
          ? const LoadingView('Loading the last scan…')
          : debug == null
          ? const Center(
              child: Padding(
                padding: EdgeInsets.all(32),
                child: Text(
                  'No scan yet. Tap "Scan now" on the home screen first.',
                  textAlign: TextAlign.center,
                ),
              ),
            )
          : ListView.separated(
              itemCount: debug.rows.length + 1,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, i) {
                if (i == 0) {
                  return Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      '${debug.full ? 'Full' : 'Incremental'} scan on '
                      '${formatFullDate(debug.scanAt)} checked '
                      '${plural(debug.scanned, 'text')}. These are the newest '
                      '${debug.rows.length}, with their scores (texts are '
                      'filtered at 3). Texts from '
                      'your contacts are never checked, so they aren\'t listed. '
                      'The export (download icon) has every rule that fired, '
                      'for analysis.',
                      style: theme.textTheme.bodyMedium,
                    ),
                  );
                }
                final row = debug.rows[i - 1];
                return _RowTile(row: row);
              },
            ),
    );
  }
}

class _RowTile extends StatelessWidget {
  const _RowTile({required this.row});

  final ScanRow row;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final c = row.confidence;
    return ExpansionTile(
      title: Text(
        displaySender(row.sender),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text.rich(
        TextSpan(
          children: [
            if (c != null) ...[
              WidgetSpan(
                alignment: PlaceholderAlignment.middle,
                child: ConfidenceDot(c),
              ),
              TextSpan(
                text: ' ${row.category?.label} ${row.score.toStringAsFixed(1)}',
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
            ] else
              TextSpan(text: 'Not spam (${row.score.toStringAsFixed(1)})'),
            TextSpan(text: ' · ${row.body}'),
          ],
        ),
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: Text(formatShortDate(row.time)),
      childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      expandedCrossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SelectableText(row.body, style: theme.textTheme.bodyMedium),
        const SizedBox(height: 8),
        for (final cat in SpamCategory.filterable)
          if (row.scores[cat] case final score?)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                '${cat.label} ${score.toStringAsFixed(1)}: '
                '${(row.reasons[cat] ?? const []).join('; ')}',
                style: theme.textTheme.bodySmall,
              ),
            ),
        if (row.scores.isEmpty)
          Text('No rules matched.', style: theme.textTheme.bodySmall),
      ],
    );
  }
}
