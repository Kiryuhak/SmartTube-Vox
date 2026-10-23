import 'incident_status.dart';

class DeviceInfo {
  final String manufacturer;
  final String model;
  final int? sdkInt;
  final String osVersion;
  final String family;
  final String tier;

  const DeviceInfo({
    this.manufacturer = '',
    this.model = '',
    this.sdkInt,
    this.osVersion = '',
    this.family = '',
    this.tier = '',
  });

  factory DeviceInfo.fromJson(Map<String, dynamic>? json) {
    if (json == null) return const DeviceInfo();
    return DeviceInfo(
      manufacturer: json['manufacturer']?.toString() ?? '',
      model: json['model']?.toString() ?? json['deviceModel']?.toString() ?? '',
      sdkInt: json['sdkInt'] is int
          ? json['sdkInt'] as int
          : int.tryParse(json['sdkInt']?.toString() ?? ''),
      osVersion: json['osVersion']?.toString() ?? '',
      family: json['family']?.toString() ?? json['device_family']?.toString() ?? '',
      tier: json['tier']?.toString() ?? json['deviceTier']?.toString() ?? '',
    );
  }

  Map<String, dynamic> toJson() => {
    'manufacturer': manufacturer,
    'model': model,
    if (sdkInt != null) 'sdkInt': sdkInt,
    'osVersion': osVersion,
    'family': family,
    'tier': tier,
  };

  String get displayName {
    if (model.isNotEmpty) {
      if (manufacturer.isNotEmpty && !model.toLowerCase().contains(manufacturer.toLowerCase())) {
        return '$manufacturer $model';
      }
      return model;
    }
    return manufacturer.isNotEmpty ? manufacturer : (family.isNotEmpty ? family : 'Неизвестное устройство');
  }

  String get apiLabel => sdkInt != null ? 'Android $osVersion (API $sdkInt)' : (osVersion.isNotEmpty ? 'Android $osVersion' : '');
}

class DiagnosticEvent {
  final int timestamp;
  final String tag;
  final String message;
  final String level;

  const DiagnosticEvent({
    required this.timestamp,
    required this.tag,
    required this.message,
    this.level = 'INFO',
  });

  factory DiagnosticEvent.fromJson(Map<String, dynamic> json) {
    return DiagnosticEvent(
      timestamp: json['timestamp'] is int
          ? json['timestamp'] as int
          : int.tryParse(json['timestamp']?.toString() ?? '0') ?? 0,
      tag: json['tag']?.toString() ?? json['category']?.toString() ?? 'SYSTEM',
      message: json['message']?.toString() ?? '',
      level: json['level']?.toString() ?? 'INFO',
    );
  }

  Map<String, dynamic> toJson() => {
    'timestamp': timestamp,
    'tag': tag,
    'message': message,
    'level': level,
  };
}

class ReportNote {
  final String author;
  final int timestamp;
  final String text;

  const ReportNote({
    required this.author,
    required this.timestamp,
    required this.text,
  });

  factory ReportNote.fromJson(Map<String, dynamic> json) {
    return ReportNote(
      author: json['author']?.toString() ?? 'Разработчик',
      timestamp: json['timestamp'] is int
          ? json['timestamp'] as int
          : int.tryParse(json['timestamp']?.toString() ?? '0') ?? 0,
      text: json['text']?.toString() ?? json['note']?.toString() ?? '',
    );
  }

  Map<String, dynamic> toJson() => {
    'author': author,
    'timestamp': timestamp,
    'text': text,
  };
}

class ReportItem {
  final String reportId;
  final int createdAt;
  final String platform;
  final String appVersion;
  final DeviceInfo device;
  final String errorCategory;
  final String errorSignature;
  final IncidentStatus status;
  final String developerNotes;
  final int duplicateCount;
  final String canonicalGroup;
  final String rootCause;
  final String fixCommit;
  final String evidence;
  final List<DiagnosticEvent> safeRecentEvents;
  final Map<String, dynamic>? rawPayload;

  const ReportItem({
    required this.reportId,
    required this.createdAt,
    required this.platform,
    required this.appVersion,
    required this.device,
    required this.errorCategory,
    required this.errorSignature,
    required this.status,
    this.developerNotes = '',
    this.duplicateCount = 0,
    this.canonicalGroup = '',
    this.rootCause = '',
    this.fixCommit = '',
    this.evidence = '',
    this.safeRecentEvents = const [],
    this.rawPayload,
  });

