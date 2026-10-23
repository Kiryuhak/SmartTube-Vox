import '../models/incident_status.dart';
import '../models/report.dart';
import '../models/issue_group.dart';
import '../models/device_summary.dart';
import '../models/dashboard_stats.dart';

class MockDataRepository {
  final List<ReportItem> _reports = [];
  final List<IssueGroup> _issues = [];
  bool _initialized = false;

  void ensureInitialized() {
    if (_initialized) return;
    _initialized = true;
    _populateBaseline();
  }

  void _populateBaseline() {
    _reports.clear();
    _issues.clear();

    final now = DateTime.now().millisecondsSinceEpoch;
    final hour = 3600 * 1000;
    final day = 24 * hour;

    // 1. Создаём реальные ключевые отчёты из проверенной Cloudflare D1 vox_diagnostics_db
    // Final Six (Миграция 0005)
    _reports.add(ReportItem(
      reportId: 'VOX-D6BCA185',
      createdAt: now - 3 * hour,
      platform: 'Android',
      appVersion: '24.11',
      device: const DeviceInfo(manufacturer: 'OnePlus', model: 'OnePlus 15', sdkInt: 35, osVersion: '15', family: 'OnePlus'),
      errorCategory: 'PLAYER',
      errorSignature: 'PLAYER_RECOVERY_DECODER_TIMEOUT',
      status: IncidentStatus.fixedAwaitingVerification,
      developerNotes: '[VOX 9.1 Backfill] Group: PLAYER_RECOVERY_DECODER_TIMEOUT. Fix commit: 5066fabe',
      duplicateCount: 2,
      canonicalGroup: 'PLAYER_DECODER_TIMEOUT',
      rootCause: 'Hardware decoder stall on initial codec configuration',
      fixCommit: '5066fabe4bb05591609c5de650865a90e0f852af',
      evidence: 'Recovery watchdog resets codec in 250ms',
      safeRecentEvents: [
        DiagnosticEvent(timestamp: now - 3 * hour, tag: 'PLAYER', message: 'Codec timeout detected on MediaCodec.configure'),
        DiagnosticEvent(timestamp: now - 3 * hour + 250, tag: 'RECOVERY', message: 'Soft watchdog fallback initiated'),
      ],
    ));

    _reports.add(ReportItem(
      reportId: 'VOX-13A27B6D',
      createdAt: now - 5 * hour,
      platform: 'Android',
      appVersion: '24.11',
      device: const DeviceInfo(manufacturer: 'Xiaomi', model: 'Mi Box S 2nd Gen', sdkInt: 31, osVersion: '11', family: 'MiBox'),
      errorCategory: 'AUDIO',
      errorSignature: 'AUDIO_TRACK_INIT_FAILED_SURROUND',
      status: IncidentStatus.fixedAwaitingVerification,
      developerNotes: '[VOX 9.1 Backfill] Group: AUDIO_TRACK_INIT. Fix commit: 5066fabe',
      duplicateCount: 1,
      canonicalGroup: 'AUDIO_INIT_FAILURE',
      rootCause: 'Passthrough audio initialization failure on 5.1 surround sound',
      fixCommit: '5066fabe4bb05591609c5de650865a90e0f852af',
      safeRecentEvents: [
        DiagnosticEvent(timestamp: now - 5 * hour, tag: 'AUDIO', message: 'AudioTrack.init() returned ERROR_INVALID_OPERATION'),
      ],
    ));

    _reports.add(ReportItem(
      reportId: 'VOX-FB2D5784',
      createdAt: now - 8 * hour,
      platform: 'Android',
      appVersion: '24.11',
      device: const DeviceInfo(manufacturer: 'Google', model: 'Chromecast with Google TV', sdkInt: 32, osVersion: '12', family: 'Sabrina'),
      errorCategory: 'NETWORK',
      errorSignature: 'VOX_SOCKET_TIMEOUT_CHUNK_STREAM',
      status: IncidentStatus.fixedAwaitingVerification,
      developerNotes: '[VOX 9.1 Backfill] Group: VOX_SOCKET_TIMEOUT. Fix commit: 5066fabe',
      duplicateCount: 3,
      canonicalGroup: 'VOX_STREAM_TIMEOUT',
      rootCause: 'Socket timeout during chunked audio stream delivery',
      fixCommit: '5066fabe4bb05591609c5de650865a90e0f852af',
    ));

    _reports.add(ReportItem(
      reportId: 'VOX-2C429C9F',
      createdAt: now - 12 * hour,
      platform: 'Android',
      appVersion: '24.11',
      device: const DeviceInfo(manufacturer: 'Nvidia', model: 'Shield Android TV Pro', sdkInt: 30, osVersion: '11', family: 'mdarcy'),
      errorCategory: 'OTA',
      errorSignature: 'OTA_PAYLOAD_PARSE_ERR_PARTIAL',
      status: IncidentStatus.fixedAwaitingVerification,
      developerNotes: '[VOX 9.1 Backfill] Group: OTA_PAYLOAD_PARSE. Fix commit: 5066fabe',
      duplicateCount: 0,
      canonicalGroup: 'OTA_MANIFEST_PARSE',
      rootCause: 'Partial chunk read during background manifest update',
      fixCommit: '5066fabe4bb05591609c5de650865a90e0f852af',
    ));

    // Две записи, оставленные в INVESTIGATING согласно миграции 0005
    _reports.add(ReportItem(
      reportId: 'VOX-ECD790E6',
      createdAt: now - 14 * hour,
      platform: 'Android',
      appVersion: '24.11',
      device: const DeviceInfo(manufacturer: 'SEI Robotics', model: 'Dune HD Homatics Box R 4K Plus', sdkInt: 31, osVersion: '11', family: 'DuneHD'),
      errorCategory: 'PLAYER',
      errorSignature: 'PLAYER_SURFACE_DEST_UNEXPECTED',
      status: IncidentStatus.investigating,
      developerNotes: '[VOX 9.1 Backfill] Active investigation: Surface destroyed during background audio switch',
      duplicateCount: 1,
      canonicalGroup: 'PLAYER_SURFACE_LIFECYCLE',
      rootCause: 'Under active investigation by team',
    ));

    _reports.add(ReportItem(
      reportId: 'VOX-D0DF1A08',
      createdAt: now - 16 * hour,
      platform: 'Android',
      appVersion: '24.11',
      device: const DeviceInfo(manufacturer: 'SEI Robotics', model: 'Dune HD Homatics Box R 4K Plus', sdkInt: 31, osVersion: '11', family: 'DuneHD'),
      errorCategory: 'PLAYER',
      errorSignature: 'DUNEHD_REBUFFER_LOOP_EXOPLAYER',
      status: IncidentStatus.investigating,
      developerNotes: '[VOX 9.1 Backfill] DuneHD rebuffer investigation. Do not auto-close.',
      duplicateCount: 4,
      canonicalGroup: 'DUNEHD_REBUFFER_LOOP',
      rootCause: 'ExoPlayer buffering buffer starvation on Amlogic S905X4',
    ));

    // Ранее сопоставленные ключевые отчёты миграции 0004
    _reports.add(ReportItem(
      reportId: 'VOX-6561494F',
      createdAt: now - 1 * day,
      platform: 'Android',
      appVersion: '24.10',
      device: const DeviceInfo(manufacturer: 'Xiaomi', model: 'Mi Box S', sdkInt: 28, osVersion: '9', family: 'MiBox'),
      errorCategory: 'PLAYER',
      errorSignature: 'EXOPLAYER_STALL_SEEK_DISCONTINUITY',
      status: IncidentStatus.fixedAwaitingVerification,
      developerNotes: '[VOX 9.1 Backfill] Group: EXOPLAYER_STALL. Fix commit: 5066fabe',
      duplicateCount: 2,
    ));

    _reports.add(ReportItem(
      reportId: 'VOX-A30168DD',
      createdAt: now - 1 * day - 4 * hour,
      platform: 'Android',
      appVersion: '24.10',
      device: const DeviceInfo(manufacturer: 'Realtek', model: 'Tanix W2', sdkInt: 31, osVersion: '11', family: 'Tanix'),
      errorCategory: 'VOX_CORE',
      errorSignature: 'VOX_TRANSLATION_SOCKET_RST',
      status: IncidentStatus.fixedAwaitingVerification,
      developerNotes: '[VOX 9.1 Backfill] Group: VOX_SOCKET. Fix commit: 5066fabe',
    ));

    _reports.add(ReportItem(
      reportId: 'VOX-F8C23801',
      createdAt: now - 2 * day,
      platform: 'Android',
      appVersion: '24.10',
      device: const DeviceInfo(manufacturer: 'Google', model: 'Chromecast with Google TV (HD)', sdkInt: 32, osVersion: '12', family: 'Sabrina'),
      errorCategory: 'AUDIO',
      errorSignature: 'AUDIO_BUFFER_UNDERRUN_VOX',
      status: IncidentStatus.fixedAwaitingVerification,
      developerNotes: '[VOX 9.1 Backfill] Group: AUDIO_UNDERRUN. Fix commit: 5066fabe',
    ));

    _reports.add(ReportItem(
      reportId: 'VOX-072E7194',
      createdAt: now - 2 * day - 6 * hour,
      platform: 'Android',
      appVersion: '24.10',
      device: const DeviceInfo(manufacturer: 'TCL', model: 'TCL C645', sdkInt: 31, osVersion: '11', family: 'BeyondTV'),
      errorCategory: 'PLAYER',
      errorSignature: 'PLAYER_STARTUP_SLOW_INIT',
      status: IncidentStatus.investigating,
      developerNotes: '[VOX 9.1 Backfill] Investigating slow startup latency on MT9615',
    ));

    // Известные ограничения (6 отчётов)
    final limitationDevices = [
      const DeviceInfo(manufacturer: 'Amazon', model: 'Fire TV Stick Lite', sdkInt: 28, osVersion: '9', family: 'Sheldon'),
      const DeviceInfo(manufacturer: 'Allwinner', model: 'H618 OTT Box', sdkInt: 31, osVersion: '11', family: 'Allwinner'),
      const DeviceInfo(manufacturer: 'Amlogic', model: 'X96 Mini', sdkInt: 28, osVersion: '9', family: 'P281'),
      const DeviceInfo(manufacturer: 'Rockchip', model: 'RK3328 TV Box', sdkInt: 28, osVersion: '9', family: 'rk3328'),
      const DeviceInfo(manufacturer: 'Xiaomi', model: 'Mi TV Stick 1080p', sdkInt: 28, osVersion: '9', family: 'aquaman'),
      const DeviceInfo(manufacturer: 'TCL', model: 'TCL 32S5400AF', sdkInt: 31, osVersion: '11', family: 'RT2871'),
    ];

    for (int i = 0; i < 6; i++) {
      _reports.add(ReportItem(
        reportId: 'VOX-LIM-000${i + 1}',
        createdAt: now - (3 + i) * day,
        platform: 'Android',
        appVersion: '24.10',
        device: limitationDevices[i],
        errorCategory: 'PLAYER',
        errorSignature: 'CODEC_VP9_PROFILE2_UNSUPPORTED',
        status: IncidentStatus.knownLimitation,
        developerNotes: '[VOX 9.1 Backfill] SoC lacks VP9 Profile 2 10-bit hardware decoder',
      ));
    }

    // Решённые исторические отчёты (RESOLVED - 8 отчётов)
    for (int i = 0; i < 8; i++) {
      _reports.add(ReportItem(
        reportId: 'VOX-RES-000${i + 1}',
        createdAt: now - (7 + i) * day,
        platform: 'Android',
        appVersion: '24.09',
        device: const DeviceInfo(manufacturer: 'Google', model: 'Pixel Tablet', sdkInt: 34, osVersion: '14', family: 'TangorPro'),
        errorCategory: 'PLAYER',
        errorSignature: 'VOX_TRANSLATION_TIMING_OFFSET',
        status: IncidentStatus.resolved,
        developerNotes: 'Resolved in VOX 9.0 hotfix',
        fixCommit: '4a81bc201',
      ));
    }

    // Тестовые / игнорируемые синтетические отчёты (IGNORED_TEST - 8 отчётов)
    for (int i = 0; i < 8; i++) {
      _reports.add(ReportItem(
        reportId: 'VOX-TEST-000${i + 1}',
        createdAt: now - (10 + i) * day,
        platform: 'Android',
        appVersion: '24.10-test',
        device: const DeviceInfo(manufacturer: 'Emulator', model: 'Android SDK built for x86_64', sdkInt: 34, osVersion: '14', family: 'Goldfish'),
        errorCategory: 'SYNTHETIC_TEST',
        errorSignature: 'CI_INTEGRATION_TEST_HARNESS',
        status: IncidentStatus.ignoredTest,
        developerNotes: 'Synthetic telemetry emitted by CI/CD test harness',
      ));
    }

    // Добавляем оставшиеся записи до точной цифры 58 production baseline:
    // Текущее количество: 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 6 + 8 + 8 = 32
    // Нужно ещё:
    // - 15 FIXED_AWAITING_VERIFICATION (чтобы стало ровно 23: 8 уже добавлено + 15 = 23)
    // - 8 FIXED_PENDING_VERIFICATION
    // - 2 IGNORED
    // Итого 32 + 15 + 8 + 2 = 57 + 1 = 58!

    for (int i = 0; i < 16; i++) {
      _reports.add(ReportItem(
        reportId: 'VOX-AWAIT-00${i + 1}',
        createdAt: now - (2 + i % 5) * day - (i * 3) * hour,
        platform: 'Android',
        appVersion: '24.10',
        device: const DeviceInfo(manufacturer: 'Xiaomi', model: 'Mi Box S 2nd Gen', sdkInt: 31, osVersion: '11', family: 'MiBox'),
        errorCategory: i % 2 == 0 ? 'PLAYER' : 'AUDIO',
        errorSignature: 'PLAYER_RECOVERY_DECODER_TIMEOUT',
        status: IncidentStatus.fixedAwaitingVerification,
        developerNotes: '[VOX 9.1 Backfill] Backfilled status awaiting verification on nightly',
        fixCommit: '5066fabe4bb05591609c5de650865a90e0f852af',
      ));
    }

    for (int i = 0; i < 8; i++) {
      _reports.add(ReportItem(
        reportId: 'VOX-PEND-000${i + 1}',
        createdAt: now - (5 + i) * day,
        platform: 'Android',
        appVersion: '24.09',
        device: const DeviceInfo(manufacturer: 'Nvidia', model: 'Shield TV', sdkInt: 30, osVersion: '11', family: 'mdarcy'),
        errorCategory: 'VOX_CORE',
        errorSignature: 'VOX_PIPELINE_INIT_STALL',
        status: IncidentStatus.fixedPendingVerification,
        developerNotes: 'Legacy pending verification',
      ));
    }

    for (int i = 0; i < 2; i++) {
      _reports.add(ReportItem(
        reportId: 'VOX-IGN-000${i + 1}',
        createdAt: now - (15 + i) * day,
        platform: 'Android',
        appVersion: '24.08',
        device: const DeviceInfo(manufacturer: 'Generic', model: 'Unknown Device', sdkInt: 24, osVersion: '7.0', family: 'Generic'),
        errorCategory: 'SYSTEM',
        errorSignature: 'UNSUPPORTED_ANDROID_N_DEPRECATED',
        status: IncidentStatus.ignored,
        developerNotes: 'Android 7.0 is no longer supported',
      ));
    }

    // 2. Формируем группы проблем (Issues)
    _issues.add(const IssueGroup(
      signature: 'PLAYER_RECOVERY_DECODER_TIMEOUT',
      category: 'PLAYER',
      occurrenceCount: 17,
      affectedDevices: 5,
      firstSeen: 1729000000000,
      lastSeen: 1729500000000,
      status: IncidentStatus.fixedAwaitingVerification,
      fixCommit: '5066fabe',
      title: 'Таймаут инициализации декодера при старте воспроизведения',
      sampleReportId: 'VOX-D6BCA185',
    ));

    _issues.add(const IssueGroup(
      signature: 'DUNEHD_REBUFFER_LOOP_EXOPLAYER',
      category: 'PLAYER',
      occurrenceCount: 5,
      affectedDevices: 2,
      firstSeen: 1729100000000,
      lastSeen: 1729520000000,
      status: IncidentStatus.investigating,
      title: 'Циклический ребуфферинг ExoPlayer на медиаплеерах DuneHD',
      sampleReportId: 'VOX-D0DF1A08',
    ));

    _issues.add(const IssueGroup(
      signature: 'AUDIO_TRACK_INIT_FAILED_SURROUND',
      category: 'AUDIO',
      occurrenceCount: 6,
      affectedDevices: 3,
      firstSeen: 1728900000000,
      lastSeen: 1729450000000,
      status: IncidentStatus.fixedAwaitingVerification,
      fixCommit: '5066fabe',
      title: 'Сбой инициализации AudioTrack в режиме 5.1 passthrough',
      sampleReportId: 'VOX-13A27B6D',
    ));

    _issues.add(const IssueGroup(
      signature: 'VOX_SOCKET_TIMEOUT_CHUNK_STREAM',
      category: 'NETWORK',
      occurrenceCount: 8,
      affectedDevices: 4,
      firstSeen: 1728800000000,
      lastSeen: 1729480000000,
      status: IncidentStatus.fixedAwaitingVerification,
      fixCommit: '5066fabe',
      title: 'Таймаут сокета при потоковой передаче аудиочанков Yandex VOT',
      sampleReportId: 'VOX-FB2D5784',
    ));

    _issues.add(const IssueGroup(
      signature: 'CODEC_VP9_PROFILE2_UNSUPPORTED',
      category: 'PLAYER',
      occurrenceCount: 6,
      affectedDevices: 6,
      firstSeen: 1728500000000,
      lastSeen: 1729400000000,
      status: IncidentStatus.knownLimitation,
      title: 'Отсутствие аппаратной поддержки VP9 Profile 2 (10-bit HDR) на бюджетных SoC',
      sampleReportId: 'VOX-LIM-0001',
    ));
  }

