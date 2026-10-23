import 'package:flutter/foundation.dart';
import '../models/dashboard_stats.dart';
import '../models/device_summary.dart';
import '../models/incident_status.dart';
import '../models/issue_group.dart';
import '../models/report.dart';
import 'api_client.dart';
import 'mock_data_repository.dart';
import 'storage_service.dart';

enum DataSourceType {
  live,   // Подключено к Cloudflare Worker (Production D1)
  cached, // Локальный кеш предыдущей успешной синхронизации (Офлайн)
  demo,   // Локальные демонстрационные данные (58 базовых записей)
}

enum ErrorCategory {
  none,
  auth,     // 401/403: Ошибка авторизации (неверный или отсутствующий токен)
  network,  // DNS/Timeout/Connection: Ошибка сети
  server,   // 500/502: Ошибка на стороне сервера
}

class BatchTransitionResult {
  final bool isLive;
  final int totalCount;
  final int successCount;
  final int failedCount;
  final List<String> updatedIds;
  final String? message;

  BatchTransitionResult({
    required this.isLive,
    required this.totalCount,
    required this.successCount,
    required this.failedCount,
    required this.updatedIds,
    this.message,
  });
}

class AppRepository extends ChangeNotifier {
  final StorageService storageService;
  final VoxDiagnosticsApiClient apiClient;
  final MockDataRepository mockRepository;

  bool _isDemoMode = true;
  DataSourceType _dataSourceType = DataSourceType.demo;
  bool _isSyncing = false;
  String? _lastError;
  ErrorCategory _lastErrorCategory = ErrorCategory.none;
  DashboardStats? _stats;
  List<ReportItem> _reports = [];
  List<IssueGroup> _issues = [];
  List<DeviceSummary> _devices = [];

  static const List<String> approvedInvestigatingReportIds = [
    'VOX-FC763605',
    'VOX-319BD42A',
    'VOX-BAEAAEB6',
    'VOX-EF0F761F',
    'VOX-0BF9F24D',
    'VOX-C04586D3',
    'VOX-D5F0C645',
    'VOX-C6A533DD',
    'VOX-6B514E8A',
    'VOX-2BC4753A',
    'VOX-EC363B97',
    'VOX-6D00AEA9',
    'VOX-3345BBFD',
    'VOX-BD27BAD9',
    'VOX-7AE27244',
    'VOX-9121247C',
    'VOX-28070F7A',
  ];

  AppRepository({
    required this.storageService,
    required this.apiClient,
    required this.mockRepository,
  }) {
    _isDemoMode = storageService.isDemoMode;
    final secret = storageService.getAdminSecret();
    apiClient.adminSecret = secret;
    _dataSourceType = (_isDemoMode || secret == null || secret.trim().isEmpty)
        ? DataSourceType.demo
        : DataSourceType.live;
    mockRepository.ensureInitialized();
  }

  bool get isDemoMode => _isDemoMode;
  DataSourceType get dataSourceType => _dataSourceType;
  bool get isSyncing => _isSyncing;
  String? get lastError => _lastError;
  ErrorCategory get lastErrorCategory => _lastErrorCategory;
  DashboardStats? get stats => _stats;
  List<ReportItem> get reports => _reports;
  List<IssueGroup> get issues => _issues;
  List<DeviceSummary> get devices => _devices;
  bool get hasAdminKey => storageService.getAdminSecret() != null && storageService.getAdminSecret()!.trim().isNotEmpty;

  int get realNewCount => _reports.where((r) => r.status.code == 'NEW').length;
  int get realInvestigatingCount => _reports.where((r) =>
      r.status.code == 'INVESTIGATING' ||
      r.status.code == 'ROOT_CAUSE_FOUND' ||
      r.status.code == 'FIX_IMPLEMENTED' ||
      r.status.code == 'TESTING').length;

  Future<void> setAdminSecret(String? secret) async {
    await storageService.setAdminSecret(secret);
    apiClient.adminSecret = secret;
    if (secret != null && secret.trim().isNotEmpty) {
      _isDemoMode = false;
      _dataSourceType = DataSourceType.live;
      await storageService.setDemoMode(false);
    } else {
      _isDemoMode = true;
      _dataSourceType = DataSourceType.demo;
      await storageService.setDemoMode(true);
    }
    notifyListeners();
    await refreshAll();
  }

