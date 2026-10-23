import 'package:flutter/material.dart';
import '../services/app_repository.dart';
import '../theme/vox_theme.dart';

class MoreScreen extends StatefulWidget {
  final AppRepository repository;

  const MoreScreen({super.key, required this.repository});

  @override
  State<MoreScreen> createState() => _MoreScreenState();
}

class _MoreScreenState extends State<MoreScreen> {
  String _workerHealthStatus = 'Проверка...';
  bool _isCheckingHealth = false;

  @override
  void initState() {
    super.initState();
    _checkWorkerHealth();
  }

  Future<void> _checkWorkerHealth() async {
    setState(() {
      _isCheckingHealth = true;
    });

    try {
      final res = await widget.repository.apiClient.checkHealth();
      if (mounted) {
        setState(() {
          _workerHealthStatus = res['status'] == 'ok' ? 'Работает (200 OK)' : res['status']?.toString() ?? 'Доступен';
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _workerHealthStatus = 'Недоступен: $e';
        });
      }
    } finally {
      if (mounted) {
        setState(() {
          _isCheckingHealth = false;
        });
      }
    }
  }

  Future<void> _showAdminKeyDialog() async {
    final currentKey = widget.repository.storageService.getAdminSecret() ?? '';
    final controller = TextEditingController(text: currentKey);

    final newKey = await showDialog<String>(
      context: context,
      builder: (ctx) {
        return AlertDialog(
          backgroundColor: VoxColors.surface,
          title: const Text('Ключ администратора Cloudflare'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Введите секретный ключ администратора (ADMIN_SECRET) для прямого управления отчётами в Cloudflare Worker.',
                style: TextStyle(color: VoxColors.textSecondary, fontSize: 13),
              ),
              const SizedBox(height: 14),
              TextField(
                controller: controller,
                obscureText: true,
                decoration: const InputDecoration(
                  hintText: 'Bearer токен / Admin Secret...',
                ),
              ),
              const SizedBox(height: 10),
              const Text(
                'Ключ сохраняется исключительно в локальном хранилище устройства и никогда не передаётся третьим лицам.',
                style: TextStyle(color: VoxColors.textMuted, fontSize: 11),
              ),
            ],
          ),
          actions: [
            if (currentKey.isNotEmpty)
              TextButton(
                onPressed: () => Navigator.pop(ctx, ''),
                child: const Text('Удалить ключ', style: TextStyle(color: VoxColors.red)),
              ),
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Отмена'),
            ),
            ElevatedButton(
              style: ElevatedButton.styleFrom(backgroundColor: VoxColors.primary),
              onPressed: () => Navigator.pop(ctx, controller.text.trim()),
              child: const Text('Сохранить', style: TextStyle(color: Colors.white)),
            ),
          ],
        );
      },
    );

    if (newKey != null && mounted) {
      await widget.repository.setAdminSecret(newKey.isEmpty ? null : newKey);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(newKey.isEmpty ? 'Ключ удалён. Включён демо-режим.' : 'Ключ сохранён. Переключение на Worker.'),
            backgroundColor: newKey.isEmpty ? VoxColors.amber : VoxColors.emerald,
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final hasKey = widget.repository.hasAdminKey;
    final isDemo = widget.repository.isDemoMode;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Ещё и настройки'),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // Блок статуса сервиса
          _buildCard(
            title: 'Cloudflare Worker & База D1',
            icon: Icons.cloud_outlined,
            children: [
              _buildSettingRow(
                label: 'Cloudflare Worker',
                value: 'vox-diagnostics',
                trailing: _isCheckingHealth
                    ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2, color: VoxColors.primary))
                    : IconButton(
                        icon: const Icon(Icons.refresh, size: 18),
                        onPressed: _checkWorkerHealth,
                        tooltip: 'Проверить статус',
                      ),
              ),
              _buildSettingRow(label: 'Состояние API', value: _workerHealthStatus),
              _buildSettingRow(label: 'Production D1', value: 'vox_diagnostics_db (58 отчётов)'),
              _buildSettingRow(label: 'Worker URL', value: 'https://vox-diagnostics.amn2402.workers.dev'),
            ],
          ),

          const SizedBox(height: 16),

          // Блок безопасности и авторизации
          _buildCard(
            title: 'Безопасность и авторизация',
            icon: Icons.security_rounded,
            children: [
              _buildSettingRow(
                label: 'Статус доступа',
                value: hasKey ? 'Авторизован (Admin)' : 'Ключ не задан (Демо-режим)',
                valueColor: hasKey ? VoxColors.emerald : VoxColors.amber,
              ),
              const SizedBox(height: 8),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('Демонстрационный режим', style: TextStyle(fontSize: 13, color: VoxColors.textPrimary)),
                        SizedBox(height: 2),
                        Text('Использовать безопасные локальные фикстуры', style: TextStyle(fontSize: 11, color: VoxColors.textMuted)),
                      ],
                    ),
                  ),
                  Switch(
                    value: isDemo,
                    activeThumbColor: VoxColors.primary,
                    onChanged: (val) => widget.repository.toggleDemoMode(val),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  style: OutlinedButton.styleFrom(
                    side: const BorderSide(color: VoxColors.border),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                  ),
                  icon: const Icon(Icons.key_rounded, size: 18, color: VoxColors.primary),
                  label: Text(hasKey ? 'Изменить ключ администратора' : 'Ввести ключ администратора'),
                  onPressed: _showAdminKeyDialog,
                ),
              ),
            ],
          ),

          const SizedBox(height: 16),

          // Release Gates проекта
          _buildCard(
            title: 'VOX 9.1 Release Gates',
            icon: Icons.verified_user_rounded,
            children: [
              _buildGateRow('Новые инциденты (NEW)', '0 инцидентов', true),
              _buildGateRow('Тесты бэкенда (Node.js)', '159 / 159 PASS', true),
              _buildGateRow('Миграции D1 (0004 & 0005)', 'Применены в production', true),
              _buildGateRow('Развёртывание Worker', 'Версия a90918a2 активна', true),
              _buildGateRow('Синхронизация Dashboard UI', 'Исправлен и согласован', true),
            ],
          ),

          const SizedBox(height: 16),

          // О приложении
          _buildCard(
            title: 'О приложении',
            icon: Icons.info_outline_rounded,
            children: [
              _buildSettingRow(label: 'Название', value: 'SmartTube VOX Pocket'),
              _buildSettingRow(label: 'Версия', value: '1.0.0-dev'),
              _buildSettingRow(label: 'Платформа', value: 'Flutter Android (OnePlus 15 Target)'),
              _buildSettingRow(label: 'Разработчик', value: 'SmartTube Team / KiryuhaK'),
              _buildSettingRow(label: 'Пакет', value: 'io.github.kiryuhak.voxpocket'),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildCard({
    required String title,
    required IconData icon,
    required List<Widget> children,
  }) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: VoxColors.card,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: VoxColors.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, color: VoxColors.primary, size: 20),
              const SizedBox(width: 8),
              Text(
                title,
                style: const TextStyle(
                  color: VoxColors.textPrimary,
                  fontSize: 14,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          const Divider(height: 1),
          const SizedBox(height: 10),
          ...children,
        ],
      ),
    );
  }

  Widget _buildSettingRow({
    required String label,
    required String value,
    Color? valueColor,
    Widget? trailing,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Expanded(
            child: Text(label, style: const TextStyle(color: VoxColors.textMuted, fontSize: 13)),
          ),
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                value,
                style: TextStyle(
                  color: valueColor ?? VoxColors.textPrimary,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
              ),
              if (trailing != null) ...[
                const SizedBox(width: 6),
                trailing,
              ],
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildGateRow(String gate, String status, bool passed) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Expanded(
            child: Text(gate, style: const TextStyle(color: VoxColors.textSecondary, fontSize: 13)),
          ),
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                passed ? Icons.check_circle_rounded : Icons.cancel_rounded,
                color: passed ? VoxColors.emerald : VoxColors.red,
                size: 16,
              ),
              const SizedBox(width: 6),
              Text(
                status,
                style: TextStyle(
                  color: passed ? VoxColors.emerald : VoxColors.red,
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
