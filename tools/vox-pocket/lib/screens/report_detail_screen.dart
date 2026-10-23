import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';
import '../models/incident_status.dart';
import '../models/report.dart';
import '../services/app_repository.dart';
import '../theme/vox_theme.dart';
import '../widgets/status_badge.dart';

class ReportDetailScreen extends StatefulWidget {
  final ReportItem report;
  final AppRepository repository;

  const ReportDetailScreen({
    super.key,
    required this.report,
    required this.repository,
  });

  @override
  State<ReportDetailScreen> createState() => _ReportDetailScreenState();
}

class _ReportDetailScreenState extends State<ReportDetailScreen> with SingleTickerProviderStateMixin {
  late TabController _tabController;
  late ReportItem _currentReport;
  bool _isUpdatingStatus = false;
  bool _isAddingNote = false;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 4, vsync: this);
    _currentReport = widget.report;
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  Future<void> _showStatusChangeDialog() async {
    IncidentStatus selected = _currentReport.status;
    final reasonController = TextEditingController();

    final confirmed = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: VoxColors.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setModalState) {
            return Padding(
              padding: EdgeInsets.only(
                left: 20,
                right: 20,
                top: 20,
                bottom: MediaQuery.of(ctx).viewInsets.bottom + 20,
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Text(
                        'Изменение статуса',
                        style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: VoxColors.textPrimary),
                      ),
                      IconButton(
                        icon: const Icon(Icons.close, size: 20),
                        onPressed: () => Navigator.pop(ctx, false),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                    decoration: BoxDecoration(
                      color: widget.repository.dataSourceType == DataSourceType.live
                          ? VoxColors.emerald.withValues(alpha: 0.15)
                          : VoxColors.amber.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(
                        color: widget.repository.dataSourceType == DataSourceType.live
                            ? VoxColors.emerald.withValues(alpha: 0.4)
                            : VoxColors.amber.withValues(alpha: 0.4),
                      ),
                    ),
                    child: Row(
                      children: [
                        Icon(
                          widget.repository.dataSourceType == DataSourceType.live
                              ? Icons.cloud_done_rounded
                              : Icons.shield_outlined,
                          size: 16,
                          color: widget.repository.dataSourceType == DataSourceType.live
                              ? VoxColors.emerald
                              : VoxColors.amber,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            widget.repository.dataSourceType == DataSourceType.live
                                ? 'LIVE-РЕЖИМ: Статус будет обновлён в production D1.'
                                : 'ДЕМО-РЕЖИМ: Статус обновится только локально в памяти.',
                            style: TextStyle(
                              color: widget.repository.dataSourceType == DataSourceType.live
                                  ? VoxColors.emerald
                                  : VoxColors.amber,
                              fontSize: 11,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    'Выберите новый статус инцидента:',
                    style: TextStyle(color: VoxColors.textSecondary, fontSize: 13),
                  ),
                  const SizedBox(height: 10),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    decoration: BoxDecoration(
                      color: VoxColors.card,
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(color: VoxColors.border),
                    ),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<IncidentStatus>(
                        value: selected,
                        isExpanded: true,
                        dropdownColor: VoxColors.card,
                        items: IncidentStatus.selectableStatuses.map((s) {
                          return DropdownMenuItem(
                            value: s,
                            child: Row(
                              children: [
                                Container(
                                  width: 8,
                                  height: 8,
                                  decoration: BoxDecoration(color: s.color, shape: BoxShape.circle),
                                ),
                                const SizedBox(width: 8),
                                Text(s.label, style: const TextStyle(fontSize: 14)),
                              ],
                            ),
                          );
                        }).toList(),
                        onChanged: (newVal) {
                          if (newVal != null) {
                            setModalState(() {
                              selected = newVal;
                            });
                          }
                        },
                      ),
                    ),
                  ),
                  const SizedBox(height: 14),
                  const Text(
                    'Обоснование / Причина изменения:',
                    style: TextStyle(color: VoxColors.textSecondary, fontSize: 13),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: reasonController,
                    maxLines: 2,
                    decoration: const InputDecoration(
                      hintText: 'Укажите причину или коммит исправления...',
                    ),
                  ),
                  const SizedBox(height: 18),
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton(
                      style: ElevatedButton.styleFrom(
                        backgroundColor: VoxColors.primary,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(vertical: 13),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                      ),
                      onPressed: () {
                        Navigator.pop(ctx, true);
                      },
                      child: const Text('Подтвердить изменение', style: TextStyle(fontWeight: FontWeight.w700)),
                    ),
                  ),
                ],
              ),
            );
          },
        );
      },
    );

    if (confirmed == true && mounted) {
      setState(() {
        _isUpdatingStatus = true;
      });

      try {
        final updated = await widget.repository.updateReportStatus(
          _currentReport.reportId,
          selected,
          reason: reasonController.text,
        );
        if (mounted) {
          setState(() {
            _currentReport = updated;
          });
          final isLive = widget.repository.dataSourceType == DataSourceType.live;
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                isLive
                    ? 'Сервер подтвердил: статус изменён на "${selected.label}"'
                    : 'ДЕМО-РЕЖИМ: Статус изменён локально на "${selected.label}". Сервер не затронут.',
              ),
              backgroundColor: isLive ? VoxColors.emerald : VoxColors.amber,
            ),
          );
        }
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('Ошибка обновления: $e'),
              backgroundColor: VoxColors.red,
            ),
          );
        }
      } finally {
        if (mounted) {
          setState(() {
            _isUpdatingStatus = false;
          });
        }
      }
    }
  }

  Future<void> _showAddNoteDialog() async {
    final noteController = TextEditingController();

    final noteText = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      backgroundColor: VoxColors.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (ctx) {
        return Padding(
          padding: EdgeInsets.only(
            left: 20,
            right: 20,
            top: 20,
            bottom: MediaQuery.of(ctx).viewInsets.bottom + 20,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text(
                    'Добавить заметку',
                    style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: VoxColors.textPrimary),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close, size: 20),
                    onPressed: () => Navigator.pop(ctx),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              TextField(
                controller: noteController,
                autofocus: true,
                maxLines: 4,
                decoration: const InputDecoration(
                  hintText: 'Введите текст заметки разработчика...',
                ),
              ),
              const SizedBox(height: 16),
              SizedBox(
                width: double.infinity,
                child: ElevatedButton(
                  style: ElevatedButton.styleFrom(
                    backgroundColor: VoxColors.primary,
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 13),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                  ),
                  onPressed: () {
                    final text = noteController.text.trim();
                    if (text.isNotEmpty) {
                      Navigator.pop(ctx, text);
                    }
                  },
                  child: const Text('Сохранить заметку', style: TextStyle(fontWeight: FontWeight.w700)),
                ),
              ),
            ],
          ),
        );
      },
    );

    if (noteText != null && noteText.isNotEmpty && mounted) {
      setState(() {
        _isAddingNote = true;
      });

      try {
        final updated = await widget.repository.addReportNote(_currentReport.reportId, noteText);
        if (mounted) {
          setState(() {
            _currentReport = updated;
          });
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Заметка сохранена'), backgroundColor: VoxColors.emerald),
          );
        }
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Ошибка сохранения заметки: $e'), backgroundColor: VoxColors.red),
          );
        }
      } finally {
        if (mounted) {
          setState(() {
            _isAddingNote = false;
          });
        }
      }
    }
  }

  Future<void> _shareReportJson() async {
    try {
      final jsonStr = await widget.repository.downloadReportJson(_currentReport.reportId);
      await SharePlus.instance.share(
        ShareParams(
          text: jsonStr,
          subject: 'SmartTube VOX Diagnostic ${_currentReport.reportId}',
        ),
      );
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Не удалось поделиться отчётом: $e'), backgroundColor: VoxColors.red),
        );
      }
    }
  }

  Future<void> _copyJsonToClipboard() async {
    try {
      final jsonStr = await widget.repository.downloadReportJson(_currentReport.reportId);
      await Clipboard.setData(ClipboardData(text: jsonStr));
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('JSON отчёта скопирован в буфер обмена'), backgroundColor: VoxColors.emerald),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Ошибка копирования: $e'), backgroundColor: VoxColors.red),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_currentReport.reportId),
        actions: [
          IconButton(
            icon: const Icon(Icons.share_rounded),
            tooltip: 'Поделиться отчётом',
            onPressed: _shareReportJson,
          ),
        ],
        bottom: TabBar(
          controller: _tabController,
          tabs: const [
            Tab(text: 'Обзор'),
            Tab(text: 'События'),
            Tab(text: 'Заметки'),
            Tab(text: 'Файлы'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        children: [
          _buildOverviewTab(),
          _buildEventsTab(),
          _buildNotesTab(),
          _buildFilesTab(),
        ],
      ),
    );
  }

  Widget _buildOverviewTab() {
    final dt = DateTime.fromMillisecondsSinceEpoch(_currentReport.createdAt);
    final dateStr = '${dt.day.toString().padLeft(2, '0')}.${dt.month.toString().padLeft(2, '0')}.${dt.year} ${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}:${dt.second.toString().padLeft(2, '0')}';

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        // Карточка статуса с кнопкой изменения
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: VoxColors.card,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: VoxColors.border),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Текущий статус:', style: TextStyle(color: VoxColors.textMuted, fontSize: 12)),
                  const SizedBox(height: 6),
                  StatusBadge(status: _currentReport.status),
                ],
              ),
              ElevatedButton.icon(
                style: ElevatedButton.styleFrom(
                  backgroundColor: VoxColors.primary,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                ),
                icon: _isUpdatingStatus
                    ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                    : const Icon(Icons.edit_note_rounded, size: 18),
                label: const Text('Изменить статус'),
                onPressed: _isUpdatingStatus ? null : _showStatusChangeDialog,
              ),
            ],
          ),
        ),

        const SizedBox(height: 14),

        // Техническая информация
        _buildSectionHeader('Технические атрибуты'),
        _buildInfoCard([
          _buildInfoRow('Идентификатор', _currentReport.reportId),
          _buildInfoRow('Категория ошибки', _currentReport.errorCategory),
          _buildInfoRow('Сигнатура', _currentReport.errorSignature),
          _buildInfoRow('Время создания', dateStr),
          _buildInfoRow('Платформа', _currentReport.platform),
          _buildInfoRow('Версия приложения', _currentReport.appVersion),
          if (_currentReport.duplicateCount > 0)
            _buildInfoRow('Похожие отчёты', '${_currentReport.duplicateCount} шт.'),
        ]),

        const SizedBox(height: 14),

        // Устройство
        _buildSectionHeader('Информация об устройстве'),
        _buildInfoCard([
          _buildInfoRow('Модель', _currentReport.device.displayName),
          _buildInfoRow('Производитель', _currentReport.device.manufacturer.isNotEmpty ? _currentReport.device.manufacturer : '—'),
          _buildInfoRow('ОС / SDK', _currentReport.device.apiLabel.isNotEmpty ? _currentReport.device.apiLabel : '—'),
          _buildInfoRow('Семейство', _currentReport.device.family.isNotEmpty ? _currentReport.device.family : '—'),
          if (_currentReport.device.tier.isNotEmpty)
            _buildInfoRow('Класс производительности', _currentReport.device.tier),
        ]),

        if (_currentReport.canonicalGroup.isNotEmpty || _currentReport.fixCommit.isNotEmpty || _currentReport.rootCause.isNotEmpty) ...[
          const SizedBox(height: 14),
          _buildSectionHeader('Инженерный разбор'),
          _buildInfoCard([
            if (_currentReport.canonicalGroup.isNotEmpty)
              _buildInfoRow('Группа инцидента', _currentReport.canonicalGroup),
            if (_currentReport.rootCause.isNotEmpty)
              _buildInfoRow('Причина сбоя', _currentReport.rootCause),
            if (_currentReport.fixCommit.isNotEmpty)
              _buildInfoRow('Коммит исправления', _currentReport.fixCommit),
            if (_currentReport.evidence.isNotEmpty)
              _buildInfoRow('Доказательство', _currentReport.evidence),
          ]),
        ],
      ],
    );
  }

  Widget _buildEventsTab() {
    final events = _currentReport.safeRecentEvents;

    if (events.isEmpty) {
      return const Center(
        child: Text('События не зафиксированы', style: TextStyle(color: VoxColors.textMuted)),
      );
    }

    return ListView.builder(
      padding: const EdgeInsets.all(16),
      itemCount: events.length,
      itemBuilder: (ctx, idx) {
        final ev = events[idx];
        return Container(
          margin: const EdgeInsets.only(bottom: 8),
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: VoxColors.card,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: VoxColors.border),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    decoration: BoxDecoration(
                      color: VoxColors.primary.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: Text(
                      ev.tag,
                      style: const TextStyle(color: VoxColors.primary, fontSize: 11, fontWeight: FontWeight.w700),
                    ),
                  ),
                  Text(
                    '+${idx * 150}ms',
                    style: const TextStyle(color: VoxColors.textMuted, fontSize: 11),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Text(
                ev.message,
                style: const TextStyle(
                  color: VoxColors.textPrimary,
                  fontSize: 12,
                  fontFamily: 'monospace',
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildNotesTab() {
    final notes = _currentReport.developerNotes;

    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text(
                'Заметки разработчиков',
                style: TextStyle(color: VoxColors.textPrimary, fontSize: 15, fontWeight: FontWeight.w700),
              ),
              ElevatedButton.icon(
                style: ElevatedButton.styleFrom(
                  backgroundColor: VoxColors.primary,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                ),
                icon: _isAddingNote
                    ? const SizedBox(width: 12, height: 12, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                    : const Icon(Icons.add, size: 16),
                label: const Text('Добавить', style: TextStyle(fontSize: 12)),
                onPressed: _isAddingNote ? null : _showAddNoteDialog,
              ),
            ],
          ),
          const SizedBox(height: 12),
          Expanded(
            child: notes.isEmpty
                ? const Center(
                    child: Text('Заметок пока нет', style: TextStyle(color: VoxColors.textMuted)),
                  )
                : Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: VoxColors.card,
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(color: VoxColors.border),
                    ),
                    child: SingleChildScrollView(
                      child: Text(
                        notes,
                        style: const TextStyle(color: VoxColors.textPrimary, fontSize: 13, height: 1.5),
                      ),
                    ),
                  ),
          ),
        ],
      ),
    );
  }

  Widget _buildFilesTab() {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _buildActionTile(
          icon: Icons.download_rounded,
          title: 'Скачать JSON отчёта',
          subtitle: 'Выгрузить очищенный JSON без персональных данных',
          onTap: () async {
            try {
              final jsonStr = await widget.repository.downloadReportJson(_currentReport.reportId);
              if (mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    content: Text('JSON отчёта успешно сформирован (${jsonStr.length} байт)'),
                    backgroundColor: VoxColors.emerald,
                  ),
                );
              }
            } catch (e) {
              if (mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(content: Text('Ошибка скачивания: $e'), backgroundColor: VoxColors.red),
                );
              }
            }
          },
        ),
        const SizedBox(height: 10),
        _buildActionTile(
          icon: Icons.share_rounded,
          title: 'Поделиться через Sharesheet',
          subtitle: 'Отправить JSON в Telegram, почту или сохранить в файлы',
          onTap: _shareReportJson,
        ),
        const SizedBox(height: 10),
        _buildActionTile(
          icon: Icons.copy_rounded,
          title: 'Скопировать JSON в буфер',
          subtitle: 'Поместить полный очищенный отчёт в буфер обмена',
          onTap: _copyJsonToClipboard,
        ),
      ],
    );
  }

  Widget _buildActionTile({
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    return Material(
      color: VoxColors.card,
      borderRadius: BorderRadius.circular(12),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: VoxColors.primary.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(icon, color: VoxColors.primary, size: 22),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: const TextStyle(color: VoxColors.textPrimary, fontSize: 14, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 3),
                    Text(subtitle, style: const TextStyle(color: VoxColors.textMuted, fontSize: 12)),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right_rounded, color: VoxColors.textMuted),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Text(
        title,
        style: const TextStyle(color: VoxColors.textPrimary, fontSize: 14, fontWeight: FontWeight.w700),
      ),
    );
  }

  Widget _buildInfoCard(List<Widget> rows) {
    return Container(
      decoration: BoxDecoration(
        color: VoxColors.card,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: VoxColors.border),
      ),
      child: Column(
        children: rows,
      ),
    );
  }

  Widget _buildInfoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 130,
            child: Text(label, style: const TextStyle(color: VoxColors.textMuted, fontSize: 12)),
          ),
          Expanded(
            child: Text(
              value,
              style: const TextStyle(color: VoxColors.textPrimary, fontSize: 12, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}
