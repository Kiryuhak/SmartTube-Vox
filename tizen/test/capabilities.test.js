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
  assert.strictEqual(profile.manufacturer, 'Samsung');
  assert.strictEqual(profile.isVideoCodecSupported('avc'), true);
  assert.strictEqual(profile.isVideoCodecSupported('vp9'), true);
  assert.strictEqual(profile.isVideoCodecSupported('av1'), false);

  assert.strictEqual(profile.isAudioDecodeSupported('ac3'), true);
  assert.strictEqual(profile.isAudioDecodeSupported('eac3'), false);
  assert.strictEqual(profile.isAudioPassthroughSupported('eac3'), true);

  console.log('  ✓ Tizen capabilities & Tri-state tests PASS');
}

module.exports = { run };
