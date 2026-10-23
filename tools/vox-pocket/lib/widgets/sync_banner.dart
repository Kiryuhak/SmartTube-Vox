import 'package:flutter/material.dart';
import '../services/app_repository.dart';
import '../theme/vox_theme.dart';

class SyncBanner extends StatelessWidget {
  final bool isDemoMode;
  final DataSourceType? dataSourceType;
  final bool isSyncing;
  final String? errorMessage;
  final ErrorCategory errorCategory;
  final int lastSyncTime;
  final VoidCallback onRefresh;
  final VoidCallback? onOpenSettings;

  const SyncBanner({
    super.key,
    required this.isDemoMode,
    this.dataSourceType,
    required this.isSyncing,
    this.errorMessage,
    this.errorCategory = ErrorCategory.none,
    required this.lastSyncTime,
    required this.onRefresh,
    this.onOpenSettings,
  });

  String _formatSyncTime(int timeMs) {
    if (timeMs <= 0) return 'не выполнялась';
    final dt = DateTime.fromMillisecondsSinceEpoch(timeMs);
    final hours = dt.hour.toString().padLeft(2, '0');
    final mins = dt.minute.toString().padLeft(2, '0');
    final secs = dt.second.toString().padLeft(2, '0');
    return '$hours:$mins:$secs';
  }

  @override
  Widget build(BuildContext context) {
    final actualSource = dataSourceType ?? (isDemoMode ? DataSourceType.demo : DataSourceType.live);

    if (errorMessage != null && errorMessage!.isNotEmpty) {
      final isAuthError = errorCategory == ErrorCategory.auth || errorMessage!.toLowerCase().contains('ключ') || errorMessage!.contains('401') || errorMessage!.contains('403');
      final errorColor = isAuthError ? VoxColors.amber : VoxColors.red;
      final errorIcon = isAuthError ? Icons.lock_outline_rounded : Icons.wifi_off_rounded;

      return Container(
        margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: errorColor.withValues(alpha: 0.15),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: errorColor.withValues(alpha: 0.4)),
        ),
        child: Row(
          children: [
            Icon(errorIcon, color: errorColor, size: 20),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    isAuthError ? 'Ошибка авторизации' : 'Сетевая ошибка',
                    style: TextStyle(color: errorColor, fontSize: 12, fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    errorMessage!,
                    style: TextStyle(color: errorColor, fontSize: 12, fontWeight: FontWeight.w500),
                  ),
                ],
              ),
            ),
            if (isAuthError && onOpenSettings != null)
              TextButton(
                onPressed: onOpenSettings,
                style: TextButton.styleFrom(
                  foregroundColor: errorColor,
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  minimumSize: Size.zero,
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
                child: const Text('Ключ', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 12)),
              )
            else
              IconButton(
                icon: Icon(Icons.refresh, color: errorColor, size: 18),
                onPressed: onRefresh,
                tooltip: 'Повторить',
              ),
          ],
        ),
      );
    }

    Color badgeBg;
    Color badgeBorder;
    Color badgeText;
    IconData badgeIcon;
    String badgeLabel;

    switch (actualSource) {
      case DataSourceType.live:
        badgeBg = VoxColors.emerald.withValues(alpha: 0.15);
        badgeBorder = VoxColors.emerald.withValues(alpha: 0.5);
        badgeText = VoxColors.emerald;
        badgeIcon = Icons.cloud_done_rounded;
        badgeLabel = 'LIVE (Worker)';
        break;
      case DataSourceType.cached:
        badgeBg = VoxColors.cyan.withValues(alpha: 0.15);
        badgeBorder = VoxColors.cyan.withValues(alpha: 0.5);
        badgeText = VoxColors.cyan;
        badgeIcon = Icons.storage_rounded;
        badgeLabel = 'КЕШ (Офлайн)';
        break;
      case DataSourceType.demo:
        badgeBg = VoxColors.amber.withValues(alpha: 0.15);
        badgeBorder = VoxColors.amber.withValues(alpha: 0.5);
        badgeText = VoxColors.amber;
        badgeIcon = Icons.shield_outlined;
        badgeLabel = 'ДЕМО-РЕЖИМ';
        break;
    }

    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: VoxColors.surface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: VoxColors.border),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: badgeBg,
              borderRadius: BorderRadius.circular(6),
              border: Border.all(color: badgeBorder),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(badgeIcon, size: 13, color: badgeText),
                const SizedBox(width: 4),
                Text(
                  badgeLabel,
                  style: TextStyle(
                    color: badgeText,
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'Синхр: ${_formatSyncTime(lastSyncTime)}',
              style: const TextStyle(
                color: VoxColors.textMuted,
                fontSize: 12,
              ),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (isSyncing)
            const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2, color: VoxColors.primary),
            )
          else
            GestureDetector(
              onTap: onRefresh,
              child: const Icon(Icons.sync_rounded, color: VoxColors.textSecondary, size: 20),
            ),
        ],
      ),
    );
  }
}
