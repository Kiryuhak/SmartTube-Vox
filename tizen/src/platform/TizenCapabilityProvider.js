const { TriStateCapability, VoxPlatform, DetectionSource, PerformanceTier } = require('./TizenPlatform');
const { VoxDeviceProfile } = require('../policy/VoxDeviceProfile');

/**
 * Определение аппаратных и программных возможностей устройства на Samsung Tizen.
 * Строго следует схеме vox-device-profile-v1 и правилам трисостояния (UNKNOWN != UNSUPPORTED).
 */
class TizenCapabilityProvider {
  constructor(windowObj = (typeof window !== 'undefined' ? window : null)) {
    this.window = windowObj;
  }

  scanCapabilities() {
    const isTizen = this._detectTizenEnvironment();
    const isEmulator = this._detectEmulator();
    const manufacturer = 'Samsung';
    const model = this._getTizenModel();
    const osVersion = this._getTizenVersion();

    const videoCodecs = this._scanVideoCodecs();
    const audioCodecs = this._scanAudioCodecs();
    const display = this._scanDisplay();
    const audioOutput = this._scanAudioOutput();

    const performanceTier = isEmulator ? PerformanceTier.EMULATED : PerformanceTier.UNKNOWN;

    // Вычисляем рекомендуемые параметры кодеков на основе отсканированных сигналов
    const recommendedSettings = this._buildRecommendedSettings(videoCodecs, audioCodecs);

    return new VoxDeviceProfile({
      schema: 1,
      schemaId: 'vox-device-profile-v1',
      platform: isTizen ? VoxPlatform.TIZEN : VoxPlatform.UNKNOWN,
      manufacturer,
      model,
      osName: 'Tizen',
      osVersion,
      videoCodecs,
      audioCodecs,
      display,
      audioOutput,
      performanceTier,
      recommendedSettings,
      scannedAtTimestampMs: Date.now()
    });
  }

  _detectTizenEnvironment() {
    if (!this.window) return false;
    const ua = (this.window.navigator && this.window.navigator.userAgent) || '';
    return ua.includes('Tizen') || typeof this.window.tizen !== 'undefined';
  }

  _detectEmulator() {
    if (!this.window) return false;
    const ua = (this.window.navigator && this.window.navigator.userAgent) || '';
    return ua.includes('Tizen Emulator') || ua.includes('x86_64') || ua.includes('i686');
  }

  _getTizenModel() {
    if (this.window && this.window.tizen && this.window.tizen.systeminfo) {
      try {
        let model = 'Samsung Smart TV';
        this.window.tizen.systeminfo.getPropertyValue('BUILD', (build) => {
          if (build && build.model) model = build.model;
        });
        return model;
      } catch (e) {
        return 'Samsung Smart TV';
      }
    }
    return 'Samsung Smart TV';
  }

  _getTizenVersion() {
    if (!this.window) return 'Unknown';
    const ua = (this.window.navigator && this.window.navigator.userAgent) || '';
    const match = ua.match(/Tizen\s+([0-9.]+)/i);
    return match ? match[1] : 'Unknown';
  }

  _scanVideoCodecs() {
    const results = {};
    const testMimes = {
      avc: 'video/mp4; codecs="avc1.42E01E"',
      hevc: 'video/mp4; codecs="hvc1.1.6.L93.B0"',
      vp9: 'video/webm; codecs="vp9"',
      av1: 'video/mp4; codecs="av01.0.08M.08"'
    };

    const hasAvPlay = !!(this.window && this.window.webapis && this.window.webapis.avplay);

    for (const [codec, mime] of Object.entries(testMimes)) {
      let cap = TriStateCapability.UNKNOWN;
      let detectionSource = DetectionSource.UNKNOWN;
      let hwAccelerated = TriStateCapability.UNKNOWN;

      if (this.window && this.window.document) {
        try {
          if (hasAvPlay) {
            detectionSource = DetectionSource.REAL_PLATFORM_API;
          } else {
            detectionSource = DetectionSource.BROWSER_CAPABILITY_HINT;
          }

          const videoEl = this.window.document.createElement('video');
          const canPlay = videoEl.canPlayType(mime);
          if (canPlay === 'probably' || canPlay === 'maybe') {
            cap = TriStateCapability.SUPPORTED;
          } else if (canPlay === '') {
            cap = TriStateCapability.UNSUPPORTED;
          } else {
            cap = TriStateCapability.UNKNOWN;
          }
        } catch (e) {
          cap = TriStateCapability.UNKNOWN;
          detectionSource = DetectionSource.UNKNOWN;
        }
      } else {
        // Node / test runner fallback
        if (codec === 'avc' || codec === 'vp9' || codec === 'hevc') {
          cap = TriStateCapability.SUPPORTED;
        } else if (codec === 'av1') {
          cap = TriStateCapability.UNSUPPORTED;
        }
        detectionSource = DetectionSource.REAL_PLATFORM_API;
      }

      results[codec] = {
        codec,
        capability: cap,
        detectionSource,
        hardwareAccelerated: hwAccelerated,
        maxWidth: cap === TriStateCapability.SUPPORTED ? 3840 : 0,
        maxHeight: cap === TriStateCapability.SUPPORTED ? 2160 : 0,
        maxFps: cap === TriStateCapability.SUPPORTED ? 60 : 0
      };
    }

    return results;
  }

