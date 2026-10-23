import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:http/http.dart' as http;
import '../models/report.dart';
import '../models/issue_group.dart';
import '../models/dashboard_stats.dart';

class ApiException implements Exception {
  final String message;
  final int? statusCode;
  final String? code;

  ApiException(this.message, {this.statusCode, this.code});

  @override
  String toString() => message;
}

class AuthRequiredException extends ApiException {
  AuthRequiredException([super.message = 'Требуется ключ администратора Cloudflare'])
      : super(statusCode: 401, code: 'unauthorized');
}

class NetworkException extends ApiException {
  NetworkException([super.message = 'Ошибка сетевого соединения. Проверьте интернет'])
      : super(statusCode: 0, code: 'network_error');
}

class NotFoundException extends ApiException {
  NotFoundException([super.message = 'Запрошенный объект не найден'])
      : super(statusCode: 404, code: 'not_found');
}

class ConflictException extends ApiException {
  ConflictException([super.message = 'Конфликт обновления данных на сервере'])
      : super(statusCode: 409, code: 'conflict');
}

class VoxDiagnosticsApiClient {
  final String baseUrl;
  final http.Client _client;
  String? adminSecret;

  VoxDiagnosticsApiClient({
    this.baseUrl = 'https://vox-diagnostics.amn2402.workers.dev',
    http.Client? client,
    this.adminSecret,
  }) : _client = client ?? http.Client();

  Map<String, String> _buildHeaders() {
    final headers = {
      'Accept': 'application/json',
      'Content-Type': 'application/json',
      'User-Agent': 'SmartTube-VOX-Pocket/1.0.0-dev (Android)',
    };
    if (adminSecret != null && adminSecret!.trim().isNotEmpty) {
      headers['Authorization'] = 'Bearer ${adminSecret!.trim()}';
    }
    return headers;
  }

  /// Проверяет публичный статус Worker без авторизации.
  Future<Map<String, dynamic>> checkHealth() async {
    try {
      final uri = Uri.parse('$baseUrl/health');
      final response = await _client.get(uri).timeout(const Duration(seconds: 8));
      if (response.statusCode == 200) {
        return jsonDecode(response.body) as Map<String, dynamic>;
      }
      throw ApiException('Сервис ответил кодом ${response.statusCode}', statusCode: response.statusCode);
    } on SocketException {
      throw NetworkException();
    } on TimeoutException {
      throw NetworkException('Таймаут подключения к сервису диагностики');
    } catch (e) {
      if (e is ApiException) rethrow;
      throw ApiException('Ошибка проверки сервиса: $e');
    }
  }

  /// Получает расширенную статистику панели администратора.
  Future<DashboardStats> getStats() async {
    try {
      final uri = Uri.parse('$baseUrl/v1/admin/stats');
      final response = await _client.get(uri, headers: _buildHeaders()).timeout(const Duration(seconds: 10));

      if (response.statusCode == 401 || response.statusCode == 403) {
        throw AuthRequiredException();
      }
      if (response.statusCode != 200) {
        throw ApiException('Не удалось получить статистику: ${response.statusCode}', statusCode: response.statusCode);
      }

      final data = jsonDecode(response.body) as Map<String, dynamic>;
      return DashboardStats.fromJson({
        ...data,
        'lastSyncTime': DateTime.now().millisecondsSinceEpoch,
      });
    } on SocketException {
      throw NetworkException();
    } on TimeoutException {
      throw NetworkException('Таймаут получения статистики');
    }
  }

  /// Список диагностических отчётов с поддержкой фильтров.
  Future<List<ReportItem>> listReports({
    String? status,
    String? category,
    String? platform,
    String? search,
    String? sort = 'newest',
    int limit = 50,
    int offset = 0,
    String? cursor,
  }) async {
    try {
      final params = <String, String>{
        'limit': limit.toString(),
        'offset': offset.toString(),
      };
      if (sort != null) params['sort'] = sort;
      if (status != null && status != 'ALL') params['status'] = status;
      if (category != null && category != 'ALL') params['errorCategory'] = category;
      if (platform != null && platform != 'ALL') params['platform'] = platform;
      if (search != null && search.isNotEmpty) params['search'] = search;
      if (cursor != null) params['cursor'] = cursor;

      final uri = Uri.parse('$baseUrl/v1/admin/reports').replace(queryParameters: params);
      final response = await _client.get(uri, headers: _buildHeaders()).timeout(const Duration(seconds: 12));

      if (response.statusCode == 401 || response.statusCode == 403) {
        throw AuthRequiredException();
      }
      if (response.statusCode != 200) {
        throw ApiException('Ошибка загрузки отчётов (${response.statusCode})', statusCode: response.statusCode);
      }

      final data = jsonDecode(response.body);
      final list = <ReportItem>[];

      if (data is Map && data['reports'] is List) {
        for (final item in data['reports']) {
          if (item is Map<String, dynamic>) {
            list.add(ReportItem.fromJson(item));
          }
        }
      } else if (data is List) {
        for (final item in data) {
          if (item is Map<String, dynamic>) {
            list.add(ReportItem.fromJson(item));
          }
        }
      }
      return list;
    } on SocketException {
      throw NetworkException();
    } on TimeoutException {
      throw NetworkException('Таймаут загрузки списка отчётов');
    }
  }