  Future<void> toggleDemoMode(bool value) async {
    _isDemoMode = value;
    _dataSourceType = value ? DataSourceType.demo : (hasAdminKey ? DataSourceType.live : DataSourceType.demo);
    await storageService.setDemoMode(value);
    notifyListeners();
    await refreshAll();
  }

  Future<void> refreshAll() async {
    _isSyncing = true;
    _lastError = null;
    _lastErrorCategory = ErrorCategory.none;
    notifyListeners();

    try {
      if (_isDemoMode || !hasAdminKey) {
        // Демонстрационный / офлайн режим
        _dataSourceType = DataSourceType.demo;
        _stats = mockRepository.getStats();
        _reports = mockRepository.getReports();
        _issues = mockRepository.getIssues();
        _devices = mockRepository.getDeviceSummaries();
      } else {
        // Подключение к Cloudflare Worker (Production)
        try {
          final statsFuture = apiClient.getStats();
          final reportsFuture = apiClient.listReports(limit: 50);
          final issuesFuture = apiClient.getGroupedIssues(limit: 50);

          final results = await Future.wait([statsFuture, reportsFuture, issuesFuture]);
          _stats = results[0] as DashboardStats;
          _reports = results[1] as List<ReportItem>;
          _issues = results[2] as List<IssueGroup>;
          _dataSourceType = DataSourceType.live;

          // Кешируем полученные данные для офлайн-доступа
          await storageService.cacheStats(_stats!);
          await storageService.cacheReports(_reports);

          _generateDeviceSummaries();
        } on AuthRequiredException {
          _lastError = 'Ошибка авторизации (401/403): Неверный или отсутствующий ключ администратора';
          _lastErrorCategory = ErrorCategory.auth;
          _fallbackToCacheOrMock();
        } on NetworkException catch (ne) {
          _lastError = 'Сетевая ошибка: ${ne.message}';
          _lastErrorCategory = ErrorCategory.network;
          _fallbackToCacheOrMock();
        } catch (e) {
          _lastError = 'Ошибка сервиса: $e';
          _lastErrorCategory = ErrorCategory.server;
          _fallbackToCacheOrMock();
        }
      }
    } finally {
      _isSyncing = false;
      notifyListeners();
    }
  }

  void _fallbackToCacheOrMock() {
    final cachedStats = storageService.getCachedStats();
    final cachedReports = storageService.getCachedReports();
    if (cachedStats != null && cachedReports != null) {
      _stats = cachedStats;
      _reports = cachedReports;
      _dataSourceType = DataSourceType.cached;
      _generateDeviceSummaries();
    } else {
      _stats = mockRepository.getStats();
      _reports = mockRepository.getReports();
      _issues = mockRepository.getIssues();
      _devices = mockRepository.getDeviceSummaries();
      _dataSourceType = DataSourceType.demo;
    }
  }

  void _generateDeviceSummaries() {
    final map = <String, List<ReportItem>>{};
    for (final r in _reports) {
      final key = r.device.displayName;
      map.putIfAbsent(key, () => []).add(r);
    }

    final result = <DeviceSummary>[];
    map.forEach((key, list) {
      final topCats = <String, int>{};
      final versions = <String>{};
      int lastSeen = 0;

      for (final r in list) {
        topCats[r.errorCategory] = (topCats[r.errorCategory] ?? 0) + 1;
        versions.add(r.appVersion);
        if (r.createdAt > lastSeen) lastSeen = r.createdAt;
      }

      final first = list.first;
      result.add(DeviceSummary(
        model: first.device.model,
        manufacturer: first.device.manufacturer,
        platform: first.platform,
        sdkInt: first.device.sdkInt,
        reportCount: list.length,
        lastReportTime: lastSeen,
        topCategories: topCats,
        versions: versions.toList(),
      ));
    });

    result.sort((a, b) => b.reportCount.compareTo(a.reportCount));
    _devices = result;
  }