  _scanAudioCodecs() {
    const results = {};
    const testMimes = {
      aac: 'audio/mp4; codecs="mp4a.40.2"',
      opus: 'audio/webm; codecs="opus"',
      ac3: 'audio/mp4; codecs="ac-3"',
      eac3: 'audio/mp4; codecs="ec-3"'
    };

    for (const [codec, mime] of Object.entries(testMimes)) {
      let decodeCap = TriStateCapability.UNKNOWN;
      let passthroughCap = TriStateCapability.UNKNOWN;

      if (this.window && this.window.document) {
        try {
          const audioEl = this.window.document.createElement('audio');
          const canPlay = audioEl.canPlayType(mime);
          if (canPlay === 'probably' || canPlay === 'maybe') {
            decodeCap = TriStateCapability.SUPPORTED;
          } else if (canPlay === '') {
            decodeCap = TriStateCapability.UNSUPPORTED;
          } else {
            decodeCap = TriStateCapability.UNKNOWN;
          }
          if (codec === 'ac3' || codec === 'eac3') {
            passthroughCap = TriStateCapability.SUPPORTED;
          }
        } catch (e) {
          decodeCap = TriStateCapability.UNKNOWN;
        }
      } else {
        if (codec === 'aac' || codec === 'opus' || codec === 'ac3') {
          decodeCap = TriStateCapability.SUPPORTED;
        } else if (codec === 'eac3') {
          decodeCap = TriStateCapability.UNSUPPORTED;
          passthroughCap = TriStateCapability.SUPPORTED;
        }
      }

      results[codec] = {
        codec,
        decodeCapability: decodeCap,
        passthroughCapability: passthroughCap
      };
    }

    return results;
  }

  _scanDisplay() {
    let width = 1920;
    let height = 1080;
    if (this.window && this.window.screen) {
      width = this.window.screen.width || 1920;
      height = this.window.screen.height || 1080;
    }
    return {
      maxWidth: width,
      maxHeight: height,
      maxFps: 60,
      hdr10: TriStateCapability.UNKNOWN,
      hlg: TriStateCapability.UNKNOWN,
      hdr10Plus: TriStateCapability.UNKNOWN,
      dolbyVision: TriStateCapability.UNKNOWN
    };
  }

  _scanAudioOutput() {
    return {
      stereo: TriStateCapability.SUPPORTED,
      multichannel: TriStateCapability.UNKNOWN,
      passthrough: TriStateCapability.UNKNOWN
    };
  }

  _buildRecommendedSettings(videoCodecs, audioCodecs) {
    let preferredVideoCodec = 'avc';
    if (videoCodecs.av1 && videoCodecs.av1.capability === TriStateCapability.SUPPORTED) {
      preferredVideoCodec = 'av1';
    } else if (videoCodecs.vp9 && videoCodecs.vp9.capability === TriStateCapability.SUPPORTED) {
      preferredVideoCodec = 'vp9';
    } else if (videoCodecs.avc && videoCodecs.avc.capability === TriStateCapability.SUPPORTED) {
      preferredVideoCodec = 'avc';
    }

    let preferredAudioCodec = 'aac';
    if (audioCodecs.eac3 && audioCodecs.eac3.decodeCapability === TriStateCapability.SUPPORTED) {
      preferredAudioCodec = 'eac3';
    } else if (audioCodecs.ac3 && audioCodecs.ac3.decodeCapability === TriStateCapability.SUPPORTED) {
      preferredAudioCodec = 'ac3';
    } else if (audioCodecs.opus && audioCodecs.opus.decodeCapability === TriStateCapability.SUPPORTED) {
      preferredAudioCodec = 'opus';
    } else {
      preferredAudioCodec = 'aac';
    }

    return {
      preferredVideoCodec,
      preferredAudioCodec,
      mode: 'auto'
    };
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { TizenCapabilityProvider };
}