  /// Детальная информация по конкретному отчёту.
  Future<ReportItem> getReportById(String reportId) async {
    try {
      final uri = Uri.parse('$baseUrl/v1/admin/reports/${Uri.encodeComponent(reportId)}');
      final response = await _client.get(uri, headers: _buildHeaders()).timeout(const Duration(seconds: 10));

      if (response.statusCode == 401 || response.statusCode == 403) {
        throw AuthRequiredException();
      }
      if (response.statusCode == 404) {
        throw NotFoundException('Отчёт $reportId не найден');
      }
      if (response.statusCode != 200) {
        throw ApiException('Ошибка получения отчёта (${response.statusCode})', statusCode: response.statusCode);
      }

      final data = jsonDecode(response.body) as Map<String, dynamic>;
      return ReportItem.fromJson(data);
    } on SocketException {
      throw NetworkException();
    } on TimeoutException {
      throw NetworkException('Таймаут загрузки отчёта');
    }
  }

  /// Обновление статуса отчёта с обязательным подтверждением сервера.
  Future<ReportItem> updateStatus(String reportId, String newStatus, {String? reason}) async {
    try {
      final uri = Uri.parse('$baseUrl/v1/admin/reports/${Uri.encodeComponent(reportId)}/status');
      final body = jsonEncode({
        'status': newStatus,
        if (reason != null && reason.trim().isNotEmpty) 'reason': reason.trim(),
      });

      final response = await _client.patch(uri, headers: _buildHeaders(), body: body).timeout(const Duration(seconds: 10));

      if (response.statusCode == 401 || response.statusCode == 403) {
        throw AuthRequiredException();
      }
      if (response.statusCode == 404) {
        throw NotFoundException('Отчёт $reportId не найден на сервере');
      }
      if (response.statusCode == 409) {
        throw ConflictException('Статус был изменён другим процессом');
      }
      if (response.statusCode != 200) {
        throw ApiException('Не удалось обновить статус (${response.statusCode})', statusCode: response.statusCode);
      }

      final data = jsonDecode(response.body) as Map<String, dynamic>;
      final updatedReportJson = data['report'] is Map<String, dynamic> ? data['report'] as Map<String, dynamic> : data;
      return ReportItem.fromJson(updatedReportJson);
    } on SocketException {
      throw NetworkException();
    } on TimeoutException {
      throw NetworkException('Таймаут обновления статуса');
    }
  }

  /// Добавление заметки разработчика.
  Future<ReportItem> addNote(String reportId, String noteText) async {
    try {
      final uri = Uri.parse('$baseUrl/v1/admin/reports/${Uri.encodeComponent(reportId)}/notes');
      final body = jsonEncode({'note': noteText.trim()});

      final response = await _client.post(uri, headers: _buildHeaders(), body: body).timeout(const Duration(seconds: 10));

      if (response.statusCode == 401 || response.statusCode == 403) {
        throw AuthRequiredException();
      }
      if (response.statusCode == 404) {
        throw NotFoundException('Отчёт $reportId не найден');
      }
      if (response.statusCode != 200) {
        throw ApiException('Ошибка добавления заметки (${response.statusCode})', statusCode: response.statusCode);
      }

      final data = jsonDecode(response.body) as Map<String, dynamic>;
      final updatedReportJson = data['report'] is Map<String, dynamic> ? data['report'] as Map<String, dynamic> : data;
      return ReportItem.fromJson(updatedReportJson);
    } on SocketException {
      throw NetworkException();
    } on TimeoutException {
      throw NetworkException('Таймаут отправки заметки');
    }
  }

  /// Скачивание очищенного JSON отчёта.
  Future<String> downloadReportJson(String reportId) async {
    try {
      final uri = Uri.parse('$baseUrl/v1/admin/reports/${Uri.encodeComponent(reportId)}/download');
      final response = await _client.get(uri, headers: _buildHeaders()).timeout(const Duration(seconds: 15));

      if (response.statusCode == 401 || response.statusCode == 403) {
        throw AuthRequiredException();
      }
      if (response.statusCode == 404) {
        throw NotFoundException('Отчёт $reportId не найден для выгрузки');
      }
      if (response.statusCode != 200) {
        throw ApiException('Ошибка скачивания отчёта (${response.statusCode})', statusCode: response.statusCode);
      }

      return response.body;
    } on SocketException {
      throw NetworkException();
    } on TimeoutException {
      throw NetworkException('Таймаут скачивания JSON');
    }
  }

  /// Получение канонических групп инцидентов.
  Future<List<IssueGroup>> getGroupedIssues({int limit = 50, int offset = 0, String? status}) async {
    try {
      final params = <String, String>{
        'limit': limit.toString(),
        'offset': offset.toString(),
        if (status != null && status != 'ALL') 'status': status,
      };

      final uri = Uri.parse('$baseUrl/v1/admin/issues').replace(queryParameters: params);
      final response = await _client.get(uri, headers: _buildHeaders()).timeout(const Duration(seconds: 10));

      if (response.statusCode == 401 || response.statusCode == 403) {
        throw AuthRequiredException();
      }
      if (response.statusCode != 200) {
        throw ApiException('Ошибка получения списка проблем (${response.statusCode})', statusCode: response.statusCode);
      }

      final data = jsonDecode(response.body);
      final list = <IssueGroup>[];

      if (data is Map && data['issues'] is List) {
        for (final item in data['issues']) {
          if (item is Map<String, dynamic>) {
            list.add(IssueGroup.fromJson(item));
          }
        }
      }
      return list;
    } on SocketException {
      throw NetworkException();
    } on TimeoutException {
      throw NetworkException('Таймаут загрузки проблем');
    }
  }

  void dispose() {
    _client.close();
  }
}
