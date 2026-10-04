import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../native_api.dart';
import 'loading_view.dart';

const _monthNames = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];

String _monthLabel(MonthStats m) => '${_monthNames[m.month - 1]} ${m.year}';

String _shortMonth(MonthStats m) =>
    '${_monthNames[m.month - 1].substring(0, 3)} ${m.year}';

/// Series colors (validated for color-vision deficiency on each surface).
/// Stack order is bottom to top.
const _stack = [
  SpamCategory.political,
  SpamCategory.commercial,
  SpamCategory.phishing,
];

Color _colorOf(SpamCategory c, Brightness b) {
  final dark = b == Brightness.dark;
  return switch (c) {
    SpamCategory.political =>
      dark ? const Color(0xFF3987E5) : const Color(0xFF2A78D6),
    SpamCategory.commercial =>
      dark ? const Color(0xFFD95926) : const Color(0xFFEB6834),
    _ => dark ? const Color(0xFF199E70) : const Color(0xFF1BAF7A),
  };
}

/// Spam per month, stacked by category, from the oldest text on record.
class StatsScreen extends StatefulWidget {
  const StatsScreen({super.key, required this.api});

  final NativeApi api;

  @override
  State<StatsScreen> createState() => _StatsScreenState();
}

class _StatsScreenState extends State<StatsScreen> {
  List<MonthStats>? _months;
  int? _selected;

  @override
  void initState() {
    super.initState();
    widget.api.monthlyStats().then((m) {
      if (mounted) setState(() => _months = m);
    });
  }

  @override
  Widget build(BuildContext context) {
    final months = _months;
    return Scaffold(
      appBar: AppBar(title: const Text('Spam over time')),
      body: months == null
          ? const LoadingView('Counting spam by month…')
          : months.isEmpty
          ? const Center(
              child: Padding(
                padding: EdgeInsets.all(32),
                child: Text('No spam filed yet.', textAlign: TextAlign.center),
              ),
            )
          : _body(context, months),
    );
  }

  Widget _body(BuildContext context, List<MonthStats> months) {
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    final total = months.fold(0, (a, m) => a + m.total);
    final busiest = months.reduce((a, b) => b.total > a.total ? b : a);
    final selected = _selected == null ? null : months[_selected!];
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text('$total spam texts', style: theme.textTheme.titleLarge),
        const SizedBox(height: 4),
        Text(
          'Since ${_monthLabel(months.first)} · busiest: '
          '${_monthLabel(busiest)} (${busiest.total})',
          style: theme.textTheme.bodyMedium?.copyWith(color: muted),
        ),
        const SizedBox(height: 12),
        Wrap(
          spacing: 16,
          runSpacing: 4,
          children: [
            for (final c in _stack)
              _LegendItem(
                color: _colorOf(c, theme.brightness),
                label: c.label,
                count: months.fold(0, (a, m) => a + m.count(c)),
              ),
          ],
        ),
        const SizedBox(height: 12),
        _Chart(
          months: months,
          selected: _selected,
          onSelect: (i) => setState(() => _selected = i),
        ),
        const SizedBox(height: 8),
        _MonthDetail(month: selected),
        const SizedBox(height: 4),
        Text(
          'Includes texts removed from the Spam folder after 90 days.',
          style: theme.textTheme.bodySmall?.copyWith(color: muted),
        ),
        const SizedBox(height: 16),
        _table(context, months),
      ],
    );
  }

  Widget _table(BuildContext context, List<MonthStats> months) {
    final theme = Theme.of(context);
    final header = theme.textTheme.labelMedium?.copyWith(
      color: theme.colorScheme.onSurfaceVariant,
    );
    // One line per cell: shrink rather than wrap on a narrow portrait screen.
    Widget cell(String text, TextStyle? style, Alignment align) => FittedBox(
      fit: BoxFit.scaleDown,
      alignment: align,
      child: Text(text, style: style, maxLines: 1),
    );
    Widget row(List<String> cells, TextStyle? style) => Row(
      children: [
        Expanded(flex: 3, child: cell(cells[0], style, Alignment.centerLeft)),
        for (final c in cells.skip(1))
          Expanded(
            flex: 2,
            child: Padding(
              padding: const EdgeInsets.only(left: 4),
              child: cell(c, style, Alignment.centerRight),
            ),
          ),
      ],
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        row(['Month', 'Political', 'Commer.', 'Phishing', 'Total'], header),
        const Divider(),
        for (var i = months.length - 1; i >= 0; i--)
          InkWell(
            onTap: () => setState(() => _selected = i),
            child: Container(
              color: i == _selected
                  ? theme.colorScheme.secondaryContainer
                  : null,
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: row([
                _shortMonth(months[i]),
                for (final c in _stack) '${months[i].count(c)}',
                '${months[i].total}',
              ], theme.textTheme.bodyMedium),
            ),
          ),
      ],
    );
  }
}

