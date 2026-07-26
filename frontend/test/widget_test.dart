import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:neurovault_frontend/main.dart';

void main() {
  testWidgets('NeuroVault App initial smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const ProviderScope(child: NeuroVaultApp()));
    expect(find.byType(MaterialApp), findsOneWidget);
  });
}