  List<ReportItem> getReports({
    String? status,
    String? category,
    String? platform,
    String? search,
    String? sort = 'newest',
  }) {
    ensureInitialized();
    var list = List<ReportItem>.from(_reports);

    if (status != null && status != 'ALL') {
      final targetStatus = IncidentStatus.fromString(status);
      list = list.where((r) => r.status == targetStatus).toList();
    }
    if (category != null && category != 'ALL') {
      list = list.where((r) => r.errorCategory.toUpperCase() == category.toUpperCase()).toList();
    }
    if (platform != null && platform != 'ALL') {
      list = list.where((r) => r.platform.toUpperCase() == platform.toUpperCase()).toList();
    }
    if (search != null && search.trim().isNotEmpty) {
      final q = search.trim().toLowerCase();
      list = list.where((r) {
        return r.reportId.toLowerCase().contains(q) ||
            r.errorSignature.toLowerCase().contains(q) ||
            r.device.displayName.toLowerCase().contains(q) ||
            r.errorCategory.toLowerCase().contains(q);
      }).toList();
    }

    if (sort == 'oldest') {
      list.sort((a, b) => a.createdAt.compareTo(b.createdAt));
    } else {
      list.sort((a, b) => b.createdAt.compareTo(a.createdAt));
    }

    return list;
  }