  Future<ReportItem> updateReportStatus(String reportId, IncidentStatus newStatus, {String? reason}) async {
    ReportItem updated;
    if (_isDemoMode || !hasAdminKey) {
      updated = mockRepository.updateStatus(reportId, newStatus.code, reason: reason);
      final idx = _reports.indexWhere((r) => r.reportId.toLowerCase() == reportId.toLowerCase());
      if (idx != -1) _reports[idx] = updated;
      _stats = mockRepository.getStats();
      notifyListeners();
    } else {
      updated = await apiClient.updateStatus(reportId, newStatus.code, reason: reason);
      final idx = _reports.indexWhere((r) => r.reportId.toLowerCase() == reportId.toLowerCase());
      if (idx != -1) _reports[idx] = updated;
      notifyListeners();
    }
    return updated;
  }

  Future<ReportItem> addReportNote(String reportId, String noteText) async {
    ReportItem updated;
    if (_isDemoMode || !hasAdminKey) {
      updated = mockRepository.addNote(reportId, noteText);
      final idx = _reports.indexWhere((r) => r.reportId.toLowerCase() == reportId.toLowerCase());
      if (idx != -1) _reports[idx] = updated;
      notifyListeners();
    } else {
      updated = await apiClient.addNote(reportId, noteText);
      final idx = _reports.indexWhere((r) => r.reportId.toLowerCase() == reportId.toLowerCase());
      if (idx != -1) _reports[idx] = updated;
      notifyListeners();
    }
    return updated;
  }

  Future<String> downloadReportJson(String reportId) async {
    if (_isDemoMode || !hasAdminKey) {
      final report = mockRepository.getReportById(reportId);
      if (report == null) throw Exception('Отчёт $reportId не найден');
      return report.toJson().toString();
    }
    return apiClient.downloadReportJson(reportId);
  }

  Future<BatchTransitionResult> batchTransitionInvestigatingReports({
    String reason = 'VOX 9.2: Перевод 12 утверждённых отчётов в INVESTIGATING после первичного triage',
  }) async {
    final isLive = (_dataSourceType == DataSourceType.live && hasAdminKey);
    final updatedIds = <String>[];
    int failedCount = 0;

    if (!isLive) {
      // Демонстрационный режим: переводим в локальной памяти mock-репозитория
      for (final id in approvedInvestigatingReportIds) {
        try {
          final updated = mockRepository.updateStatus(id, 'INVESTIGATING', reason: reason);
          final idx = _reports.indexWhere((r) => r.reportId.toLowerCase() == id.toLowerCase());
          if (idx != -1) _reports[idx] = updated;
          updatedIds.add(id);
        } catch (_) {
          failedCount++;
        }
      }
      _stats = mockRepository.getStats();
      notifyListeners();
      return BatchTransitionResult(
        isLive: false,
        totalCount: approvedInvestigatingReportIds.length,
        successCount: updatedIds.length,
        failedCount: failedCount,
        updatedIds: updatedIds,
        message: 'ДЕМО-РЕЖИМ: Статусы 12 отчётов обновлены только локально в памяти приложения. Production D1 не изменялся.',
      );
    } else {
      // LIVE-режим: авторизованные запросы к Cloudflare Worker
      String? firstError;
      for (final id in approvedInvestigatingReportIds) {
        try {
          final updated = await apiClient.updateStatus(id, 'INVESTIGATING', reason: reason);
          final idx = _reports.indexWhere((r) => r.reportId.toLowerCase() == id.toLowerCase());
          if (idx != -1) _reports[idx] = updated;
          updatedIds.add(id);
        } catch (e) {
          failedCount++;
          firstError ??= e.toString();
        }
      }
      await refreshAll();
      return BatchTransitionResult(
        isLive: true,
        totalCount: approvedInvestigatingReportIds.length,
        successCount: updatedIds.length,
        failedCount: failedCount,
        updatedIds: updatedIds,
        message: failedCount == 0
            ? 'Сервер подтвердил перевод ${updatedIds.length} отчётов в INVESTIGATING в production D1.'
            : 'Частичный перевод: ${updatedIds.length} успешно, $failedCount с ошибкой ($firstError).',
      );
    }
  }
}
