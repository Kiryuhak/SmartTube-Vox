const assert = require('assert');
const { TriStateCapability, VoxPlatform } = require('../src/platform/TizenPlatform');
const { TizenCapabilityProvider } = require('../src/platform/TizenCapabilityProvider');

function run() {
  console.log('Testing Tizen Capabilities & Tri-state semantics...');

  // 1. Tri-state tests
  assert.strictEqual(TriStateCapability.isSupported(TriStateCapability.SUPPORTED), true);
  assert.strictEqual(TriStateCapability.isSupported(TriStateCapability.UNSUPPORTED), false);
  assert.strictEqual(TriStateCapability.isSupported(TriStateCapability.UNKNOWN), false);

  assert.strictEqual(TriStateCapability.isUnknown(TriStateCapability.UNKNOWN), true);
  assert.notStrictEqual(TriStateCapability.UNKNOWN, TriStateCapability.UNSUPPORTED);

  // 2. Capability provider scan in Node environment
  const provider = new TizenCapabilityProvider();
  const profile = provider.scanCapabilities();

  assert.strictEqual(profile.schema, 1);
  assert.strictEqual(profile.schemaId, 'vox-device-profile-v1');
  assert.strictEqual(profile.manufacturer, 'Unknown');
  assert.strictEqual(profile.display.maxWidth, 0);
  assert.strictEqual(profile.audioOutput.stereo, TriStateCapability.UNKNOWN);
  assert.strictEqual(profile.platform, VoxPlatform.UNKNOWN);
  assert.strictEqual(profile.videoCodecs.avc.capability, TriStateCapability.UNKNOWN);
  assert.strictEqual(profile.videoCodecs.vp9.capability, TriStateCapability.UNKNOWN);
  assert.strictEqual(profile.isVideoCodecSupported('av1'), false);

  assert.strictEqual(profile.audioCodecs.ac3.decodeCapability, TriStateCapability.UNKNOWN);
  assert.strictEqual(profile.isAudioDecodeSupported('eac3'), false);
  assert.strictEqual(profile.audioCodecs.eac3.passthroughCapability, TriStateCapability.UNKNOWN);

  console.log('  ✓ Tizen capabilities & Tri-state tests PASS');
}

module.exports = { run };
