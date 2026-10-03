const { TriStateCapability, VoxPlatform } = require('../platform/TizenPlatform');

/**
 * Профиль устройства SmartTube VOX (схема vox-device-profile-v1) для платформы Tizen.
 */
class VoxDeviceProfile {
  constructor(options = {}) {
    this.schema = options.schema || 1;
    this.schemaId = options.schemaId || 'vox-device-profile-v1';
    this.platform = options.platform || VoxPlatform.TIZEN;
    this.manufacturer = options.manufacturer || 'Samsung';
    this.model = options.model || 'Samsung Smart TV';
    this.osName = options.osName || 'Tizen';
    this.osVersion = options.osVersion || 'Unknown';
    this.videoCodecs = options.videoCodecs || {};
    this.audioCodecs = options.audioCodecs || {};
    this.display = options.display || {
      maxWidth: 1920,
      maxHeight: 1080,
      maxFps: 60,
      hdr10: TriStateCapability.UNKNOWN,
      hlg: TriStateCapability.UNKNOWN,
      hdr10Plus: TriStateCapability.UNKNOWN,
      dolbyVision: TriStateCapability.UNKNOWN
    };
    this.audioOutput = options.audioOutput || {
      stereo: TriStateCapability.SUPPORTED,
      multichannel: TriStateCapability.UNKNOWN,
      passthrough: TriStateCapability.UNKNOWN
    };
    this.scannedAtTimestampMs = options.scannedAtTimestampMs || Date.now();
  }

  isVideoCodecSupported(codec) {
    const key = this._normalizeCodec(codec);
    const item = this.videoCodecs[key];
    return item && item.capability === TriStateCapability.SUPPORTED;
  }

  isAudioDecodeSupported(codec) {
    const key = this._normalizeCodec(codec);
    const item = this.audioCodecs[key];
    return item && item.decodeCapability === TriStateCapability.SUPPORTED;
  }

  isAudioPassthroughSupported(codec) {
    const key = this._normalizeCodec(codec);
    const item = this.audioCodecs[key];
    return item && item.passthroughCapability === TriStateCapability.SUPPORTED;
  }

  _normalizeCodec(codec) {
    const lower = (codec || '').toLowerCase();
    if (lower.includes('av01') || lower.includes('av1')) return 'av1';
    if (lower.includes('vp9') || lower.includes('vp09')) return 'vp9';
    if (lower.includes('avc') || lower.includes('h264')) return 'avc';
    if (lower.includes('hevc') || lower.includes('h265')) return 'hevc';
    if (lower.includes('eac3') || lower.includes('ec-3')) return 'eac3';
    if (lower.includes('ac3') || lower.includes('ac-3')) return 'ac3';
    if (lower.includes('opus')) return 'opus';
    if (lower.includes('mp4a') || lower.includes('aac')) return 'aac';
    return lower;
  }

  toJSON() {
    return {
      schema: this.schema,
      schemaId: this.schemaId,
      platform: this.platform,
      manufacturer: this.manufacturer,
      model: this.model,
      osName: this.osName,
      osVersion: this.osVersion,
      video: this.videoCodecs,
      audio: this.audioCodecs,
      display: this.display,
      audioOutput: this.audioOutput,
      scannedAtTimestampMs: this.scannedAtTimestampMs
    };
  }

  static fromJSON(json) {
    if (!json) return null;
    const obj = typeof json === 'string' ? JSON.parse(json) : json;
    return new VoxDeviceProfile({
      schema: obj.schema,
      schemaId: obj.schemaId,
      platform: obj.platform,
      manufacturer: obj.manufacturer,
      model: obj.model,
      osName: obj.osName,
      osVersion: obj.osVersion,
      videoCodecs: obj.video || {},
      audioCodecs: obj.audio || {},
      display: obj.display,
      audioOutput: obj.audioOutput,
      scannedAtTimestampMs: obj.scannedAtTimestampMs
    });
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { VoxDeviceProfile };
}