class _LegendItem extends StatelessWidget {
  const _LegendItem({
    required this.color,
    required this.label,
    required this.count,
  });

  final Color color;
  final String label;
  final int count;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 12,
          height: 12,
          decoration: BoxDecoration(
            color: color,
            borderRadius: BorderRadius.circular(3),
          ),
        ),
        const SizedBox(width: 6),
        Flexible(child: Text('$label $count')),
      ],
    );
  }
}

class _MonthDetail extends StatelessWidget {
  const _MonthDetail({required this.month});

  final MonthStats? month;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final m = month;
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 44),
      child: m == null
          ? Text(
              'Tap a bar to see that month.',
              style: theme.textTheme.bodySmall,
            )
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '${_monthLabel(m)}: ${m.total}',
                  style: theme.textTheme.titleSmall,
                ),
                Text(
                  [for (final c in _stack) '${c.label} ${m.count(c)}']
                      .join(' · '),
                  style: theme.textTheme.bodySmall,
                ),
              ],
            ),
    );
  }
}

/// Chart geometry shared by the y-axis column and the scrolling bars.
const _topPad = 8.0;
const _bottomPad = 36.0;
const _slot = 28.0;
const _barWidth = 18.0;

/// Axis labels stay legible with large system text without crowding the
/// bars on a narrow portrait screen.
const _maxLabelScale = 1.3;

/// "950", "1.5k", "12k".
String _compact(int v) {
  if (v < 1000) return '$v';
  final k = v / 1000;
  return k >= 10 || k == k.roundToDouble()
      ? '${k.round()}k'
      : '${k.toStringAsFixed(1)}k';
}

/// A round axis maximum and step (1, 2 or 5 × 10ⁿ) giving about 4 gridlines.
(int, int) _niceScale(int max) {
  if (max <= 0) return (4, 1);
  final raw = max / 4;
  final mag = math.pow(10, (math.log(raw) / math.ln10).floor()).toDouble();
  final step = [1, 2, 5, 10].map((f) => f * mag).firstWhere((s) => s >= raw);
  final s = math.max(1, step.round());
  return ((max / s).ceil() * s, s);
}

class _Chart extends StatelessWidget {
  const _Chart({
    required this.months,
    required this.selected,
    required this.onSelect,
  });

