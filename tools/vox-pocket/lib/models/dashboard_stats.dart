class DailyIncidentPoint {
  final String date;
  final int count;

  const DailyIncidentPoint(this.date, this.count);

  factory DailyIncidentPoint.fromJson(Map<String, dynamic> json) {
    return DailyIncidentPoint(
      json['date']?.toString() ?? '',
      json['count'] is int ? json['count'] as int : 0,
    );
  }

  Map<String, dynamic> toJson() => {'date': date, 'count': count};
}

class DashboardStats {
  final int totalReports;
  final Map<String, int> countByStatus;
  final int last24hCount;
  final int last7dCount;
  final int last30dCount;
  final Map<String, int> categoryDistribution;
  final List<DailyIncidentPoint> incidentTrend;
  final int openIssuesCount;
  final String workerHealth;
  final int lastSyncTime;

  const DashboardStats({
    required this.totalReports,
    required this.countByStatus,
    required this.last24hCount,
    required this.last7dCount,
    required this.last30dCount,
    required this.categoryDistribution,
    required this.incidentTrend,
    required this.openIssuesCount,
    this.workerHealth = 'ok',
    this.lastSyncTime = 0,
  });

  factory DashboardStats.fromJson(Map<String, dynamic> json) {
    final statusMap = <String, int>{};
    if (json['countByStatus'] is Map) {
      (json['countByStatus'] as Map).forEach((k, v) {
        if (v is int) statusMap[k.toString()] = v;
      });
    } else if (json['statuses'] is Map) {
      (json['statuses'] as Map).forEach((k, v) {
        if (v is int) statusMap[k.toString()] = v;
      });
    }

    final catMap = <String, int>{};
    if (json['categoryDistribution'] is Map) {
      (json['categoryDistribution'] as Map).forEach((k, v) {
        if (v is int) catMap[k.toString()] = v;
      });
    } else if (json['categories'] is Map) {
      (json['categories'] as Map).forEach((k, v) {
        if (v is int) catMap[k.toString()] = v;
      });
    }

    final trend = <DailyIncidentPoint>[];
    if (json['incidentTrend'] is List) {
      for (final p in json['incidentTrend'] as List) {
        if (p is Map<String, dynamic>) {
          trend.add(DailyIncidentPoint.fromJson(p));
        }
      }
    }

    return DashboardStats(
      totalReports: json['totalReports'] is int ? json['totalReports'] as int : (json['total'] is int ? json['total'] as int : 0),
      countByStatus: statusMap,
      last24hCount: json['last24hCount'] is int ? json['last24hCount'] as int : (json['last24h'] is int ? json['last24h'] as int : 0),
      last7dCount: json['last7dCount'] is int ? json['last7dCount'] as int : (json['last7d'] is int ? json['last7d'] as int : 0),
      last30dCount: json['last30dCount'] is int ? json['last30dCount'] as int : (json['last30d'] is int ? json['last30d'] as int : 0),
      categoryDistribution: catMap,
      incidentTrend: trend,
      openIssuesCount: json['openIssuesCount'] is int ? json['openIssuesCount'] as int : 0,
      workerHealth: json['workerHealth']?.toString() ?? 'ok',
      lastSyncTime: json['lastSyncTime'] is int ? json['lastSyncTime'] as int : 0,
    );
  }

  int get newCount => countByStatus['NEW'] ?? 0;
  int get investigatingCount => (countByStatus['INVESTIGATING'] ?? 0) + (countByStatus['ROOT_CAUSE_FOUND'] ?? 0) + (countByStatus['FIX_IMPLEMENTED'] ?? 0) + (countByStatus['TESTING'] ?? 0);
  int get awaitingVerificationCount => (countByStatus['FIXED_AWAITING_VERIFICATION'] ?? 0) + (countByStatus['FIXED_PENDING_VERIFICATION'] ?? 0);
  int get resolvedCount => (countByStatus['RESOLVED'] ?? 0) + (countByStatus['VERIFIED'] ?? 0);
  int get knownLimitationsCount => (countByStatus['KNOWN_LIMITATION'] ?? 0) + (countByStatus['KNOWN_ISSUE'] ?? 0);
  int get regressionCount => countByStatus['REGRESSION'] ?? 0;
}
