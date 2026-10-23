import 'package:flutter/material.dart';
import 'screens/main_shell.dart';
import 'services/api_client.dart';
import 'services/app_repository.dart';
import 'services/mock_data_repository.dart';
import 'services/storage_service.dart';
import 'theme/vox_theme.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final storageService = await StorageService.init();
  final apiClient = VoxDiagnosticsApiClient();
  final mockRepository = MockDataRepository();

  final appRepository = AppRepository(
    storageService: storageService,
    apiClient: apiClient,
    mockRepository: mockRepository,
  );

  // Первоначальная загрузка данных
  await appRepository.refreshAll();

  runApp(VoxPocketApp(repository: appRepository));
}

class VoxPocketApp extends StatelessWidget {
  final AppRepository repository;

  const VoxPocketApp({super.key, required this.repository});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SmartTube VOX Pocket',
      debugShowCheckedModeBanner: false,
      theme: VoxTheme.darkTheme,
      home: MainShell(repository: repository),
    );
  }
}
