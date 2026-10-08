import { compareVoxVersions, generateDeterministicIssueTitle } from '../triage.mjs';
import { normalizeStatus } from '../storage.mjs';

/**
 * Сервис автоматического безопасного триажа диагностических отчётов SmartTube VOX.
 * 
 * Правила безопасности:
 * 1. Детерминированная логика без вызова внешних непроверенных моделей/LLM.
 * 2. Автоматический перевод в RESOLVED КАТЕГОРИЧЕСКИ ЗАПРЕЩЁН.
 * 3. Поддержка версионной регрессии (historical occurrence vs regression candidate).
 * 4. Полная идемпотентность и поддержка безопасного режима Dry-Run.
 */
export class AutoTriageService {
  constructor(storage) {
    if (!storage) throw new Error('ReportStorage instance is required for AutoTriageService');
    this.storage = storage;
  }

  /**
   * Выполняет прогон автоматического триажа по журналу изменений (changes feed).
   * 
   * @param {Object} options
   * @param {number} [options.cursor=0] Курсор изменений
   * @param {number} [options.limit=100] Максимальное число изменений за один проход
   * @param {boolean} [options.dryRun=false] Режим без записи изменений в БД
   * @param {string} [options.actor='AUTOMATION'] Идентификатор актора
   * @returns {Promise<Object>} Сводный отчёт о результатах триажа
   */
  async runTriage({ cursor = 0, limit = 100, dryRun = false, actor = 'AUTOMATION' } = {}) {
    const startCursor = Number(cursor) || 0;
    const { changes, nextCursor } = await this.storage.listChanges(startCursor, limit);

    const summary = {
      checkedReports: 0,
      newReports: 0,
      updatedReports: 0,
      duplicates: 0,
      newIssues: 0,
      knownIssues: 0,
      reopenedIssues: 0,
      ignoredTests: 0,
      statusChanges: 0,
      criticalAlerts: [],
      errors: [],
      dryRun: Boolean(dryRun),
      cursor: startCursor,
      nextCursor: nextCursor || startCursor,
      plannedMutations: []
    };

    if (!changes || changes.length === 0) {
      return summary;
    }

    const processedReportIds = new Set();

    for (const change of changes) {
      const reportId = change.reportId;
      if (!reportId || processedReportIds.has(reportId)) continue;
      processedReportIds.add(reportId);

      try {
        const report = await this.storage.getReportById(reportId);
        if (!report) continue;

        summary.checkedReports++;
        const currentStatus = normalizeStatus(report.status);
        const reportPurpose = String(report.report_purpose || report.payload?.reportPurpose || '').toUpperCase();
        const isTestReport = reportPurpose === 'TEST' || reportId.includes('TEST');

        // Сценарий 1: Тестовый или отладочный отчёт
        if (isTestReport) {
          summary.ignoredTests++;
          if (currentStatus === 'NEW') {
            const noteText = '[AUTOMATION] Отчёт классифицирован как тестовый/отладочный.';
            const mutation = {
              type: 'REPORT_STATUS',
              reportId,
              fromStatus: currentStatus,
              toStatus: 'IGNORED_TEST',
              note: noteText,
              reason: 'TEST_PURPOSE_DETECTED'
            };
            summary.plannedMutations.push(mutation);
            summary.statusChanges++;

            if (!dryRun) {
              const existingNotes = report.developer_notes || '';
              const newNotes = existingNotes.includes(noteText)
                ? existingNotes
                : (existingNotes ? `${existingNotes}\n${noteText}` : noteText);
              await this.storage.updateReport(reportId, {
                status: 'IGNORED_TEST',
                developerNotes: newNotes,
                reason: 'TEST_PURPOSE_DETECTED'
              });
              summary.updatedReports++;
            }
          }
          continue;
        }

        // Сценарий 2: Боевой пользовательский отчёт
        const errorSignature = report.error_signature || report.errorSignature || '';
        const appVersion = report.app_version || report.appVersion || 'unknown';
        const severity = report.severity || 'INFO';

        if (!errorSignature) {
          // Отчёт без специфической сигнатуры (информационный/общий)
          continue;
        }

        const issue = await this.storage.getIssue(errorSignature);
        const hasExistingIssue = issue && (issue.title || issue.issue_status !== 'NEW');
        const isResolvedOrRecentlyReopened = hasExistingIssue && (issue.issue_status === 'RESOLVED' || (issue.reopened_at > 0 && issue.fixed_in_version));

        if (hasExistingIssue && isResolvedOrRecentlyReopened) {
          // Анализ исправленной проблемы на предмет регрессии
          const fixedInVersion = issue.fixed_in_version || '';

          if (fixedInVersion && compareVoxVersions(appVersion, fixedInVersion) !== null) {
            const comp = compareVoxVersions(appVersion, fixedInVersion);

            if (comp < 0) {
              // Историческое проявление (версия старее фикса) -> НЕ переоткрывать Issue
              summary.duplicates++;
              summary.knownIssues++;

              if (currentStatus === 'NEW') {
                const noteText = `[AUTOMATION] Историческое проявление известной исправленной проблемы (версия ${appVersion} < ${fixedInVersion}).`;
                summary.plannedMutations.push({
                  type: 'REPORT_STATUS',
                  reportId,
                  fromStatus: currentStatus,
                  toStatus: 'KNOWN_ISSUE',
                  note: noteText,
                  reason: 'HISTORICAL_RESOLVED_ISSUE'
                });
                summary.statusChanges++;

                if (!dryRun) {
                  const existingNotes = report.developer_notes || '';
                  const newNotes = existingNotes.includes(noteText)
                    ? existingNotes
                    : (existingNotes ? `${existingNotes}\n${noteText}` : noteText);
                  await this.storage.updateReport(reportId, {
                    status: 'KNOWN_ISSUE',
                    developerNotes: newNotes,
                    reason: 'HISTORICAL_RESOLVED_ISSUE'
                  });
                  summary.updatedReports++;
                }
              }
            } else {
              // РЕГРЕССИЯ: ошибка в той же или более новой версии!
              summary.reopenedIssues++;
              summary.statusChanges++;

              const noteText = `[AUTOMATION] Возможная регрессия: аналогичная ошибка обнаружена в версии ${appVersion} (исправление было заявлено в ${fixedInVersion}).`;
              summary.plannedMutations.push({
                type: 'ISSUE_REOPEN',
                signature: errorSignature,
                fromStatus: 'RESOLVED',
                toStatus: 'IN_PROGRESS',
                reportId,
                appVersion
              });

              if (currentStatus !== 'IN_PROGRESS') {
                summary.plannedMutations.push({
                  type: 'REPORT_STATUS',
                  reportId,
                  fromStatus: currentStatus,
                  toStatus: 'IN_PROGRESS',
                  note: noteText,
                  reason: 'REGRESSION_DETECTED'
                });
              }

              if (['HIGH', 'CRITICAL'].includes(severity)) {
                summary.criticalAlerts.push({
                  type: 'CRITICAL_DIAGNOSTIC_DETECTED',
                  reportId,
                  signature: errorSignature,
                  severity,
                  appVersion,
                  reason: 'REGRESSION_IN_CRITICAL_SUBSYSTEM'
                });
              }

              if (!dryRun) {
                await this.storage.reopenRegressedIssue(errorSignature, appVersion);
                if (currentStatus !== 'IN_PROGRESS') {
                  const existingNotes = report.developer_notes || '';
                  const newNotes = existingNotes.includes(noteText)
                    ? existingNotes
                    : (existingNotes ? `${existingNotes}\n${noteText}` : noteText);
                  await this.storage.updateReport(reportId, {
                    status: 'IN_PROGRESS',
                    developerNotes: newNotes,
                    reason: 'REGRESSION_DETECTED'
                  });
                  summary.updatedReports++;
                }
              }
            }
          } else {
            // Без явного fixedInVersion: связать как дубликат
            summary.duplicates++;
            summary.knownIssues++;
          }
        } else if (hasExistingIssue && ['IN_PROGRESS', 'KNOWN_ISSUE', 'NEW'].includes(issue.issue_status)) {
          // Проблема уже известна и активна в базе инцидентов
          summary.duplicates++;
          summary.knownIssues++;

          if (currentStatus === 'NEW') {
            const noteText = `[AUTOMATION] Связано с известной проблемой (${issue.title || errorSignature}).`;
            summary.plannedMutations.push({
              type: 'REPORT_STATUS',
              reportId,
              fromStatus: currentStatus,
              toStatus: 'KNOWN_ISSUE',
              note: noteText,
              reason: 'LINKED_TO_KNOWN_ISSUE'
            });
            summary.statusChanges++;

            if (!dryRun) {
              const existingNotes = report.developer_notes || '';
              const newNotes = existingNotes.includes(noteText)
                ? existingNotes
                : (existingNotes ? `${existingNotes}\n${noteText}` : noteText);
              await this.storage.updateReport(reportId, {
                status: 'KNOWN_ISSUE',
                developerNotes: newNotes,
                reason: 'LINKED_TO_KNOWN_ISSUE'
              });
              summary.updatedReports++;
            }
          }
        } else {
          // Новая уникальная проблема
          summary.newIssues++;
          summary.newReports++;
          const generatedTitle = generateDeterministicIssueTitle(errorSignature, report);

          summary.plannedMutations.push({
            type: 'CREATE_ISSUE',
            signature: errorSignature,
            title: generatedTitle,
            status: 'NEW'
          });

          const isUrgent = ['HIGH', 'CRITICAL'].includes(severity);
          if (isUrgent) {
            summary.criticalAlerts.push({
              type: 'CRITICAL_DIAGNOSTIC_DETECTED',
              reportId,
              signature: errorSignature,
              severity,
              appVersion,
              reason: 'NEW_HIGH_SEVERITY_ISSUE'
            });
          }

          if (currentStatus === 'NEW') {
            const noteText = isUrgent
              ? `[AUTOMATION] Новая проблема высокой важности (${severity}), зарегистрирована в базе инцидентов.`
              : '[AUTOMATION] Новая проблема зарегистрирована в базе инцидентов.';
            summary.plannedMutations.push({
              type: 'REPORT_STATUS',
              reportId,
              fromStatus: currentStatus,
              toStatus: 'IN_PROGRESS',
              note: noteText,
              reason: isUrgent ? 'NEW_HIGH_SEVERITY_ISSUE' : 'NEW_ISSUE_TRIAGED'
            });
            summary.statusChanges++;

            if (!dryRun) {
              await this.storage.updateIssue(errorSignature, {
                status: 'NEW',
                title: generatedTitle
              });
              const existingNotes = report.developer_notes || '';
              const newNotes = existingNotes.includes(noteText)
                ? existingNotes
                : (existingNotes ? `${existingNotes}\n${noteText}` : noteText);
              await this.storage.updateReport(reportId, {
                status: 'IN_PROGRESS',
                developerNotes: newNotes,
                reason: isUrgent ? 'NEW_HIGH_SEVERITY_ISSUE' : 'NEW_ISSUE_TRIAGED'
              });
              summary.updatedReports++;
            }
          } else if (!dryRun) {
            await this.storage.updateIssue(errorSignature, {
              status: 'NEW',
              title: generatedTitle
            });
          }
        }
      } catch (err) {
        summary.errors.push({ reportId, error: err.message });
      }
    }

    return summary;
  }
}

/**
 * Удобная функция запуска триажа.
 */
export async function runAutoTriage(storage, options = {}) {
  const service = new AutoTriageService(storage);
  return service.runTriage(options);
}
