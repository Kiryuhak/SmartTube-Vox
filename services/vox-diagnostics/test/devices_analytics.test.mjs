import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ReportStorage } from '../src/storage.mjs';

test('Devices & Analytics Gate 1: Installation tracking and model grouping', async () => {
  const storage = new ReportStorage();
  const now = Date.now();

  // Create 3 installations for "Mi Box S"
  // Installation A: 2 reports
  await storage.saveReport({
    reportId: 'VOX-MI-A1',
    installationId: 'inst-mi-a',
    model: 'Mi Box S',
    manufacturer: 'Xiaomi',
    appVersion: '32.56-vox.9.1',
    status: 'NEW',
    timestamp: now - 3600000,
  });
  await storage.saveReport({
    reportId: 'VOX-MI-A2',
    installationId: 'inst-mi-a',
    model: 'Mi Box S',
    manufacturer: 'Xiaomi',
    appVersion: '32.56-vox.9.1',
    status: 'INVESTIGATING',
    timestamp: now - 1800000,
  });

  // Installation B: 1 report
  await storage.saveReport({
    reportId: 'VOX-MI-B1',
    installationId: 'inst-mi-b',
    model: 'Mi Box S',
    manufacturer: 'Xiaomi',
    appVersion: '32.56-vox.9.2',
    status: 'NEW',
    timestamp: now - 7200000,
  });

  // Installation C: 1 report
  await storage.saveReport({
    reportId: 'VOX-MI-C1',
    installationId: 'inst-mi-c',
    model: 'Mi Box S',
    manufacturer: 'Xiaomi',
    appVersion: '32.56-vox.9.1',
    status: 'NEW',
    timestamp: now - 90000000,
  });

  // Another model: Google TV Streamer with 1 installation
  await storage.saveReport({
    reportId: 'VOX-GTV-01',
    installationId: 'inst-gtv-1',
    model: 'Google TV Streamer',
    manufacturer: 'Google',
    appVersion: '32.56-vox.9.2',
    status: 'NEW',
    timestamp: now - 500000,
  });

  const devicesData = await storage.getDevices();
  assert.ok(devicesData.models, 'Must return models map or list');

  const miBoxGroup = devicesData.models.find(m => m.model === 'Mi Box S');
  assert.ok(miBoxGroup, 'Mi Box S group must exist');
  assert.equal(miBoxGroup.installationsCount, 3, 'Must have 3 unique installations for Mi Box S');
  assert.equal(miBoxGroup.reportsCount, 4, 'Must have 4 total reports for Mi Box S');

  const instA = miBoxGroup.installations.find(i => i.installationId === 'inst-mi-a');
  assert.ok(instA, 'Installation A must be present in Mi Box S');
  assert.equal(instA.reportsCount, 2);
  assert.equal(instA.activeInvestigationsCount, 1);
});

test('Devices & Analytics Gate 2: Reports without installationId are not merged into a dummy device', async () => {
  const storage = new ReportStorage();
  const now = Date.now();

  // 2 legacy reports with no installationId
  await storage.saveReport({
    reportId: 'VOX-LEGACY-01',
    model: 'SEI Robotics KP1',
    manufacturer: 'SEI',
    appVersion: '32.56-vox.9.0',
    status: 'NEW',
    timestamp: now - 1000000,
  });
  await storage.saveReport({
    reportId: 'VOX-LEGACY-02',
    model: 'Dune HD Homatics',
    manufacturer: 'Dune HD',
    appVersion: '32.56-vox.9.1',
    status: 'INVESTIGATING',
    timestamp: now - 500000,
  });

  const devicesData = await storage.getDevices();
  assert.equal(devicesData.unassignedReportsCount, 2, 'Must record 2 unassigned legacy reports');
  assert.ok(devicesData.unassignedReports.some(r => r.reportId === 'VOX-LEGACY-01'));
  assert.ok(devicesData.unassignedReports.some(r => r.reportId === 'VOX-LEGACY-02'));
  // Ensure legacy reports are not given a fake shared installationId
  assert.ok(!devicesData.models.some(m => m.model.includes('fake_dummy_device')));
});

