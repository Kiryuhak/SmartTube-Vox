import { ReportStorage } from '../src/storage.mjs';
import { runAutoTriage } from '../src/automation/auto_triage.mjs';

async function main() {
  const args = process.argv.slice(2);
  const dryRun = args.includes('--dry-run') || !args.includes('--apply');
  const cursorArg = args.find(a => a.startsWith('--cursor='));
  const cursor = cursorArg ? Number(cursorArg.split('=')[1]) || 0 : 0;
  const limitArg = args.find(a => a.startsWith('--limit='));
  const limit = limitArg ? Number(limitArg.split('=')[1]) || 100 : 100;

  console.log('====================================================');
  console.log(`SMARTTUBE VOX — AUTOMATED DIAGNOSTICS TRIAGE (${dryRun ? 'DRY-RUN MODE' : 'APPLY MODE'})`);
  console.log(`Cursor: ${cursor}, Limit: ${limit}`);
  console.log('====================================================\n');

  const storage = new ReportStorage();
  const summary = await runAutoTriage(storage, { cursor, limit, dryRun });

  console.log('Triage Summary:');
  console.log(`  Checked Reports:   ${summary.checkedReports}`);
  console.log(`  New Reports:       ${summary.newReports}`);
  console.log(`  Updated Reports:   ${summary.updatedReports}`);
  console.log(`  Duplicates:        ${summary.duplicates}`);
  console.log(`  New Issues:        ${summary.newIssues}`);
  console.log(`  Known Issues:      ${summary.knownIssues}`);
  console.log(`  Reopened Issues:   ${summary.reopenedIssues}`);
  console.log(`  Ignored Tests:     ${summary.ignoredTests}`);
  console.log(`  Status Changes:    ${summary.statusChanges}`);
  console.log(`  Critical Alerts:   ${summary.criticalAlerts.length}`);
  console.log(`  Next Cursor:       ${summary.nextCursor}`);
  console.log(`  Errors:            ${summary.errors.length}`);

  if (summary.plannedMutations.length > 0) {
    console.log('\nPlanned Mutations:');
    for (const m of summary.plannedMutations) {
      console.log(`  - [${m.type}] ${m.reportId || m.signature}: ${m.fromStatus || ''} -> ${m.toStatus || ''} (${m.reason || m.title || ''})`);
    }
  }

  if (summary.criticalAlerts.length > 0) {
    console.log('\nCritical Alerts:');
    for (const alert of summary.criticalAlerts) {
      console.log(`  ⚠️  [${alert.type}] Report ${alert.reportId} (${alert.severity}) in ${alert.appVersion}: ${alert.reason}`);
    }
  }

  console.log('\n====================================================');
  console.log(`Auto Triage Completed Successfully. (Dry-Run: ${summary.dryRun ? 'YES' : 'NO'})`);
  console.log('====================================================');
}

main().catch(err => {
  console.error('Fatal triage error:', err);
  process.exit(1);
});