  final List<MonthStats> months;
  final int? selected;
  final ValueChanged<int> onSelect;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final (top, step) = _niceScale(
      months.fold(0, (a, m) => math.max(a, m.total)),
    );
    final ink = theme.colorScheme.onSurfaceVariant;
    final width = months.length * _slot;
    // Phones are mostly held upright: use a good share of the tall screen.
    final height = (MediaQuery.sizeOf(context).height * 0.38).clamp(
      200.0,
      360.0,
    );
    final scaler = MediaQuery.textScalerOf(context)
        .clamp(maxScaleFactor: _maxLabelScale);
    final labelStyle = TextStyle(color: ink, fontSize: 11);
    var axisWidth = 0.0;
    for (var v = 0; v <= top; v += step) {
      final tp = TextPainter(
        text: TextSpan(text: _compact(v), style: labelStyle),
        textDirection: TextDirection.ltr,
        textScaler: scaler,
      )..layout();
      axisWidth = math.max(axisWidth, tp.width);
    }
    return SizedBox(
      height: height,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            width: axisWidth + 8,
            child: CustomPaint(
              painter: _AxisPainter(top, step, labelStyle, scaler, height),
            ),
          ),
          Expanded(
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              reverse: true, // start at the newest month
              child: GestureDetector(
                onTapUp: (d) {
                  final i = (d.localPosition.dx / _slot).floor();
                  if (i >= 0 && i < months.length) onSelect(i);
                },
                child: CustomPaint(
                  size: Size(width, height),
                  painter: _BarsPainter(
                    height: height,
                    scaler: scaler,
                    months: months,
                    top: top,
                    step: step,
                    selected: selected,
                    brightness: theme.brightness,
                    ink: ink,
                    grid: theme.colorScheme.outlineVariant,
                    highlight: theme.colorScheme.secondaryContainer,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

double _plotHeight(double height) => height - _topPad - _bottomPad;

double _yOf(num value, int top, double height) =>
    _topPad + _plotHeight(height) * (1 - value / top);

void _drawText(
  Canvas canvas,
  String text,
  Offset at,
  TextStyle style,
  TextScaler scaler, {
  bool alignRight = false,
  bool center = false,
}) {
  final tp = TextPainter(
    text: TextSpan(text: text, style: style),
    textDirection: TextDirection.ltr,
    textScaler: scaler,
  )..layout();
  var dx = at.dx;
  if (alignRight) dx -= tp.width;
  if (center) dx -= tp.width / 2;
  tp.paint(canvas, Offset(dx, at.dy - (alignRight ? tp.height / 2 : 0)));
}

class _AxisPainter extends CustomPainter {
  _AxisPainter(this.top, this.step, this.style, this.scaler, this.height);

  final int top;
  final int step;
  final TextStyle style;
  final TextScaler scaler;
  final double height;

  @override
  void paint(Canvas canvas, Size size) {
    for (var v = 0; v <= top; v += step) {
      _drawText(
        canvas,
        _compact(v),
        Offset(size.width - 6, _yOf(v, top, height)),
        style,
        scaler,
        alignRight: true,
      );
    }
  }

  @override
  bool shouldRepaint(_AxisPainter old) =>
      old.top != top ||
      old.step != step ||
      old.style != style ||
      old.scaler != scaler ||
      old.height != height;
}

class _BarsPainter extends CustomPainter {
  _BarsPainter({
    required this.height,
    required this.scaler,
    required this.months,
    required this.top,
    required this.step,
    required this.selected,
    required this.brightness,
    required this.ink,
    required this.grid,
    required this.highlight,
  });

  final double height;
  final TextScaler scaler;
  final List<MonthStats> months;
  final int top;
  final int step;
  final int? selected;
  final Brightness brightness;
  final Color ink;
  final Color grid;
  final Color highlight;

  @override
  void paint(Canvas canvas, Size size) {
    final gridPaint = Paint()
      ..color = grid
      ..strokeWidth = 1;
    for (var v = 0; v <= top; v += step) {
      final y = _yOf(v, top, height);
      canvas.drawLine(Offset(0, y), Offset(size.width, y), gridPaint);
    }
    if (selected != null) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromLTWH(selected! * _slot + 1, 0, _slot - 2, height),
          const Radius.circular(6),
        ),
        Paint()..color = highlight.withValues(alpha: 0.6),
      );
    }

    final style = TextStyle(color: ink, fontSize: 10);
    final baseline = _yOf(0, top, height);
    for (var i = 0; i < months.length; i++) {
      final m = months[i];
      final left = i * _slot + (_slot - _barWidth) / 2;
      final segments = [
        for (final c in _stack)
          if (m.count(c) > 0) (c, m.count(c)),
      ];
      var cursor = baseline;
      for (var s = 0; s < segments.length; s++) {
        final (category, count) = segments[s];
        final h = _plotHeight(height) * count / top;
        // 2px surface gap between stacked segments, never hiding a segment.
        final gap = s == 0 ? 0.0 : math.min(2.0, h / 2);
        final rect = Rect.fromLTRB(left, cursor - h, left + _barWidth, cursor);
        final r = s == segments.length - 1
            ? Radius.circular(math.min(4, h - gap))
            : Radius.zero;
        canvas.drawRRect(
          RRect.fromRectAndCorners(
            Rect.fromLTRB(rect.left, rect.top, rect.right, rect.bottom - gap),
            topLeft: r,
            topRight: r,
          ),
          Paint()..color = _colorOf(category, brightness),
        );
        cursor -= h;
      }

      final cx = i * _slot + _slot / 2;
      _drawText(
        canvas,
        _monthNames[m.month - 1][0],
        Offset(cx, baseline + 4),
        style,
        scaler,
        center: true,
      );
      if (m.month == 1 || i == 0) {
        _drawText(
          canvas,
          '${m.year}',
          Offset(cx, baseline + 19),
          style.copyWith(fontWeight: FontWeight.bold),
          scaler,
          center: true,
        );
      }
    }
  }

  @override
  bool shouldRepaint(_BarsPainter old) =>
      old.months != months ||
      old.selected != selected ||
      old.brightness != brightness ||
      old.top != top ||
      old.height != height ||
      old.scaler != scaler;
}