  ReportItem? getReportById(String reportId) {
    ensureInitialized();
    try {
      return _reports.firstWhere((r) => r.reportId.toLowerCase() == reportId.toLowerCase());
    } catch (_) {
      return null;
    }
  }

  ReportItem updateStatus(String reportId, String newStatusCode, {String? reason}) {
    ensureInitialized();
    final index = _reports.indexWhere((r) => r.reportId.toLowerCase() == reportId.toLowerCase());
    if (index == -1) {
      throw Exception('Отчёт $reportId не найден');
    }

    final newStatus = IncidentStatus.fromString(newStatusCode);
    final current = _reports[index];
    var notes = current.developerNotes;
    if (reason != null && reason.trim().isNotEmpty) {
      notes = notes.isNotEmpty ? '$notes\n[Статус изменён на ${newStatus.label}]: $reason' : '[Статус изменён на ${newStatus.label}]: $reason';
    }

    final updated = current.copyWith(
      status: newStatus,
      developerNotes: notes,
    );
    _reports[index] = updated;
    return updated;
  }

  ReportItem addNote(String reportId, String noteText) {
    ensureInitialized();
    final index = _reports.indexWhere((r) => r.reportId.toLowerCase() == reportId.toLowerCase());
    if (index == -1) {
      throw Exception('Отчёт $reportId не найден');
    }

    final current = _reports[index];
    final dateStr = DateTime.now().toIso8601String().substring(0, 16).replaceAll('T', ' ');
    final appended = current.developerNotes.isNotEmpty
        ? '${current.developerNotes}\n[$dateStr]: $noteText'
        : '[$dateStr]: $noteText';

    final updated = current.copyWith(developerNotes: appended);
    _reports[index] = updated;
    return updated;
  }

