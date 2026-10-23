import 'package:flutter/material.dart';

class VoxColors {
  // Deep navy palette
  static const background = Color(0xFF0B0F19);
  static const surface = Color(0xFF111827);
  static const card = Color(0xFF1F2937);
  static const cardHover = Color(0xFF263346);
  static const border = Color(0xFF374151);
  static const borderSubtle = Color(0xFF1F2937);

  // Brand and accents
  static const primary = Color(0xFF3B82F6); // Electric Blue
  static const primaryDark = Color(0xFF2563EB);
  static const cyan = Color(0xFF2DD4BF);
  static const teal = Color(0xFF14B8A6);
  static const amber = Color(0xFFFBBF24);
  static const orange = Color(0xFFF97316);
  static const red = Color(0xFFF87171);
  static const emerald = Color(0xFF34D399);
  static const purple = Color(0xFFA78BFA);

  // Typography
  static const textPrimary = Color(0xFFF9FAFB);
  static const textSecondary = Color(0xFF9CA3AF);
  static const textMuted = Color(0xFF6B7280);
}

class VoxTheme {
  static ThemeData get darkTheme {
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: VoxColors.background,
      colorScheme: const ColorScheme.dark(
        primary: VoxColors.primary,
        secondary: VoxColors.cyan,
        surface: VoxColors.surface,
        error: VoxColors.red,
        onPrimary: Colors.white,
        onSecondary: Colors.black,
        onSurface: VoxColors.textPrimary,
        onError: Colors.white,
      ),
      cardTheme: const CardThemeData(
        color: VoxColors.card,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(12)),
          side: BorderSide(color: VoxColors.border, width: 1),
        ),
        margin: EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: VoxColors.surface,
        foregroundColor: VoxColors.textPrimary,
        elevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(
          color: VoxColors.textPrimary,
          fontSize: 18,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.3,
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: VoxColors.surface,
        indicatorColor: VoxColors.primary.withValues(alpha: 0.2),
        elevation: 4,
        labelTextStyle: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return const TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: VoxColors.primary,
            );
          }
          return const TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w500,
            color: VoxColors.textMuted,
          );
        }),
        iconTheme: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return const IconThemeData(color: VoxColors.primary, size: 24);
          }
          return const IconThemeData(color: VoxColors.textMuted, size: 24);
        }),
      ),
      dividerTheme: const DividerThemeData(
        color: VoxColors.border,
        thickness: 1,
        space: 1,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: VoxColors.card,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: const BorderSide(color: VoxColors.border),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: const BorderSide(color: VoxColors.border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: const BorderSide(color: VoxColors.primary, width: 1.5),
        ),
        hintStyle: const TextStyle(color: VoxColors.textMuted, fontSize: 14),
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      ),
      tabBarTheme: const TabBarThemeData(
        indicatorColor: VoxColors.primary,
        indicatorSize: TabBarIndicatorSize.tab,
        labelColor: VoxColors.primary,
        unselectedLabelColor: VoxColors.textMuted,
        labelStyle: TextStyle(fontWeight: FontWeight.w600, fontSize: 13),
        unselectedLabelStyle: TextStyle(fontWeight: FontWeight.w500, fontSize: 13),
      ),
    );
  }
}
