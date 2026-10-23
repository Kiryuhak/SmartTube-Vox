import 'package:flutter/material.dart';

/// Статусы инцидентов SmartTube VOX Diagnostics.
/// Включает полную поддержку как нового жизненного цикла VOX 9.1,
/// так и исторических статусов базы данных.
enum IncidentStatus {
  // VOX 9.1 Lifecycle
  newIncident('NEW', 'Новый', Color(0xFF6B7280), Color(0x336B7280)),
  investigating('INVESTIGATING', 'В работе', Color(0xFFF59E0B), Color(0x33F59E0B)),
  rootCauseFound('ROOT_CAUSE_FOUND', 'Причина найдена', Color(0xFFD97706), Color(0x33D97706)),
  fixImplemented('FIX_IMPLEMENTED', 'Исправлено в коде', Color(0xFF3B82F6), Color(0x333B82F6)),
  testing('TESTING', 'На проверке', Color(0xFF60A5FA), Color(0x3360A5FA)),
  fixedAwaitingVerification('FIXED_AWAITING_VERIFICATION', 'Исправлено — ждёт проверки', Color(0xFF14B8A6), Color(0x3314B8A6)),
  verified('VERIFIED', 'Исправлено — подтверждено', Color(0xFF10B981), Color(0x3310B981)),
  regression('REGRESSION', 'Повторная ошибка', Color(0xFFEF4444), Color(0x33EF4444)),
  knownLimitation('KNOWN_LIMITATION', 'Известное ограничение', Color(0xFF9CA3AF), Color(0x339CA3AF)),
  ignored('IGNORED', 'Игнорируется', Color(0xFF4B5563), Color(0x334B5563)),

  // Legacy production values (D1 baseline)
  fixedPendingVerification('FIXED_PENDING_VERIFICATION', 'Исправлено — ждёт проверки', Color(0xFF14B8A6), Color(0x3314B8A6)),
  resolved('RESOLVED', 'Решено', Color(0xFF10B981), Color(0x3310B981)),
  ignoredTest('IGNORED_TEST', 'Тест / игнор', Color(0xFF4B5563), Color(0x334B5563)),
  closed('CLOSED', 'Закрыто', Color(0xFF6B7280), Color(0x336B7280)),
  triaged('TRIAGED', 'Рассмотрено', Color(0xFF8B5CF6), Color(0x338B5CF6)),
  wontFix('WONT_FIX', 'Не будет исправлено', Color(0xFF9CA3AF), Color(0x339CA3AF)),
  knownIssue('KNOWN_ISSUE', 'Известная проблема', Color(0xFF9CA3AF), Color(0x339CA3AF)),
  unknown('UNKNOWN', 'Неизвестно', Color(0xFF6B7280), Color(0x336B7280));

  final String code;
  final String label;
  final Color color;
  final Color backgroundColor;

  const IncidentStatus(this.code, this.label, this.color, this.backgroundColor);

  static IncidentStatus fromString(String? raw) {
    if (raw == null || raw.trim().isEmpty) return IncidentStatus.newIncident;
    final normalized = raw.trim().toUpperCase().replaceAll(' ', '_');
    for (final status in IncidentStatus.values) {
      if (status.code == normalized) return status;
    }
    return IncidentStatus.unknown;
  }

  /// Список статусов, доступных для ручного выбора в выпадающем списке изменения статуса.
  static List<IncidentStatus> get selectableStatuses => [
    IncidentStatus.investigating,
    IncidentStatus.rootCauseFound,
    IncidentStatus.fixImplemented,
    IncidentStatus.testing,
    IncidentStatus.fixedAwaitingVerification,
    IncidentStatus.verified,
    IncidentStatus.regression,
    IncidentStatus.knownLimitation,
    IncidentStatus.ignored,
    IncidentStatus.newIncident,
  ];

  /// Список статусов для быстрого фильтра на главном экране.
  static List<IncidentStatus> get primaryFilterStatuses => [
    IncidentStatus.newIncident,
    IncidentStatus.investigating,
    IncidentStatus.fixedAwaitingVerification,
    IncidentStatus.resolved,
    IncidentStatus.knownLimitation,
    IncidentStatus.regression,
  ];

  bool get isResolved => this == IncidentStatus.verified || this == IncidentStatus.resolved;
  bool get isAwaitingVerification => this == IncidentStatus.fixedAwaitingVerification || this == IncidentStatus.fixedPendingVerification;
  bool get isInProgress => this == IncidentStatus.investigating || this == IncidentStatus.rootCauseFound || this == IncidentStatus.fixImplemented || this == IncidentStatus.testing;
  bool get isKnownLimitation => this == IncidentStatus.knownLimitation || this == IncidentStatus.knownIssue;
}
