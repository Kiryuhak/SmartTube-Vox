import 'package:flutter_test/flutter_test.dart';
import 'package:vox_pocket/models/incident_status.dart';
import 'package:vox_pocket/models/report.dart';
import 'package:vox_pocket/models/issue_group.dart';
import 'package:vox_pocket/models/device_summary.dart';
import 'package:vox_pocket/models/dashboard_stats.dart';
import 'package:vox_pocket/services/api_client.dart';
import 'package:vox_pocket/services/mock_data_repository.dart';

void main() {
  group('IncidentStatus Unit Tests', () {
    test('Canonical VOX 9.1 status mapping and labels', () {
      expect(IncidentStatus.fromString('NEW'), IncidentStatus.newIncident);
      expect(IncidentStatus.newIncident.label, 'Новый');

      expect(IncidentStatus.fromString('INVESTIGATING'), IncidentStatus.investigating);
      expect(IncidentStatus.investigating.label, 'В работе');

      expect(IncidentStatus.fromString('ROOT_CAUSE_FOUND'), IncidentStatus.rootCauseFound);
      expect(IncidentStatus.rootCauseFound.label, 'Причина найдена');

      expect(IncidentStatus.fromString('FIX_IMPLEMENTED'), IncidentStatus.fixImplemented);
      expect(IncidentStatus.fixImplemented.label, 'Исправлено в коде');

      expect(IncidentStatus.fromString('TESTING'), IncidentStatus.testing);
      expect(IncidentStatus.testing.label, 'На проверке');

      expect(IncidentStatus.fromString('FIXED_AWAITING_VERIFICATION'), IncidentStatus.fixedAwaitingVerification);
      expect(IncidentStatus.fixedAwaitingVerification.label, 'Исправлено — ждёт проверки');

      expect(IncidentStatus.fromString('VERIFIED'), IncidentStatus.verified);
      expect(IncidentStatus.verified.label, 'Исправлено — подтверждено');

      expect(IncidentStatus.fromString('REGRESSION'), IncidentStatus.regression);
      expect(IncidentStatus.regression.label, 'Повторная ошибка');

      expect(IncidentStatus.fromString('KNOWN_LIMITATION'), IncidentStatus.knownLimitation);
      expect(IncidentStatus.knownLimitation.label, 'Известное ограничение');

      expect(IncidentStatus.fromString('IGNORED'), IncidentStatus.ignored);
      expect(IncidentStatus.ignored.label, 'Игнорируется');
    });

    test('Legacy statuses from production D1 baseline', () {
      expect(IncidentStatus.fromString('FIXED_PENDING_VERIFICATION'), IncidentStatus.fixedPendingVerification);
      expect(IncidentStatus.fromString('RESOLVED'), IncidentStatus.resolved);
      expect(IncidentStatus.fromString('IGNORED_TEST'), IncidentStatus.ignoredTest);
      expect(IncidentStatus.fromString('CLOSED'), IncidentStatus.closed);
      expect(IncidentStatus.fromString('TRIAGED'), IncidentStatus.triaged);
      expect(IncidentStatus.fromString('WONT_FIX'), IncidentStatus.wontFix);
      expect(IncidentStatus.fromString('KNOWN_ISSUE'), IncidentStatus.knownIssue);
    });

    test('Normalization and fallback for unknown or empty statuses', () {
      expect(IncidentStatus.fromString(null), IncidentStatus.newIncident);
      expect(IncidentStatus.fromString(''), IncidentStatus.newIncident);
      expect(IncidentStatus.fromString('  '), IncidentStatus.newIncident);
      expect(IncidentStatus.fromString('investigating'), IncidentStatus.investigating);
      expect(IncidentStatus.fromString('fixed awaiting verification'), IncidentStatus.fixedAwaitingVerification);
      expect(IncidentStatus.fromString('SOMETHING_RANDOM_123'), IncidentStatus.unknown);
    });

    test('Status category helper flags', () {
      expect(IncidentStatus.verified.isResolved, isTrue);
      expect(IncidentStatus.resolved.isResolved, isTrue);
      expect(IncidentStatus.newIncident.isResolved, isFalse);

      expect(IncidentStatus.fixedAwaitingVerification.isAwaitingVerification, isTrue);
      expect(IncidentStatus.fixedPendingVerification.isAwaitingVerification, isTrue);

      expect(IncidentStatus.investigating.isInProgress, isTrue);
      expect(IncidentStatus.rootCauseFound.isInProgress, isTrue);
      expect(IncidentStatus.fixImplemented.isInProgress, isTrue);
      expect(IncidentStatus.testing.isInProgress, isTrue);
      expect(IncidentStatus.verified.isInProgress, isFalse);

      expect(IncidentStatus.knownLimitation.isKnownLimitation, isTrue);
      expect(IncidentStatus.knownIssue.isKnownLimitation, isTrue);
    });
  });

  group('Data Models & JSON Serialization Tests', () {
    test('ReportItem fromJson parses full canonical report with safeRecentEvents', () {
      final json = {
        'report_id': 'VOX-D6BCA185',
        'created_at': 1729500000000,
        'platform': 'Android',
        'app_version': '24.11',
        'device': {
          'manufacturer': 'OnePlus',
          'model': 'OnePlus 15',
          'sdkInt': 35,
          'osVersion': '15',
          'family': 'OnePlus',
          'tier': 'FLAGSHIP',
        },
        'error_category': 'PLAYER',
        'error_signature': 'PLAYER_RECOVERY_DECODER_TIMEOUT',
        'status': 'FIXED_AWAITING_VERIFICATION',
        'developer_notes': '[VOX 9.1 Backfill] Group: PLAYER_RECOVERY_DECODER_TIMEOUT',
        'duplicate_count': 2,
        'canonical_group': 'PLAYER_DECODER_TIMEOUT',
        'root_cause': 'Hardware decoder stall on initial codec configuration',
        'fix_commit': '5066fabe4bb05591609c5de650865a90e0f852af',
        'evidence': 'Recovery watchdog resets codec in 250ms',
        'safeRecentEvents': [
          {'timestamp': 1729500000000, 'tag': 'PLAYER', 'message': 'Codec timeout detected', 'level': 'WARN'},
          {'timestamp': 1729500000250, 'tag': 'RECOVERY', 'message': 'Soft watchdog fallback', 'level': 'INFO'},
        ],
      };

      final report = ReportItem.fromJson(json);
      expect(report.reportId, 'VOX-D6BCA185');
      expect(report.createdAt, 1729500000000);
      expect(report.platform, 'Android');
      expect(report.appVersion, '24.11');
      expect(report.device.displayName, 'OnePlus 15');
      expect(report.device.sdkInt, 35);
      expect(report.device.apiLabel, 'Android 15 (API 35)');
      expect(report.errorCategory, 'PLAYER');
      expect(report.errorSignature, 'PLAYER_RECOVERY_DECODER_TIMEOUT');
      expect(report.status, IncidentStatus.fixedAwaitingVerification);
      expect(report.duplicateCount, 2);
      expect(report.safeRecentEvents.length, 2);
      expect(report.safeRecentEvents[0].tag, 'PLAYER');
      expect(report.safeRecentEvents[1].level, 'INFO');

      final serialized = report.toJson();
      expect(serialized['reportId'], 'VOX-D6BCA185');
      expect(serialized['status'], 'FIXED_AWAITING_VERIFICATION');
    });

    test('ReportItem handles sparse and legacy JSON with missing fields safely', () {
      final sparseJson = {
        'reportId': 'VOX-SPARSE-01',
      };

      final report = ReportItem.fromJson(sparseJson);
      expect(report.reportId, 'VOX-SPARSE-01');
      expect(report.createdAt, 0);
      expect(report.platform, 'Android');
      expect(report.appVersion, 'unknown');
      expect(report.status, IncidentStatus.newIncident);
      expect(report.device.displayName, 'Неизвестное устройство');
      expect(report.safeRecentEvents, isEmpty);
    });

    test('IssueGroup fromJson and toJson', () {
      final json = {
        'signature': 'PLAYER_RECOVERY_DECODER_TIMEOUT',
        'category': 'PLAYER',
        'count': 17,
        'affectedDevices': 5,
        'first_seen': 1729000000000,
        'last_seen': 1729500000000,
        'status': 'FIXED_AWAITING_VERIFICATION',
        'fix_commit': '5066fabe',
        'is_regression': false,
        'title': 'Таймаут декодера',
      };

      final issue = IssueGroup.fromJson(json);
      expect(issue.signature, 'PLAYER_RECOVERY_DECODER_TIMEOUT');
      expect(issue.occurrenceCount, 17);
      expect(issue.affectedDevices, 5);
      expect(issue.status, IncidentStatus.fixedAwaitingVerification);
      expect(issue.fixCommit, '5066fabe');
      expect(issue.title, 'Таймаут декодера');
    });

    test('DeviceSummary fromJson calculates display name and handles categories', () {
      final json = {
        'model': 'OnePlus 15',
        'manufacturer': 'OnePlus',
        'platform': 'Android',
        'sdkInt': 35,
        'reportCount': 5,
        'lastReportTime': 1729500000000,
        'topCategories': {'PLAYER': 4, 'AUDIO': 1},
      };

      final dev = DeviceSummary.fromJson(json);
      expect(dev.displayName, 'OnePlus 15');
      expect(dev.sdkInt, 35);
      expect(dev.reportCount, 5);
      expect(dev.topCategories['PLAYER'], 4);
    });

    test('DashboardStats correctly calculates aggregated status counts', () {
      final stats = DashboardStats(
        totalReports: 58,
        countByStatus: {
          'NEW': 0,
          'INVESTIGATING': 3,
          'FIXED_AWAITING_VERIFICATION': 23,
          'RESOLVED': 8,
          'KNOWN_LIMITATION': 6,
          'REGRESSION': 0,
        },
        last24hCount: 4,
        last7dCount: 18,
        last30dCount: 58,
        categoryDistribution: {'PLAYER': 30, 'AUDIO': 12, 'NETWORK': 10, 'OTA': 6},
        incidentTrend: [const DailyIncidentPoint('21 окт', 11)],
        openIssuesCount: 5,
      );

      expect(stats.totalReports, 58);
      expect(stats.newCount, 0);
      expect(stats.investigatingCount, 3);
      expect(stats.awaitingVerificationCount, 23);
      expect(stats.resolvedCount, 8);
      expect(stats.knownLimitationsCount, 6);
      expect(stats.regressionCount, 0);
    });
  });

  group('MockDataRepository & Production Baseline Verification', () {
    late MockDataRepository repo;

    setUp(() {
      repo = MockDataRepository();
      repo.ensureInitialized();
    });

    test('Mock repository contains exact 58 production baseline reports', () {
      final reports = repo.getReports();
      expect(reports.length, 58);

      final stats = repo.getStats();
      expect(stats.totalReports, 58);

      // Verify exact status distribution from Production D1
      expect(stats.newCount, 0, reason: 'Zero NEW incidents baseline');
      expect(stats.investigatingCount, 3);
      expect(stats.awaitingVerificationCount, 23 + 8); // 23 awaiting + 8 pending legacy
      expect(stats.knownLimitationsCount, 6);
      expect(stats.resolvedCount, 8);
    });

    test('Final Six incidents verified in repository with accurate statuses', () {
      // Four in FIXED_AWAITING_VERIFICATION
      final r1 = repo.getReportById('VOX-D6BCA185');
      expect(r1, isNotNull);
      expect(r1!.status, IncidentStatus.fixedAwaitingVerification);
      expect(r1.device.displayName, 'OnePlus 15');

      final r2 = repo.getReportById('VOX-13A27B6D');
      expect(r2, isNotNull);
      expect(r2!.status, IncidentStatus.fixedAwaitingVerification);

      final r3 = repo.getReportById('VOX-FB2D5784');
      expect(r3, isNotNull);
      expect(r3!.status, IncidentStatus.fixedAwaitingVerification);

      final r4 = repo.getReportById('VOX-2C429C9F');
      expect(r4, isNotNull);
      expect(r4!.status, IncidentStatus.fixedAwaitingVerification);

      // Two in INVESTIGATING
      final r5 = repo.getReportById('VOX-ECD790E6');
      expect(r5, isNotNull);
      expect(r5!.status, IncidentStatus.investigating);

      final r6 = repo.getReportById('VOX-D0DF1A08');
      expect(r6, isNotNull);
      expect(r6!.status, IncidentStatus.investigating);
      expect(r6.device.displayName, 'SEI Robotics Dune HD Homatics Box R 4K Plus');
    });

    test('Local status modification and note addition in mock mode', () {
      final updated = repo.updateStatus('VOX-D0DF1A08', 'TESTING', reason: 'Verified rebuffer patch');
      expect(updated.status, IncidentStatus.testing);
      expect(updated.developerNotes, contains('[Статус изменён на На проверке]: Verified rebuffer patch'));

      final noted = repo.addNote('VOX-D0DF1A08', 'Тестовая заметка инженера');
      expect(noted.developerNotes, contains('Тестовая заметка инженера'));
    });

    test('Filtering and searching reports in mock repository', () {
      final playerReports = repo.getReports(category: 'PLAYER');
      expect(playerReports.every((r) => r.errorCategory == 'PLAYER'), isTrue);

      final onePlusReports = repo.getReports(search: 'OnePlus');
      expect(onePlusReports.isNotEmpty, isTrue);
      expect(onePlusReports.first.device.displayName, contains('OnePlus'));
    });
  });

  group('ApiClient Exception Hierarchy Tests', () {
    test('AuthRequiredException produces 401 code', () {
      final ex = AuthRequiredException();
      expect(ex.statusCode, 401);
      expect(ex.code, 'unauthorized');
    });

    test('NetworkException produces 0 status code', () {
      final ex = NetworkException();
      expect(ex.statusCode, 0);
      expect(ex.code, 'network_error');
    });

    test('NotFoundException produces 404 code', () {
      final ex = NotFoundException('Not found');
      expect(ex.statusCode, 404);
      expect(ex.code, 'not_found');
    });

    test('ConflictException produces 409 code', () {
      final ex = ConflictException('Conflict detected');
      expect(ex.statusCode, 409);
      expect(ex.code, 'conflict');
    });
  });
}
