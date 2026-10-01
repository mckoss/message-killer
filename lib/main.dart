import 'package:flutter/material.dart';

import 'native_api.dart';
import 'screens/home_screen.dart';

void main() {
  runApp(MessageKillerApp(api: MethodChannelNativeApi()));
}

class MessageKillerApp extends StatelessWidget {
  const MessageKillerApp({super.key, required this.api});

  final NativeApi api;

  @override
  Widget build(BuildContext context) {
    const seed = Color(0xFFB3261E);
    return MaterialApp(
      title: 'Message Killer',
      theme: ThemeData(colorSchemeSeed: seed, useMaterial3: true),
      darkTheme: ThemeData(
        colorSchemeSeed: seed,
        brightness: Brightness.dark,
        useMaterial3: true,
      ),
      home: HomeScreen(api: api),
    );
  }
}
