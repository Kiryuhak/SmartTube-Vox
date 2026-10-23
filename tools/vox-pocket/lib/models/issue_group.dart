import 'incident_status.dart';

class IssueGroup {
  final String signature;
  final String category;
  final int occurrenceCount;
  final int affectedDevices;
  final int firstSeen;
  final int lastSeen;
  final IncidentStatus status;
  final String fixCommit;
  final bool isRegression;
  final String title;
  final String sampleReportId;

  const IssueGroup({
    required this.signature,
    required this.category,
    required this.occurrenceCount,
    required this.affectedDevices,
    required this.firstSeen,
    required this.lastSeen,
    required this.status,
    this.fixCommit = '',
    this.isRegression = false,
    this.title = '',
    this.sampleReportId = '',
  });

  factory IssueGroup.fromJson(Map<String, dynamic> json) {
    return IssueGroup(
      signature: json['signature']?.toString() ?? json['error_signature']?.toString() ?? 'UNKNOWN_ISSUE',
      category: json['category']?.toString() ?? json['error_category']?.toString() ?? 'PLAYER',
      occurrenceCount: json['count'] is int
          ? json['count'] as int
          : (json['occurrenceCount'] is int ? json['occurrenceCount'] as int : 1),
      affectedDevices: json['affectedDevices'] is int
          ? json['affectedDevices'] as int
          : (json['devicesCount'] is int ? json['devicesCount'] as int : 1),
      firstSeen: json['first_seen'] is int
          ? json['first_seen'] as int
          : (json['firstSeen'] is int ? json['firstSeen'] as int : 0),
      lastSeen: json['last_seen'] is int
          ? json['last_seen'] as int
          : (json['lastSeen'] is int ? json['lastSeen'] as int : 0),
      status: IncidentStatus.fromString(json['status']?.toString()),
      fixCommit: json['fix_commit']?.toString() ?? json['fixCommit']?.toString() ?? '',
      isRegression: json['is_regression'] == true || json['isRegression'] == true,
      title: json['title']?.toString() ?? json['description']?.toString() ?? '',
      sampleReportId: json['sample_report_id']?.toString() ?? json['sampleReportId']?.toString() ?? '',
    );
  }

  Map<String, dynamic> toJson() => {
    'signature': signature,
    'category': category,
    'count': occurrenceCount,
    'affectedDevices': affectedDevices,
    'first_seen': firstSeen,
    'last_seen': lastSeen,
    'status': status.code,
    'fix_commit': fixCommit,
    'is_regression': isRegression,
    'title': title,
    'sample_report_id': sampleReportId,
  };
}
