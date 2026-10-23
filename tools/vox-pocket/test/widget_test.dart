import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:vox_pocket/main.dart';
import 'package:vox_pocket/models/incident_status.dart';
import 'package:vox_pocket/screens/home_screen.dart';
import 'package:vox_pocket/screens/report_detail_screen.dart';
import 'package:vox_pocket/screens/reports_screen.dart';
import 'package:vox_pocket/services/api_client.dart';
import 'package:vox_pocket/services/app_repository.dart';
import 'package:vox_pocket/services/mock_data_repository.dart';
import 'package:vox_pocket/services/storage_service.dart';
import 'package:vox_pocket/theme/vox_theme.dart';
import 'package:vox_pocket/widgets/status_badge.dart';

void main() {
  late StorageService storageService;
  late VoxDiagnosticsApiClient apiClient;
  late MockDataRepository mockRepository;
  late AppRepository appRepository;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    storageService = StorageService(prefs);
    apiClient = VoxDiagnosticsApiClient();
    mockRepository = MockDataRepository();
    appRepository = AppRepository(
      storageService: storageService,
      apiClient: apiClient,
      mockRepository: mockRepository,
    );
    await appRepository.refreshAll();
  });

  group('Widget Tests - StatusBadge', () {
    testWidgets('Renders status label and color indicator dot', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: StatusBadge(status: IncidentStatus.fixedAwaitingVerification),
          ),
        ),
      );

      expect(find.text('Исправлено — ждёт проверки'), findsOneWidget);
    });
  });

  group('Widget Tests - Navigation & App Shell', () {
    testWidgets('Full VoxPocketApp renders bottom NavigationBar with 5 destinations', (tester) async {
      await tester.pumpWidget(VoxPocketApp(repository: appRepository));
      await tester.pumpAndSettle();

      expect(find.text('Главная'), findsOneWidget);
      expect(find.text('Отчёты'), findsOneWidget);
      expect(find.text('Проблемы'), findsOneWidget);
      expect(find.text('Устройства'), findsOneWidget);
      expect(find.text('Ещё'), findsOneWidget);

      // Verify Home dashboard elements
      expect(find.text('SmartTube VOX Pocket'), findsOneWidget);
      expect(find.text('Статус инцидентов'), findsOneWidget);
      expect(find.text('Новые'), findsOneWidget);
      expect(find.text('В работе'), findsOneWidget);
      expect(find.text('Ждут проверки'), findsOneWidget);
      expect(find.text('Решено'), findsOneWidget);

      // Tap on 'Отчёты' tab
      await tester.tap(find.text('Отчёты'));
      await tester.pumpAndSettle();

      expect(find.text('Диагностические отчёты'), findsOneWidget);
      expect(find.byType(TextField), findsOneWidget); // Search bar

      // Tap on 'Проблемы' tab
      await tester.tap(find.text('Проблемы'));
      await tester.pumpAndSettle();

      expect(find.text('Группы проблем'), findsOneWidget);

      // Tap on 'Устройства' tab
      await tester.tap(find.text('Устройства'));
      await tester.pumpAndSettle();

      expect(find.widgetWithText(AppBar, 'Устройства'), findsOneWidget);

      // Tap on 'Ещё' tab
      await tester.tap(find.text('Ещё'));
      await tester.pumpAndSettle();

      expect(find.text('Ещё и настройки'), findsOneWidget);
      expect(find.text('Cloudflare Worker & База D1'), findsOneWidget);
    });
  });

  group('Widget Tests - ReportsScreen Filtering & Interaction', () {
    testWidgets('Search input filters report list dynamically', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: VoxTheme.darkTheme,
          home: ReportsScreen(repository: appRepository),
        ),
      );
      await tester.pumpAndSettle();

      // Enter search text 'OnePlus'
      await tester.enterText(find.byType(TextField), 'OnePlus');
      await tester.pumpAndSettle();

      expect(find.text('VOX-D6BCA185'), findsOneWidget);
    });
  });

  group('Widget Tests - ReportDetailScreen', () {
    testWidgets('Detail screen renders all 4 tabs and opens status dialog', (tester) async {
      final sample = appRepository.reports.first;

      await tester.pumpWidget(
        MaterialApp(
          theme: VoxTheme.darkTheme,
          home: ReportDetailScreen(report: sample, repository: appRepository),
        ),
      );
      await tester.pumpAndSettle();

      // Tabs
      expect(find.text('Обзор'), findsOneWidget);
      expect(find.text('События'), findsOneWidget);
      expect(find.text('Заметки'), findsOneWidget);
      expect(find.text('Файлы'), findsOneWidget);

      // Status change button
      expect(find.text('Изменить статус'), findsOneWidget);

      // Switch to 'Файлы' tab
      await tester.tap(find.text('Файлы'));
      await tester.pumpAndSettle();

      expect(find.text('Скачать JSON отчёта'), findsOneWidget);
      expect(find.text('Поделиться через Sharesheet'), findsOneWidget);
      expect(find.text('Скопировать JSON в буфер'), findsOneWidget);
    });
  });

  group('Widget Tests - Responsive Compact Widths (No Overflow)', () {
    final testWidths = [320.0, 360.0, 393.0, 430.0];

    for (final width in testWidths) {
      testWidgets('HomeScreen renders without horizontal overflow at ${width.toInt()}px width', (tester) async {
        tester.view.physicalSize = Size(width * 2, 800 * 2);
        tester.view.devicePixelRatio = 2.0;

        await tester.pumpWidget(
          MaterialApp(
            theme: VoxTheme.darkTheme,
            home: HomeScreen(
              repository: appRepository,
              onSelectStatusFilter: (_) {},
            ),
          ),
        );
        await tester.pumpAndSettle();

        final err = tester.takeException();
        expect(err, isNull, reason: 'Overflow occurred at width $width');

        addTearDown(() {
          tester.view.resetPhysicalSize();
          tester.view.resetDevicePixelRatio();
        });
      });
    }
  });
}
