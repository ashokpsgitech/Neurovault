import 'package:flutter/material.dart';

/// Material 3 Dark and Light Theme tokens for NeuroVault.
class AppTheme {
  // Primary brand palette - Deep Cyber Indigo & Vibrant Teal Accent
  static const Color primaryColor = Color(0xFF6366F1);
  static const Color secondaryColor = Color(0xFF10B981);
  static const Color accentColor = Color(0xFF06B6D4);

  static final ThemeData darkTheme = ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    colorScheme: const ColorScheme.dark(
      primary: primaryColor,
      secondary: secondaryColor,
      tertiary: accentColor,
      surface: Color(0xFF0F172A),
      error: Color(0xFFEF4444),
    ),
    scaffoldBackgroundColor: const Color(0xFF020617),
    appBarTheme: const AppBarTheme(
      backgroundColor: Color(0xFF0F172A),
      elevation: 0,
      centerTitle: false,
    ),
    cardTheme: CardTheme(
      color: const Color(0xFF0F172A),
      elevation: 2,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
    ),
  );

  static final ThemeData lightTheme = ThemeData(
    useMaterial3: true,
    brightness: Brightness.light,
    colorScheme: const ColorScheme.light(
      primary: primaryColor,
      secondary: secondaryColor,
      tertiary: accentColor,
      surface: Color(0xFFF8FAFC),
      error: Color(0xFFDC2626),
    ),
    scaffoldBackgroundColor: const Color(0xFFF8FAFC),
    appBarTheme: const AppBarTheme(
      backgroundColor: Color(0xFFFFFFFF),
      elevation: 0,
      centerTitle: false,
    ),
    cardTheme: CardTheme(
      color: const Color(0xFFFFFFFF),
      elevation: 1,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
    ),
  );
}