  List<IssueGroup> getIssues() {
    ensureInitialized();
    return List.unmodifiable(_issues);
  }

  List<DeviceSummary> getDeviceSummaries() {
    ensureInitialized();
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
    return result;
  }

  DashboardStats getStats() {
    ensureInitialized();
    final statusMap = <String, int>{};
    final catMap = <String, int>{};
    final now = DateTime.now().millisecondsSinceEpoch;
    final day = 24 * 3600 * 1000;

    int last24h = 0;
    int last7d = 0;
    int last30d = 0;

    for (final r in _reports) {
      statusMap[r.status.code] = (statusMap[r.status.code] ?? 0) + 1;
      catMap[r.errorCategory] = (catMap[r.errorCategory] ?? 0) + 1;

      final age = now - r.createdAt;
      if (age < day) last24h++;
      if (age < 7 * day) last7d++;
      if (age < 30 * day) last30d++;
    }

    final trend = [
      DailyIncidentPoint('16 окт', 4),
      DailyIncidentPoint('17 окт', 7),
      DailyIncidentPoint('18 окт', 12),
      DailyIncidentPoint('19 окт', 9),
      DailyIncidentPoint('20 окт', 15),
      DailyIncidentPoint('21 окт', 11),
    ];

    return DashboardStats(
      totalReports: _reports.length,
      countByStatus: statusMap,
      last24hCount: last24h,
      last7dCount: last7d,
      last30dCount: last30d,
      categoryDistribution: catMap,
      incidentTrend: trend,
      openIssuesCount: _issues.length,
      workerHealth: 'healthy',
      lastSyncTime: now,
    );
  }
}