test('Devices & Analytics Gate 3: Heartbeat records activity and online estimation (45m window)', async () => {
  const storage = new ReportStorage();
  const now = Date.now();

  // Send heartbeat for online install
  await storage.recordHeartbeat({
    installationId: 'inst-active-online',
    appVersion: '32.56-vox.9.2',
    platform: 'Android TV',
    manufacturer: 'Nvidia',
    model: 'Shield TV Pro',
    sdkInt: 30,
    timestamp: now - (10 * 60 * 1000), // 10 minutes ago -> ONLINE (< 45m)
  });

  // Another install active 2 hours ago -> OFFLINE (> 45m)
  await storage.recordHeartbeat({
    installationId: 'inst-offline',
    appVersion: '32.56-vox.9.2',
    platform: 'Android TV',
    manufacturer: 'Nvidia',
    model: 'Shield TV Pro',
    sdkInt: 30,
    timestamp: now - (120 * 60 * 1000), // 2 hours ago -> OFFLINE
  });

  const analytics1d = await storage.getAnalytics({ period: '1d', now });
  assert.equal(analytics1d.activeNow, 1, 'Only inst-active-online should be counted in activeNow (45m window)');
  assert.equal(analytics1d.activeInPeriod, 2, 'Both should be counted in 24h active period');
});

test('Devices & Analytics Gate 4: Analytics time periods (1d, 3d, 7d, 30d, 90d, all)', async () => {
  const storage = new ReportStorage();
  const now = Date.now();
  const DAY_MS = 24 * 3600 * 1000;

  // Install 1: 12 hours ago
  await storage.recordHeartbeat({
    installationId: 'inst-today',
    appVersion: '32.56-vox.9.2',
    model: 'Chromecast',
    timestamp: now - (12 * 3600 * 1000),
  });

  // Install 2: 2 days ago
  await storage.recordHeartbeat({
    installationId: 'inst-2days',
    appVersion: '32.56-vox.9.2',
    model: 'Chromecast',
    timestamp: now - (2 * DAY_MS),
  });

  // Install 3: 5 days ago
  await storage.recordHeartbeat({
    installationId: 'inst-5days',
    appVersion: '32.56-vox.9.1',
    model: 'Chromecast',
    timestamp: now - (5 * DAY_MS),
  });

  // Install 4: 20 days ago
  await storage.recordHeartbeat({
    installationId: 'inst-20days',
    appVersion: '32.56-vox.9.0',
    model: 'Chromecast',
    timestamp: now - (20 * DAY_MS),
  });

  const p1d = await storage.getAnalytics({ period: '1d', now });
  assert.equal(p1d.activeInPeriod, 1);

  const p3d = await storage.getAnalytics({ period: '3d', now });
  assert.equal(p3d.activeInPeriod, 2);

  const p7d = await storage.getAnalytics({ period: '7d', now });
  assert.equal(p7d.activeInPeriod, 3);

  const p30d = await storage.getAnalytics({ period: '30d', now });
  assert.equal(p30d.activeInPeriod, 4);

  const p90d = await storage.getAnalytics({ period: '90d', now });
  assert.equal(p90d.activeInPeriod, 4);

  assert.ok(Array.isArray(p7d.timeline), 'Must return daily timeline array');
  assert.ok(p7d.versionDistribution, 'Must return version distribution');
});

test('Devices & Analytics Gate 5: Version upgrade detection and transitions', async () => {
  const storage = new ReportStorage();
  const now = Date.now();

  // Install seen on 9.1 first, then upgraded to 9.2
  await storage.recordInstallationActivity({
    installationId: 'inst-upgrader',
    appVersion: '32.56-vox.9.1',
    model: 'Mi Box 4K',
    timestamp: now - (48 * 3600 * 1000),
  });
  await storage.recordInstallationActivity({
    installationId: 'inst-upgrader',
    appVersion: '32.56-vox.9.2',
    model: 'Mi Box 4K',
    timestamp: now - (2 * 3600 * 1000),
  });

  const analytics = await storage.getAnalytics({ period: '7d', now });
  assert.equal(analytics.upgradedInstallations, 1);
  assert.ok(analytics.versionTransitions.some(t => t.from === '32.56-vox.9.1' && t.to === '32.56-vox.9.2'));
});

test('Devices & Analytics Gate 6: Error rate calculation and error distribution', async () => {
  const storage = new ReportStorage();
  const now = Date.now();

  // 2 installations, 1 with errors and 1 healthy
  await storage.saveReport({
    reportId: 'VOX-ERR-01',
    installationId: 'inst-faulty',
    model: 'Fire TV Stick',
    errorCategory: 'PLAYER',
    status: 'NEW',
    timestamp: now - 3600000,
  });
  await storage.recordHeartbeat({
    installationId: 'inst-healthy',
    model: 'Fire TV Stick',
    appVersion: '32.56-vox.9.2',
    timestamp: now - 1800000,
  });

  const analytics = await storage.getAnalytics({ period: '1d', now });
  assert.equal(analytics.activeInPeriod, 2);
  assert.equal(analytics.installationsWithErrors, 1);
  assert.equal(analytics.errorRatePercent, 50);
  assert.equal(analytics.errorCategoryDistribution.PLAYER, 1);
});
