const assert = require('assert');
const { VoxCodecPolicy, VoxCodecPolicyMode, VoxVideoCodecPreference, VoxAudioCodecPreference } = require('../src/policy/VoxCodecPolicy');
const { VoxDeviceProfile } = require('../src/policy/VoxDeviceProfile');
const { TriStateCapability } = require('../src/platform/TizenPlatform');

function run() {
  console.log('Testing Tizen Codec Policy & Fallback engine...');

  const profile = new VoxDeviceProfile({
    videoCodecs: {
      avc: { capability: TriStateCapability.SUPPORTED },
      vp9: { capability: TriStateCapability.SUPPORTED },
      av1: { capability: TriStateCapability.UNSUPPORTED }
    },
    audioCodecs: {
      aac: { decodeCapability: TriStateCapability.SUPPORTED, passthroughCapability: TriStateCapability.UNKNOWN },
      opus: { decodeCapability: TriStateCapability.SUPPORTED, passthroughCapability: TriStateCapability.UNKNOWN },
      ac3: { decodeCapability: TriStateCapability.SUPPORTED, passthroughCapability: TriStateCapability.SUPPORTED },
      eac3: { decodeCapability: TriStateCapability.UNSUPPORTED, passthroughCapability: TriStateCapability.SUPPORTED }
    }
  });

  // 1. AUTO: AV1 unsupported -> picks VP9
  const autoPolicy = new VoxCodecPolicy({ mode: VoxCodecPolicyMode.AUTO });
  const videoRes = autoPolicy.selectVideoCodec(['av1', 'vp9', 'avc'], profile);
  assert.strictEqual(videoRes.selected, 'vp9');

  // 2. MAX_COMPATIBILITY -> picks AVC
  const compatPolicy = new VoxCodecPolicy({ mode: VoxCodecPolicyMode.MAX_COMPATIBILITY });
  const compatRes = compatPolicy.selectVideoCodec(['av1', 'vp9', 'avc'], profile);
  assert.strictEqual(compatRes.selected, 'avc');

  // 3. MAX_QUALITY -> picks VP9 (best supported)
  const qualityPolicy = new VoxCodecPolicy({ mode: VoxCodecPolicyMode.MAX_QUALITY });
  const qualityRes = qualityPolicy.selectVideoCodec(['av1', 'vp9', 'avc'], profile);
  assert.strictEqual(qualityRes.selected, 'vp9');

  // 4. CUSTOM with unsupported AV1 -> returns warning
  const customPolicy = new VoxCodecPolicy({
    mode: VoxCodecPolicyMode.CUSTOM,
    preferredVideoCodec: VoxVideoCodecPreference.AV1
  });
  const customRes = customPolicy.selectVideoCodec(['av1', 'vp9', 'avc'], profile);
  assert.strictEqual(customRes.selected, 'av1');
  assert.ok(customRes.warning);
  assert.ok(customRes.warning.includes('не заявлен как поддерживаемый'));

  // 5. Audio: EAC3 passthrough note
  const audioRes = autoPolicy.selectAudioCodec(['eac3', 'ac3', 'opus', 'aac'], profile);
  assert.strictEqual(audioRes.selected, 'eac3');
  assert.ok(audioRes.warning);
  assert.ok(audioRes.warning.includes('только через совместимый аудиовыход'));

  console.log('  ✓ Tizen Codec Policy tests PASS');
}

module.exports = { run };
