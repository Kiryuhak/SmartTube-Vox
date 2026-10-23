import 'package:flutter/material.dart';
import '../theme/vox_theme.dart';

class MetricCard extends StatelessWidget {
  final String title;
  final int count;
  final Color accentColor;
  final IconData icon;
  final VoidCallback? onTap;

  const MetricCard({
    super.key,
    required this.title,
    required this.count,
    required this.accentColor,
    required this.icon,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          decoration: BoxDecoration(
            color: VoxColors.card,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: count > 0 ? accentColor.withValues(alpha: 0.35) : VoxColors.border,
              width: 1,
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Icon(icon, color: accentColor, size: 18),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    decoration: BoxDecoration(
                      color: accentColor.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(
                      count.toString(),
                      style: TextStyle(
                        color: accentColor,
                        fontSize: 14,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                title,
                style: const TextStyle(
                  color: VoxColors.textSecondary,
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
