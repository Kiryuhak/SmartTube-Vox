import 'package:flutter/material.dart';
import '../models/incident_status.dart';
import '../models/report.dart';
import '../services/app_repository.dart';
import '../theme/vox_theme.dart';
import '../widgets/status_badge.dart';
import 'report_detail_screen.dart';

class ReportsScreen extends StatefulWidget {
  final AppRepository repository;
  final String initialStatusFilter;

  const ReportsScreen({
    super.key,
    required this.repository,
    this.initialStatusFilter = 'ALL',
  });

  @override
  State<ReportsScreen> createState() => _ReportsScreenState();
}

class _ReportsScreenState extends State<ReportsScreen> {
  final TextEditingController _searchController = TextEditingController();
  late String _selectedStatus;
  String _selectedCategory = 'ALL';
  String _selectedPlatform = 'ALL';
  String _sort = 'newest';

  @override
  void initState() {
    super.initState();
    _selectedStatus = widget.initialStatusFilter;
  }

  @override
  void didUpdateWidget(covariant ReportsScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.initialStatusFilter != oldWidget.initialStatusFilter) {
      setState(() {
        _selectedStatus = widget.initialStatusFilter;
      });
    }
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<ReportItem> _getFilteredReports() {
    final query = _searchController.text.trim().toLowerCase();
    var list = widget.repository.reports;

    if (_selectedStatus != 'ALL') {
      final target = IncidentStatus.fromString(_selectedStatus);
      list = list.where((r) => r.status == target).toList();
    }

    if (_selectedCategory != 'ALL') {
      list = list.where((r) => r.errorCategory.toUpperCase() == _selectedCategory.toUpperCase()).toList();
    }

    if (_selectedPlatform != 'ALL') {
      list = list.where((r) => r.platform.toUpperCase() == _selectedPlatform.toUpperCase()).toList();
    }

    if (query.isNotEmpty) {
      list = list.where((r) {
        return r.reportId.toLowerCase().contains(query) ||
            r.device.displayName.toLowerCase().contains(query) ||
            r.errorSignature.toLowerCase().contains(query) ||
            r.errorCategory.toLowerCase().contains(query) ||
            r.developerNotes.toLowerCase().contains(query);
      }).toList();
    }

    if (_sort == 'oldest') {
      list = List.of(list)..sort((a, b) => a.createdAt.compareTo(b.createdAt));
    } else {
      list = List.of(list)..sort((a, b) => b.createdAt.compareTo(a.createdAt));
    }

    return list;
  }

