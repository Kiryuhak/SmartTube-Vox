import 'package:flutter/material.dart';
import '../models/issue_group.dart';
import '../services/app_repository.dart';
import '../theme/vox_theme.dart';
import '../widgets/status_badge.dart';

class IssuesScreen extends StatefulWidget {
  final AppRepository repository;
  final Function(String searchSignature) onSelectIssue;

  const IssuesScreen({
    super.key,
    required this.repository,
    required this.onSelectIssue,
  });

  @override
  State<IssuesScreen> createState() => _IssuesScreenState();
}

class _IssuesScreenState extends State<IssuesScreen> {
  final TextEditingController _searchController = TextEditingController();

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<IssueGroup> _getFilteredIssues() {
    final query = _searchController.text.trim().toLowerCase();
    var list = widget.repository.issues;

    if (query.isNotEmpty) {
      list = list.where((i) {
        return i.signature.toLowerCase().contains(query) ||
            i.title.toLowerCase().contains(query) ||
            i.category.toLowerCase().contains(query);
      }).toList();
    }

    return list;
  }

  @override
  Widget build(BuildContext context) {
    final issues = _getFilteredIssues();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Группы проблем'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
            child: TextField(
              controller: _searchController,
              decoration: InputDecoration(
                hintText: 'Поиск по сигнатуре или описанию...',
                prefixIcon: const Icon(Icons.search_rounded, color: VoxColors.textMuted, size: 20),
                suffixIcon: _searchController.text.isNotEmpty
                    ? IconButton(
                        icon: const Icon(Icons.clear, size: 18),
                        onPressed: () {
                          _searchController.clear();
                          setState(() {});
                        },
                      )
                    : null,
              ),
              onChanged: (_) => setState(() {}),
            ),
          ),
          Expanded(
            child: issues.isEmpty
                ? const Center(
                    child: Text('Проблем не найдено', style: TextStyle(color: VoxColors.textMuted)),
                  )
                : RefreshIndicator(
                    onRefresh: () => widget.repository.refreshAll(),
                    color: VoxColors.primary,
                    child: ListView.builder(
                      itemCount: issues.length,
                      padding: const EdgeInsets.only(bottom: 16),
                      itemBuilder: (ctx, idx) {
                        final issue = issues[idx];
                        return _buildIssueCard(issue);
                      },
                    ),
                  ),
          ),
        ],
      ),
    );
  }

  Widget _buildIssueCard(IssueGroup issue) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () => widget.onSelectIssue(issue.signature),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Expanded(
                    child: Text(
                      issue.signature,
                      style: const TextStyle(
                        color: VoxColors.textPrimary,
                        fontSize: 14,
                        fontWeight: FontWeight.w700,
                      ),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  const SizedBox(width: 8),
                  StatusBadge(status: issue.status, compact: true),
                ],
              ),
              if (issue.title.isNotEmpty) ...[
                const SizedBox(height: 6),
                Text(
                  issue.title,
                  style: const TextStyle(color: VoxColors.textSecondary, fontSize: 13),
                ),
              ],
              const SizedBox(height: 10),
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    decoration: BoxDecoration(
                      color: VoxColors.primary.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: Text(
                      '${issue.occurrenceCount} повторов',
                      style: const TextStyle(color: VoxColors.primary, fontSize: 11, fontWeight: FontWeight.w700),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    decoration: BoxDecoration(
                      color: VoxColors.surface,
                      borderRadius: BorderRadius.circular(4),
                      border: Border.all(color: VoxColors.border),
                    ),
                    child: Text(
                      '${issue.affectedDevices} устр.',
                      style: const TextStyle(color: VoxColors.textSecondary, fontSize: 11),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    decoration: BoxDecoration(
                      color: VoxColors.cyan.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: Text(
                      issue.category,
                      style: const TextStyle(color: VoxColors.cyan, fontSize: 11, fontWeight: FontWeight.w600),
                    ),
                  ),
                  if (issue.fixCommit.isNotEmpty) ...[
                    const Spacer(),
                    const Icon(Icons.commit_rounded, size: 14, color: VoxColors.textMuted),
                    const SizedBox(width: 2),
                    Text(
                      issue.fixCommit.substring(0, issue.fixCommit.length > 8 ? 8 : issue.fixCommit.length),
                      style: const TextStyle(color: VoxColors.textMuted, fontSize: 11, fontFamily: 'monospace'),
                    ),
                  ],
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