  factory ReportItem.fromJson(Map<String, dynamic> json) {
    final reportId = json['report_id']?.toString() ?? json['reportId']?.toString() ?? 'VOX-UNKNOWN';
    final createdAt = json['created_at'] is int
        ? json['created_at'] as int
        : (json['createdAt'] is int
            ? json['createdAt'] as int
            : int.tryParse(json['created_at']?.toString() ?? json['createdAt']?.toString() ?? '0') ?? 0);

    final platform = json['platform']?.toString() ?? 'Android';
    final appVersion = json['app_version']?.toString() ?? json['appVersion']?.toString() ?? 'unknown';

    // Device parsing
    DeviceInfo device;
    if (json['device'] is Map<String, dynamic>) {
      device = DeviceInfo.fromJson(json['device'] as Map<String, dynamic>);
    } else {
      device = DeviceInfo(
        family: json['device_family']?.toString() ?? '',
        model: json['model']?.toString() ?? '',
        manufacturer: json['manufacturer']?.toString() ?? '',
      );
    }

    final rawPayload = json['payload'] is Map<String, dynamic> ? json['payload'] as Map<String, dynamic> : null;
    final p = rawPayload ?? {};

    // Events parsing
    final events = <DiagnosticEvent>[];
    final rawEvents = json['safeRecentEvents'] ?? p['safeRecentEvents'] ?? json['events'];
    if (rawEvents is List) {
      for (final e in rawEvents) {
        if (e is Map<String, dynamic>) {
          events.add(DiagnosticEvent.fromJson(e));
        }
      }
    }

    // Status parsing
    final rawStatus = json['status']?.toString();
    final status = IncidentStatus.fromString(rawStatus);

    return ReportItem(
      reportId: reportId,
      createdAt: createdAt,
      platform: platform,
      appVersion: appVersion,
      device: device,
      errorCategory: json['error_category']?.toString() ?? json['errorCategory']?.toString() ?? p['errorCategory']?.toString() ?? 'PLAYER',
      errorSignature: json['error_signature']?.toString() ?? json['errorSignature']?.toString() ?? p['errorSignature']?.toString() ?? '',
      status: status,
      developerNotes: json['developer_notes']?.toString() ?? json['developerNotes']?.toString() ?? '',
      duplicateCount: json['duplicate_count'] is int
          ? json['duplicate_count'] as int
          : (json['duplicateCount'] is int ? json['duplicateCount'] as int : 0),
      canonicalGroup: json['canonical_group']?.toString() ?? json['canonicalGroup']?.toString() ?? '',
      rootCause: json['root_cause']?.toString() ?? json['rootCause']?.toString() ?? '',
      fixCommit: json['fix_commit']?.toString() ?? json['fixCommit']?.toString() ?? '',
      evidence: json['evidence']?.toString() ?? '',
      safeRecentEvents: events,
      rawPayload: rawPayload,
    );
  }

  ReportItem copyWith({
    IncidentStatus? status,
    String? developerNotes,
    int? duplicateCount,
    String? canonicalGroup,
    String? rootCause,
    String? fixCommit,
    String? evidence,
    List<DiagnosticEvent>? safeRecentEvents,
  }) {
    return ReportItem(
      reportId: reportId,
      createdAt: createdAt,
      platform: platform,
      appVersion: appVersion,
      device: device,
      errorCategory: errorCategory,
      errorSignature: errorSignature,
      status: status ?? this.status,
      developerNotes: developerNotes ?? this.developerNotes,
      duplicateCount: duplicateCount ?? this.duplicateCount,
      canonicalGroup: canonicalGroup ?? this.canonicalGroup,
      rootCause: rootCause ?? this.rootCause,
      fixCommit: fixCommit ?? this.fixCommit,
      evidence: evidence ?? this.evidence,
      safeRecentEvents: safeRecentEvents ?? this.safeRecentEvents,
      rawPayload: rawPayload,
    );
  }

  Map<String, dynamic> toJson() => {
    'reportId': reportId,
    'createdAt': createdAt,
    'platform': platform,
    'appVersion': appVersion,
    'device': device.toJson(),
    'errorCategory': errorCategory,
    'errorSignature': errorSignature,
    'status': status.code,
    'developerNotes': developerNotes,
    'duplicateCount': duplicateCount,
    'canonicalGroup': canonicalGroup,
    'rootCause': rootCause,
    'fixCommit': fixCommit,
    'evidence': evidence,
    'safeRecentEvents': safeRecentEvents.map((e) => e.toJson()).toList(),
    if (rawPayload != null) 'payload': rawPayload,
  };
}