  Future<void> _showBatchInvestigatingDialog() async {
    final repo = widget.repository;
    final isLive = repo.dataSourceType == DataSourceType.live && repo.hasAdminKey;
    final reasonController = TextEditingController(
      text: 'VOX 9.2: Перевод 12 утверждённых отчётов в INVESTIGATING после triage 18 инцидентов',
    );

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) {
        return AlertDialog(
          backgroundColor: VoxColors.surface,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          title: const Row(
            children: [
              Icon(Icons.playlist_add_check_rounded, color: VoxColors.amber, size: 24),
              SizedBox(width: 8),
              Expanded(
                child: Text(
                  'Пакетный перевод (12 отчётов)',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                ),
              ),
            ],
          ),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: isLive ? VoxColors.emerald.withValues(alpha: 0.15) : VoxColors.amber.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(
                      color: isLive ? VoxColors.emerald.withValues(alpha: 0.4) : VoxColors.amber.withValues(alpha: 0.4),
                    ),
                  ),
                  child: Row(
                    children: [
                      Icon(
                        isLive ? Icons.cloud_done_rounded : Icons.shield_outlined,
                        color: isLive ? VoxColors.emerald : VoxColors.amber,
                        size: 18,
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          isLive
                              ? 'Источник: LIVE (Cloudflare Worker)\nСтатусы обновятся в production D1.'
                              : 'Источник: ДЕМО-РЕЖИМ (Локально)\nСтатусы изменятся только в памяти приложения.',
                          style: TextStyle(
                            color: isLive ? VoxColors.emerald : VoxColors.amber,
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 12),
                const Text(
                  'Утверждённые отчёты к переводу:',
                  style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: VoxColors.textPrimary),
                ),
                const SizedBox(height: 6),
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: VoxColors.card,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: VoxColors.border),
                  ),
                  child: const Text(
                    'VOX-FC763605, VOX-319BD42A, VOX-BAEAAEB6, VOX-EF0F761F, VOX-0BF9F24D, VOX-C04586D3, VOX-D5F0C645, VOX-C6A533DD, VOX-6B514E8A, VOX-2BC4753A, VOX-EC363B97, VOX-6D00AEA9, VOX-3345BBFD, VOX-BD27BAD9, VOX-7AE27244, VOX-9121247C, VOX-28070F7A',
                    style: TextStyle(fontSize: 11, fontFamily: 'monospace', color: VoxColors.textSecondary),
                  ),
                ),
                const SizedBox(height: 12),
                const Text(
                  'Обоснование перевода:',
                  style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: VoxColors.textSecondary),
                ),
                const SizedBox(height: 6),
                TextField(
                  controller: reasonController,
                  maxLines: 2,
                  decoration: const InputDecoration(
                    hintText: 'Причина изменения статуса...',
                  ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Отмена', style: TextStyle(color: VoxColors.textMuted)),
            ),
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: VoxColors.primary,
                foregroundColor: Colors.white,
              ),
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Подтвердить перевод'),
            ),
          ],
        );
      },
    );

    if (confirmed == true && mounted) {
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (_) => const Center(
          child: Card(
            color: VoxColors.surface,
            child: Padding(
              padding: EdgeInsets.all(20),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  CircularProgressIndicator(color: VoxColors.primary),
                  SizedBox(height: 16),
                  Text('Выполняется пакетный перевод...', style: TextStyle(color: VoxColors.textPrimary)),
                ],
              ),
            ),
          ),
        ),
      );

      final result = await repo.batchTransitionInvestigatingReports(
        reason: reasonController.text.trim(),
      );

      if (mounted) {
        Navigator.pop(context); // закрыть прогресс
        showDialog(
          context: context,
          builder: (ctx) => AlertDialog(
            backgroundColor: VoxColors.surface,
            title: Text(
              result.isLive ? 'Результат Live-обновления' : 'Результат Демо-обновления',
              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
            ),
            content: Text(
              result.message ?? 'Обновлено отчётов: ${result.successCount} из ${result.totalCount}',
              style: const TextStyle(fontSize: 13, color: VoxColors.textPrimary),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('Понятно'),
              ),
            ],
          ),
        );
      }
    }
  }

  String _formatTimestamp(int ms) {
    if (ms <= 0) return '';
    final dt = DateTime.fromMillisecondsSinceEpoch(ms);
    final now = DateTime.now();
    final diff = now.difference(dt);

    if (diff.inMinutes < 60) {
      return '${diff.inMinutes} мин назад';
    } else if (diff.inHours < 24) {
      return '${diff.inHours} ч назад';
    } else if (diff.inDays < 7) {
      return '${diff.inDays} дн назад';
    }
    return '${dt.day.toString().padLeft(2, '0')}.${dt.month.toString().padLeft(2, '0')} ${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final reports = _getFilteredReports();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Диагностические отчёты'),
        actions: [
          IconButton(
            icon: const Icon(Icons.playlist_add_check_rounded),
            tooltip: 'Пакетный перевод 12 отчётов',
            onPressed: _showBatchInvestigatingDialog,
          ),
          PopupMenuButton<String>(
            icon: const Icon(Icons.sort_rounded),
            tooltip: 'Сортировка',
            onSelected: (val) {
              setState(() {
                _sort = val;
              });
            },
            itemBuilder: (_) => [
              const PopupMenuItem(value: 'newest', child: Text('Сначала новые')),
              const PopupMenuItem(value: 'oldest', child: Text('Сначала старые')),
            ],
          ),
        ],
      ),
      body: Column(
        children: [
          // Поиск
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 6),
            child: TextField(
              controller: _searchController,
              decoration: InputDecoration(
                hintText: 'Поиск по ID, устройству, ошибке...',
                prefixIcon: const Icon(Icons.search_rounded, color: VoxColors.textMuted, size: 20),
                suffixIcon: _searchController.text.isNotEmpty
                    ? IconButton(
                        icon: const Icon(Icons.clear, size: 18),
                        onPressed: () {
                          _searchController.clear();
                          setState(() {});
                        },
                      )
                    : null,
              ),
              onChanged: (_) => setState(() {}),
            ),
          ),

          // Фильтры статусов (горизонтальная прокрутка)
          SizedBox(
            height: 40,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16),
              children: [
                _buildStatusChip('ALL', 'Все (${widget.repository.reports.length})'),
                _buildStatusChip('NEW', 'Новые'),
                _buildStatusChip('INVESTIGATING', 'В работе'),
                _buildStatusChip('FIXED_AWAITING_VERIFICATION', 'Ждут проверки'),
                _buildStatusChip('RESOLVED', 'Решено'),
                _buildStatusChip('KNOWN_LIMITATION', 'Ограничения'),
                _buildStatusChip('REGRESSION', 'Регрессии'),
                _buildStatusChip('IGNORED_TEST', 'Тест/игнор'),
              ],
            ),
          ),

          const SizedBox(height: 4),

          // Фильтры категорий и платформ
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
            child: Row(
              children: [
                Expanded(
                  child: SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children: [
                        _buildCategoryChip('ALL', 'Все категории'),
                        _buildCategoryChip('PLAYER', 'PLAYER'),
                        _buildCategoryChip('AUDIO', 'AUDIO'),
                        _buildCategoryChip('NETWORK', 'NETWORK'),
                        _buildCategoryChip('OTA', 'OTA'),
                        _buildCategoryChip('VOX_CORE', 'VOX_CORE'),
                      ],
                    ),
                  ),
                ),
                Text(
                  'Найдено: ${reports.length}',
                  style: const TextStyle(color: VoxColors.textMuted, fontSize: 11),
                ),
              ],
            ),
          ),

          const Divider(height: 12),

          // Список карточек отчётов
          Expanded(
            child: reports.isEmpty
                ? Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Icon(Icons.search_off_rounded, size: 48, color: VoxColors.textMuted),
                        const SizedBox(height: 12),
                        const Text(
                          'Отчётов не найдено',
                          style: TextStyle(color: VoxColors.textSecondary, fontSize: 15, fontWeight: FontWeight.w600),
                        ),
                        const SizedBox(height: 6),
                        const Text(
                          'Попробуйте изменить параметры поиска или фильтры',
                          style: TextStyle(color: VoxColors.textMuted, fontSize: 13),
                        ),
                        if (_selectedStatus != 'ALL' || _selectedCategory != 'ALL' || _searchController.text.isNotEmpty)
                          Padding(
                            padding: const EdgeInsets.only(top: 14),
                            child: OutlinedButton(
                              onPressed: () {
                                _searchController.clear();
                                setState(() {
                                  _selectedStatus = 'ALL';
                                  _selectedCategory = 'ALL';
                                  _selectedPlatform = 'ALL';
                                });
                              },
                              child: const Text('Сбросить фильтры'),
                            ),
                          ),
                      ],
                    ),
                  )
                : RefreshIndicator(
                    onRefresh: () => widget.repository.refreshAll(),
                    color: VoxColors.primary,
                    child: ListView.builder(
                      itemCount: reports.length,
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      itemBuilder: (context, index) {
                        final r = reports[index];
                        return _buildReportCard(r);
                      },
                    ),
                  ),
          ),
        ],
      ),
    );
  }

  Widget _buildStatusChip(String code, String label) {
    final isSelected = _selectedStatus == code;
    return Padding(
      padding: const EdgeInsets.only(right: 6),
      child: FilterChip(
        selected: isSelected,
        label: Text(
          label,
          style: TextStyle(
            fontSize: 12,
            fontWeight: isSelected ? FontWeight.w700 : FontWeight.w500,
            color: isSelected ? VoxColors.textPrimary : VoxColors.textSecondary,
          ),
        ),
        backgroundColor: VoxColors.card,
        selectedColor: VoxColors.primary.withValues(alpha: 0.3),
        side: BorderSide(
          color: isSelected ? VoxColors.primary : VoxColors.border,
          width: 1,
        ),
        onSelected: (_) {
          setState(() {
            _selectedStatus = code;
          });
        },
      ),
    );
  }

  Widget _buildCategoryChip(String cat, String label) {
    final isSelected = _selectedCategory == cat;
    return Padding(
      padding: const EdgeInsets.only(right: 6),
      child: ActionChip(
        label: Text(
          label,
          style: TextStyle(
            fontSize: 11,
            fontWeight: isSelected ? FontWeight.w700 : FontWeight.w500,
            color: isSelected ? VoxColors.cyan : VoxColors.textMuted,
          ),
        ),
        backgroundColor: isSelected ? VoxColors.cyan.withValues(alpha: 0.15) : VoxColors.card,
        side: BorderSide(
          color: isSelected ? VoxColors.cyan.withValues(alpha: 0.5) : VoxColors.border,
          width: 1,
        ),
        onPressed: () {
          setState(() {
            _selectedCategory = cat;
          });
        },
      ),
    );
  }

  Widget _buildReportCard(ReportItem r) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 5),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () {
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (_) => ReportDetailScreen(
                report: r,
                repository: widget.repository,
              ),
            ),
          );
        },
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Верхняя строка: ID, Похожие, Статус
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Expanded(
                    child: Row(
                      children: [
                        Text(
                          r.reportId,
                          style: const TextStyle(
                            color: VoxColors.primary,
                            fontSize: 14,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        if (r.duplicateCount > 0) ...[
                          const SizedBox(width: 8),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                            decoration: BoxDecoration(
                              color: VoxColors.primary.withValues(alpha: 0.15),
                              borderRadius: BorderRadius.circular(4),
                            ),
                            child: Text(
                              'Похожие: ${r.duplicateCount}',
                              style: const TextStyle(
                                color: VoxColors.primary,
                                fontSize: 10,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  Flexible(
                    child: StatusBadge(status: r.status, compact: true),
                  ),
                ],
              ),

              const SizedBox(height: 8),

              // Сигнатура ошибки
              Text(
                r.errorSignature.isNotEmpty ? r.errorSignature : 'UNKNOWN_ERROR_SIGNATURE',
                style: const TextStyle(
                  color: VoxColors.textPrimary,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),

              const SizedBox(height: 8),

              // Метаданные: Устройство, Категория, Версия, Время
              Row(
                children: [
                  const Icon(Icons.tv_rounded, size: 14, color: VoxColors.textMuted),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Text(
                      r.device.displayName,
                      style: const TextStyle(color: VoxColors.textSecondary, fontSize: 12),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                    decoration: BoxDecoration(
                      color: VoxColors.surface,
                      borderRadius: BorderRadius.circular(4),
                      border: Border.all(color: VoxColors.border),
                    ),
                    child: Text(
                      r.errorCategory,
                      style: const TextStyle(color: VoxColors.cyan, fontSize: 10, fontWeight: FontWeight.w600),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    _formatTimestamp(r.createdAt),
                    style: const TextStyle(color: VoxColors.textMuted, fontSize: 11),
                  ),
                ],
              ),

              // Если есть заметка разработчика
              if (r.developerNotes.isNotEmpty) ...[
                const SizedBox(height: 8),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: VoxColors.surface,
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(color: VoxColors.border),
                  ),
                  child: Text(
                    r.developerNotes.replaceAll('\n', ' • '),
                    style: const TextStyle(color: VoxColors.textMuted, fontSize: 11, fontStyle: FontStyle.italic),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
