import 'package:flutter/material.dart';
import '../services/app_repository.dart';
import '../theme/vox_theme.dart';
import '../widgets/metric_card.dart';
import '../widgets/incident_trend_chart.dart';
import '../widgets/status_badge.dart';
import '../widgets/sync_banner.dart';
import 'report_detail_screen.dart';

class HomeScreen extends StatelessWidget {
  final AppRepository repository;
  final Function(String statusFilter) onSelectStatusFilter;

  const HomeScreen({
    super.key,
    required this.repository,
    required this.onSelectStatusFilter,
  });

  @override
  Widget build(BuildContext context) {
    final stats = repository.stats;
    final isSyncing = repository.isSyncing;
    final lastError = repository.lastError;
    final isDemoMode = repository.isDemoMode;

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(6),
              decoration: BoxDecoration(
                color: VoxColors.primary.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(8),
              ),
              child: const Icon(Icons.analytics_outlined, color: VoxColors.primary, size: 20),
            ),
            const SizedBox(width: 10),
            const Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text('SmartTube VOX Pocket', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700), overflow: TextOverflow.ellipsis),
                  Text('Диагностика и мониторинг', style: TextStyle(fontSize: 11, color: VoxColors.textMuted), overflow: TextOverflow.ellipsis),
                ],
              ),
            ),
          ],
        ),
        actions: [
          IconButton(
            icon: isSyncing
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2, color: VoxColors.primary),
                  )
                : const Icon(Icons.refresh_rounded),
            tooltip: 'Синхронизировать',
            onPressed: isSyncing ? null : () => repository.refreshAll(),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () => repository.refreshAll(),
        color: VoxColors.primary,
        backgroundColor: VoxColors.surface,
        child: ListView(
          padding: const EdgeInsets.only(bottom: 24),
          children: [
            SyncBanner(
              isDemoMode: isDemoMode,
              dataSourceType: repository.dataSourceType,
              isSyncing: isSyncing,
              errorMessage: lastError,
              errorCategory: repository.lastErrorCategory,
              lastSyncTime: stats?.lastSyncTime ?? 0,
              onRefresh: () => repository.refreshAll(),
            ),

            // Карточки ключевых статусов
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Статус инцидентов',
                    style: TextStyle(
                      color: VoxColors.textPrimary,
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 10),
                  GridView.count(
                    crossAxisCount: 2,
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    crossAxisSpacing: 10,
                    mainAxisSpacing: 10,
                    childAspectRatio: 2.1,
                    children: [
                      MetricCard(
                        title: 'Новые',
                        count: repository.realNewCount,
                        accentColor: const Color(0xFF6B7280),
                        icon: Icons.fiber_new_rounded,
                        onTap: () => onSelectStatusFilter('NEW'),
                      ),
                      MetricCard(
                        title: 'В работе',
                        count: repository.realInvestigatingCount,
                        accentColor: VoxColors.amber,
                        icon: Icons.engineering_rounded,
                        onTap: () => onSelectStatusFilter('INVESTIGATING'),
                      ),
                      MetricCard(
                        title: 'Ждут проверки',
                        count: stats?.awaitingVerificationCount ?? 0,
                        accentColor: VoxColors.teal,
                        icon: Icons.checklist_rtl_rounded,
                        onTap: () => onSelectStatusFilter('FIXED_AWAITING_VERIFICATION'),
                      ),
                      MetricCard(
                        title: 'Решено',
                        count: stats?.resolvedCount ?? 0,
                        accentColor: VoxColors.emerald,
                        icon: Icons.verified_rounded,
                        onTap: () => onSelectStatusFilter('RESOLVED'),
                      ),
                      MetricCard(
                        title: 'Ограничения',
                        count: stats?.knownLimitationsCount ?? 0,
                        accentColor: VoxColors.textSecondary,
                        icon: Icons.info_outline_rounded,
                        onTap: () => onSelectStatusFilter('KNOWN_LIMITATION'),
                      ),
                      MetricCard(
                        title: 'Регрессии',
                        count: stats?.regressionCount ?? 0,
                        accentColor: VoxColors.red,
                        icon: Icons.report_problem_rounded,
                        onTap: () => onSelectStatusFilter('REGRESSION'),
                      ),
                    ],
                  ),
                ],
              ),
            ),

            // Сводка по периодам
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 12),
                decoration: BoxDecoration(
                  color: VoxColors.card,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: VoxColors.border),
                ),
                child: Row(
                  children: [
                    Expanded(child: _buildPeriodStat('Всего', (stats?.totalReports ?? repository.reports.length).toString())),
                    _buildPeriodDivider(),
                    Expanded(child: _buildPeriodStat('За 24ч', (stats?.last24hCount ?? 0).toString())),
                    _buildPeriodDivider(),
                    Expanded(child: _buildPeriodStat('За 7д', (stats?.last7dCount ?? 0).toString())),
                    _buildPeriodDivider(),
                    Expanded(child: _buildPeriodStat('За 30д', (stats?.last30dCount ?? 0).toString())),
                  ],
                ),
              ),
            ),

            // График динамики
            if (stats != null && stats.incidentTrend.isNotEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                child: IncidentTrendChart(points: stats.incidentTrend),
              ),

            // Распределение по категориям
            if (stats != null && stats.categoryDistribution.isNotEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                child: _buildCategoryDistribution(stats.categoryDistribution),
              ),

            // Последние инциденты
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text(
                    'Свежие отчёты',
                    style: TextStyle(
                      color: VoxColors.textPrimary,
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  Text(
                    'Показано: ${repository.reports.take(5).length}',
                    style: const TextStyle(color: VoxColors.textMuted, fontSize: 12),
                  ),
                ],
              ),
            ),

            ...repository.reports.take(5).map((r) {
              return Card(
                margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                child: ListTile(
                  contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
                  title: Row(
                    children: [
                      Text(
                        r.reportId,
                        style: const TextStyle(
                          color: VoxColors.primary,
                          fontWeight: FontWeight.w700,
                          fontSize: 14,
                        ),
                      ),
                      const SizedBox(width: 8),
                      Flexible(
                        child: StatusBadge(status: r.status, compact: true),
                      ),
                    ],
                  ),
                  subtitle: Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Text(
                      '${r.device.displayName} • ${r.errorCategory}',
                      style: const TextStyle(color: VoxColors.textSecondary, fontSize: 12),
                    ),
                  ),
                  trailing: const Icon(Icons.chevron_right_rounded, color: VoxColors.textMuted, size: 20),
                  onTap: () {
                    Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (_) => ReportDetailScreen(
                          report: r,
                          repository: repository,
                        ),
                      ),
                    );
                  },
                ),
              );
            }),
          ],
        ),
      ),
    );
  }

  Widget _buildPeriodStat(String label, String value) {
    return Column(
      children: [
        Text(
          value,
          style: const TextStyle(
            color: VoxColors.textPrimary,
            fontSize: 16,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(height: 2),
        Text(
          label,
          style: const TextStyle(
            color: VoxColors.textMuted,
            fontSize: 11,
          ),
        ),
      ],
    );
  }

  Widget _buildPeriodDivider() {
    return Container(
      height: 24,
      width: 1,
      color: VoxColors.border,
    );
  }

  Widget _buildCategoryDistribution(Map<String, int> categories) {
    final total = categories.values.fold<int>(0, (sum, val) => sum + val);
    final effectiveTotal = total > 0 ? total : 1;

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: VoxColors.card,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: VoxColors.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Распределение по категориям',
            style: TextStyle(
              color: VoxColors.textPrimary,
              fontSize: 13,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 12),
          ...categories.entries.map((entry) {
            final percent = (entry.value / effectiveTotal * 100).toStringAsFixed(0);
            return Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Column(
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        entry.key,
                        style: const TextStyle(color: VoxColors.textSecondary, fontSize: 12, fontWeight: FontWeight.w500),
                      ),
                      Text(
                        '${entry.value} ($percent%)',
                        style: const TextStyle(color: VoxColors.textPrimary, fontSize: 12, fontWeight: FontWeight.w600),
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(4),
                    child: LinearProgressIndicator(
                      value: entry.value / effectiveTotal,
                      backgroundColor: VoxColors.surface,
                      color: _getCategoryColor(entry.key),
                      minHeight: 5,
                    ),
                  ),
                ],
              ),
            );
          }),
        ],
      ),
    );
  }

  Color _getCategoryColor(String category) {
    switch (category.toUpperCase()) {
      case 'PLAYER':
        return VoxColors.primary;
      case 'AUDIO':
        return VoxColors.cyan;
      case 'NETWORK':
        return VoxColors.amber;
      case 'OTA':
        return VoxColors.purple;
      case 'VOX_CORE':
        return VoxColors.emerald;
      default:
        return VoxColors.textSecondary;
    }
  }
}
