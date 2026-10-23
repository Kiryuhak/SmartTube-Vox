import 'package:flutter/material.dart';
import '../services/app_repository.dart';
import 'devices_screen.dart';
import 'home_screen.dart';
import 'issues_screen.dart';
import 'more_screen.dart';
import 'reports_screen.dart';

class MainShell extends StatefulWidget {
  final AppRepository repository;

  const MainShell({super.key, required this.repository});

  @override
  State<MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<MainShell> {
  int _currentIndex = 0;
  String _reportsStatusFilter = 'ALL';

  void _navigateToReportsWithStatus(String status) {
    setState(() {
      _reportsStatusFilter = status;
      _currentIndex = 1; // Tab: Отчёты
    });
  }

  void _navigateToReportsWithSearch(String query) {
    setState(() {
      _reportsStatusFilter = 'ALL';
      _currentIndex = 1; // Tab: Отчёты
    });
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.repository,
      builder: (context, _) {
        final screens = [
          HomeScreen(
            repository: widget.repository,
            onSelectStatusFilter: _navigateToReportsWithStatus,
          ),
          ReportsScreen(
            repository: widget.repository,
            initialStatusFilter: _reportsStatusFilter,
          ),
          IssuesScreen(
            repository: widget.repository,
            onSelectIssue: _navigateToReportsWithSearch,
          ),
          DevicesScreen(
            repository: widget.repository,
            onSelectDevice: _navigateToReportsWithSearch,
          ),
          MoreScreen(
            repository: widget.repository,
          ),
        ];

        return Scaffold(
          body: IndexedStack(
            index: _currentIndex,
            children: screens,
          ),
          bottomNavigationBar: NavigationBar(
            selectedIndex: _currentIndex,
            onDestinationSelected: (idx) {
              setState(() {
                _currentIndex = idx;
              });
            },
            destinations: const [
              NavigationDestination(
                icon: Icon(Icons.dashboard_outlined),
                selectedIcon: Icon(Icons.dashboard_rounded),
                label: 'Главная',
              ),
              NavigationDestination(
                icon: Icon(Icons.assignment_outlined),
                selectedIcon: Icon(Icons.assignment_rounded),
                label: 'Отчёты',
              ),
              NavigationDestination(
                icon: Icon(Icons.bug_report_outlined),
                selectedIcon: Icon(Icons.bug_report_rounded),
                label: 'Проблемы',
              ),
              NavigationDestination(
                icon: Icon(Icons.devices_outlined),
                selectedIcon: Icon(Icons.devices_rounded),
                label: 'Устройства',
              ),
              NavigationDestination(
                icon: Icon(Icons.more_horiz_outlined),
                selectedIcon: Icon(Icons.more_horiz_rounded),
                label: 'Ещё',
              ),
            ],
          ),
        );
      },
    );
  }
}
