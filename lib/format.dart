const _months = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
];

String _two(int n) => n.toString().padLeft(2, '0');

String formatTime(DateTime t) {
  final hour = t.hour % 12 == 0 ? 12 : t.hour % 12;
  return '$hour:${_two(t.minute)} ${t.hour < 12 ? 'AM' : 'PM'}';
}

/// "3:05 PM" for today, "Oct 1" this year, "Oct 1, 2025" otherwise.
String formatShortDate(DateTime t, {DateTime? now}) {
  now ??= DateTime.now();
  if (t.year == now.year && t.month == now.month && t.day == now.day) {
    return formatTime(t);
  }
  final date = '${_months[t.month - 1]} ${t.day}';
  return t.year == now.year ? date : '$date, ${t.year}';
}

String formatFullDate(DateTime t) =>
    '${_months[t.month - 1]} ${t.day}, ${t.year} at ${formatTime(t)}';

String plural(int n, String one, [String? many]) =>
    '$n ${n == 1 ? one : (many ?? '${one}s')}';
