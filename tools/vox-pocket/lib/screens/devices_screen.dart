import 'package:flutter/material.dart';
import '../models/device_summary.dart';
import '../services/app_repository.dart';
import '../theme/vox_theme.dart';

class DevicesScreen extends StatelessWidget {
  final AppRepository repository;
  final Function(String searchDevice) onSelectDevice;

  const DevicesScreen({
    super.key,
    required this.repository,
    required this.onSelectDevice,
  });

  String _formatTimestamp(int ms) {
    if (ms <= 0) return 'не зафиксировано';
    final dt = DateTime.fromMillisecondsSinceEpoch(ms);
    final now = DateTime.now();
    final diff = now.difference(dt);

    if (diff.inHours < 24) {
      return '${diff.inHours} ч назад';
    } else if (diff.inDays < 7) {
      return '${diff.inDays} дн назад';
    }
    return '${dt.day.toString().padLeft(2, '0')}.${dt.month.toString().padLeft(2, '0')}.${dt.year}';
  }

  @override
  Widget build(BuildContext context) {
    final devices = repository.devices;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Устройства'),
      ),
      body: devices.isEmpty
          ? const Center(
              child: Text('Устройства не зафиксированы', style: TextStyle(color: VoxColors.textMuted)),
            )
          : RefreshIndicator(
              onRefresh: () => repository.refreshAll(),
              color: VoxColors.primary,
              child: ListView.builder(
                padding: const EdgeInsets.symmetric(vertical: 8),
                itemCount: devices.length,
                itemBuilder: (ctx, idx) {
                  final dev = devices[idx];
                  return _buildDeviceCard(dev);
                },
              ),
            ),
    );
  }

  Widget _buildDeviceCard(DeviceSummary dev) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () => onSelectDevice(dev.displayName),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Expanded(
                    child: Row(
                      children: [
                        const Icon(Icons.tv_rounded, color: VoxColors.primary, size: 20),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            dev.displayName,
                            style: const TextStyle(
                              color: VoxColors.textPrimary,
                              fontSize: 14,
                              fontWeight: FontWeight.w700,
                            ),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                    ),
                  ),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                    decoration: BoxDecoration(
                      color: VoxColors.primary.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Text(
                      '${dev.reportCount} отчётов',
                      style: const TextStyle(
                        color: VoxColors.primary,
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  if (dev.sdkInt != null) ...[
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: VoxColors.surface,
                        borderRadius: BorderRadius.circular(4),
                        border: Border.all(color: VoxColors.border),
                      ),
                      child: Text(
                        'API ${dev.sdkInt}',
                        style: const TextStyle(color: VoxColors.textSecondary, fontSize: 11),
                      ),
                    ),
                    const SizedBox(width: 8),
                  ],
                  Text(
                    'Последний отчёт: ${_formatTimestamp(dev.lastReportTime)}',
                    style: const TextStyle(color: VoxColors.textMuted, fontSize: 11),
                  ),
                ],
              ),
              if (dev.topCategories.isNotEmpty) ...[
                const SizedBox(height: 10),
                Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  children: dev.topCategories.entries.map((e) {
                    return Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: VoxColors.cardHover,
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: Text(
                        '${e.key}: ${e.value}',
                        style: const TextStyle(color: VoxColors.cyan, fontSize: 10, fontWeight: FontWeight.w600),
                      ),
                    );
                  }).toList(),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
