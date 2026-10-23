import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/dashboard_stats.dart';
import '../models/report.dart';

class StorageService {
  static const _keyAdminSecret = 'vox_pocket_admin_secret';
  static const _keyDemoMode = 'vox_pocket_demo_mode';
  static const _keyCachedStats = 'vox_pocket_cached_stats';
  static const _keyCachedReports = 'vox_pocket_cached_reports';
  static const _keyLastSync = 'vox_pocket_last_sync';

  final SharedPreferences _prefs;

  StorageService(this._prefs);

  static Future<StorageService> init() async {
    final prefs = await SharedPreferences.getInstance();
    return StorageService(prefs);
  }

  String? getAdminSecret() {
    return _prefs.getString(_keyAdminSecret);
  }

  Future<bool> setAdminSecret(String? secret) async {
    if (secret == null || secret.trim().isEmpty) {
      return _prefs.remove(_keyAdminSecret);
    }
    return _prefs.setString(_keyAdminSecret, secret.trim());
  }

  bool get isDemoMode {
    // По умолчанию, если ключ не задан, запускается безопасный демонстрационный режим
    if (getAdminSecret() == null) return true;
    return _prefs.getBool(_keyDemoMode) ?? false;
  }

  Future<bool> setDemoMode(bool value) async {
    return _prefs.setBool(_keyDemoMode, value);
  }

  int get lastSyncTime {
    return _prefs.getInt(_keyLastSync) ?? 0;
  }

  Future<void> saveLastSyncTime(int timestamp) async {
    await _prefs.setInt(_keyLastSync, timestamp);
  }

  DashboardStats? getCachedStats() {
    final raw = _prefs.getString(_keyCachedStats);
    if (raw == null) return null;
    try {
      final map = jsonDecode(raw) as Map<String, dynamic>;
      return DashboardStats.fromJson(map);
    } catch (_) {
      return null;
    }
  }

  Future<void> cacheStats(DashboardStats stats) async {
    final raw = jsonEncode({
      'totalReports': stats.totalReports,
      'countByStatus': stats.countByStatus,
      'last24hCount': stats.last24hCount,
      'last7dCount': stats.last7dCount,
      'last30dCount': stats.last30dCount,
      'categoryDistribution': stats.categoryDistribution,
      'incidentTrend': stats.incidentTrend.map((e) => e.toJson()).toList(),
      'openIssuesCount': stats.openIssuesCount,
      'workerHealth': stats.workerHealth,
      'lastSyncTime': stats.lastSyncTime,
    });
    await _prefs.setString(_keyCachedStats, raw);
    await saveLastSyncTime(stats.lastSyncTime);
  }

  List<ReportItem>? getCachedReports() {
    final raw = _prefs.getString(_keyCachedReports);
    if (raw == null) return null;
    try {
      final list = jsonDecode(raw) as List;
      return list.map((e) => ReportItem.fromJson(e as Map<String, dynamic>)).toList();
    } catch (_) {
      return null;
    }
  }

  Future<void> cacheReports(List<ReportItem> reports) async {
    final raw = jsonEncode(reports.map((e) => e.toJson()).toList());
    await _prefs.setString(_keyCachedReports, raw);
  }
}
