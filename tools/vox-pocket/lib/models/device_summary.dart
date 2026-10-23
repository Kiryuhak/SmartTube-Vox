class DeviceSummary {
  final String model;
  final String manufacturer;
  final String platform;
  final int? sdkInt;
  final int reportCount;
  final int lastReportTime;
  final Map<String, int> topCategories;
  final List<String> versions;

  const DeviceSummary({
    required this.model,
    required this.manufacturer,
    required this.platform,
    this.sdkInt,
    required this.reportCount,
    required this.lastReportTime,
    this.topCategories = const {},
    this.versions = const [],
  });

  factory DeviceSummary.fromJson(Map<String, dynamic> json) {
    final topCats = <String, int>{};
    if (json['topCategories'] is Map) {
      (json['topCategories'] as Map).forEach((k, v) {
        if (v is int) topCats[k.toString()] = v;
      });
    }

    final vers = <String>[];
    if (json['versions'] is List) {
      for (final v in json['versions'] as List) {
        vers.add(v.toString());
      }
    }

    return DeviceSummary(
      model: json['model']?.toString() ?? 'Неизвестная модель',
      manufacturer: json['manufacturer']?.toString() ?? '',
      platform: json['platform']?.toString() ?? 'Android',
      sdkInt: json['sdkInt'] is int ? json['sdkInt'] as int : null,
      reportCount: json['reportCount'] is int ? json['reportCount'] as int : (json['count'] is int ? json['count'] as int : 1),
      lastReportTime: json['lastReportTime'] is int ? json['lastReportTime'] as int : (json['last_seen'] is int ? json['last_seen'] as int : 0),
      topCategories: topCats,
      versions: vers,
    );
  }

  String get displayName {
    if (model.isNotEmpty) {
      if (manufacturer.isNotEmpty && !model.toLowerCase().contains(manufacturer.toLowerCase())) {
        return '$manufacturer $model';
      }
      return model;
    }
    return manufacturer.isNotEmpty ? manufacturer : 'Неизвестное устройство';
  }

  Map<String, dynamic> toJson() => {
    'model': model,
    'manufacturer': manufacturer,
    'platform': platform,
    if (sdkInt != null) 'sdkInt': sdkInt,
    'reportCount': reportCount,
    'lastReportTime': lastReportTime,
    'topCategories': topCategories,
    'versions': versions,
  };
}
