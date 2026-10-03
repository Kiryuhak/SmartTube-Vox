const assert = require('assert');
const { TizenDiagnosticsDialog } = require('../src/ui/TizenDiagnosticsDialog');
const { VoxDeviceProfile } = require('../src/policy/VoxDeviceProfile');
const { VoxCodecPolicy } = require('../src/policy/VoxCodecPolicy');
const { TriStateCapability } = require('../src/platform/TizenPlatform');

function run() {
  console.log('Testing Tizen Diagnostics Report & Sanitization...');

  const profile = new VoxDeviceProfile({
    manufacturer: 'Samsung',
    model: 'QN90B',
    osName: 'Tizen',
    osVersion: '6.5',
    videoCodecs: {
      avc: { capability: TriStateCapability.SUPPORTED },
      vp9: { capability: TriStateCapability.SUPPORTED },
      av1: { capability: TriStateCapability.UNSUPPORTED }
    },
    audioCodecs: {
      aac: { decodeCapability: TriStateCapability.SUPPORTED, passthroughCapability: TriStateCapability.UNKNOWN },
      ac3: { decodeCapability: TriStateCapability.SUPPORTED, passthroughCapability: TriStateCapability.SUPPORTED }
    },
    display: {
      maxWidth: 3840,
      maxHeight: 2160
    }
  });

  const policy = new VoxCodecPolicy();
  const diag = new TizenDiagnosticsDialog();
  const report = diag.generateReport(profile, policy);

  assert.ok(report.includes('SmartTube VOX — Диагностика Samsung Tizen'));
  assert.ok(report.includes('QN90B'));
  assert.ok(report.includes('3840x2160'));

  // Verify sanitization
  assert.strictEqual(report.includes('access_token'), false);
  assert.strictEqual(report.includes('refresh_token'), false);
  assert.strictEqual(report.includes('Bearer'), false);
  assert.strictEqual(report.includes('password'), false);
  assert.strictEqual(report.includes('cookie'), false);

  console.log('  ✓ Tizen Diagnostics tests PASS');
}

module.exports = { run };
