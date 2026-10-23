import 'package:flutter/material.dart';
import '../models/dashboard_stats.dart';
import '../theme/vox_theme.dart';

class IncidentTrendChart extends StatelessWidget {
  final List<DailyIncidentPoint> points;

  const IncidentTrendChart({super.key, required this.points});

  @override
  Widget build(BuildContext context) {
    if (points.isEmpty) {
      return const SizedBox(
        height: 140,
        child: Center(
          child: Text('Нет данных динамики', style: TextStyle(color: VoxColors.textMuted, fontSize: 13)),
        ),
      );
    }

    final maxVal = points.map((e) => e.count).reduce((a, b) => a > b ? a : b);
    final effectiveMax = maxVal > 0 ? maxVal : 1;

    return Container(
      height: 150,
      padding: const EdgeInsets.fromLTRB(12, 16, 12, 8),
      decoration: BoxDecoration(
        color: VoxColors.card,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: VoxColors.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Expanded(
                child: Text(
                  'Динамика инцидентов',
                  style: TextStyle(
                    color: VoxColors.textPrimary,
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              const SizedBox(width: 8),
              Text(
                'Макс: $maxVal за день',
                style: const TextStyle(color: VoxColors.textMuted, fontSize: 11),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Expanded(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: points.map((p) {
                final ratio = p.count / effectiveMax;
                return Expanded(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        Text(
                          p.count.toString(),
                          style: const TextStyle(
                            color: VoxColors.textSecondary,
                            fontSize: 11,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Expanded(
                          child: Align(
                            alignment: Alignment.bottomCenter,
                            child: FractionallySizedBox(
                              heightFactor: ratio.clamp(0.08, 1.0),
                              child: Container(
                                decoration: BoxDecoration(
                                  color: VoxColors.primary.withValues(alpha: 0.8),
                                  borderRadius: const BorderRadius.vertical(top: Radius.circular(4)),
                                ),
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          p.date,
                          style: const TextStyle(
                            color: VoxColors.textMuted,
                            fontSize: 10,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ),
                  ),
                );
              }).toList(),
            ),
          ),
        ],
      ),
    );
  }
}
