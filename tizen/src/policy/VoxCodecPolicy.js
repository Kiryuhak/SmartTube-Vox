const VoxCodecPolicyMode = {
  AUTO: 'auto',
  MAX_QUALITY: 'max_quality',
  MAX_COMPATIBILITY: 'max_compatibility',
  CUSTOM: 'custom'
};

const VoxVideoCodecPreference = {
  AUTO: 'auto',
  AVC: 'avc',
  VP9: 'vp9',
  AV1: 'av1'
};

const VoxAudioCodecPreference = {
  AUTO: 'auto',
  AAC: 'aac',
  OPUS: 'opus',
  AC3: 'ac3',
  EAC3: 'eac3'
};

/**
 * Единый движок выбора кодеков для Tizen.
 */
class VoxCodecPolicy {
  constructor(options = {}) {
    this.mode = options.mode || VoxCodecPolicyMode.AUTO;
    this.maxQualityHeight = options.maxQualityHeight || 0;
    this.preferredVideoCodec = options.preferredVideoCodec || VoxVideoCodecPreference.AUTO;
    this.preferredAudioCodec = options.preferredAudioCodec || VoxAudioCodecPreference.AUTO;
    this.passthroughEnabled = options.passthroughEnabled !== false;
  }

  selectVideoCodec(availableCodecs = [], profile) {
    if (!availableCodecs || availableCodecs.length === 0) {
      return { selected: null, isFallback: false, warning: null };
    }

    const normalized = availableCodecs.map(c => this._normalizeVideo(c));

    if (this.mode === VoxCodecPolicyMode.MAX_COMPATIBILITY) {
      if (normalized.includes('avc')) {
        return { selected: 'avc', isFallback: false, warning: null };
      }
      if (normalized.includes('vp9') && profile.isVideoCodecSupported('vp9')) {
        return { selected: 'vp9', isFallback: true, warning: null };
      }
      return { selected: normalized[0], isFallback: true, warning: null };
    }

    if (this.mode === VoxCodecPolicyMode.CUSTOM && this.preferredVideoCodec !== VoxVideoCodecPreference.AUTO) {
      const target = this.preferredVideoCodec;
      if (normalized.includes(target)) {
        const isSupported = profile.isVideoCodecSupported(target);
        const warning = !isSupported
          ? 'Формат не заявлен как поддерживаемый устройством. Воспроизведение может не работать.'
          : null;
        return { selected: target, isFallback: !isSupported, warning };
      }
    }

    if (this.mode === VoxCodecPolicyMode.MAX_QUALITY) {
      for (const codec of ['av1', 'vp9', 'avc']) {
        if (normalized.includes(codec) && profile.isVideoCodecSupported(codec)) {
          return { selected: codec, isFallback: false, warning: null };
        }
      }
      const fallback = normalized.includes('avc') ? 'avc' : normalized[0];
      return { selected: fallback, isFallback: true, warning: null };
    }

    // AUTO mode: AV1 -> VP9 -> AVC
    if (normalized.includes('av1') && profile.isVideoCodecSupported('av1')) {
      return { selected: 'av1', isFallback: false, warning: null };
    }
    if (normalized.includes('vp9') && profile.isVideoCodecSupported('vp9')) {
      return { selected: 'vp9', isFallback: false, warning: null };
    }
    if (normalized.includes('avc')) {
      return { selected: 'avc', isFallback: false, warning: null };
    }

    return { selected: normalized[0], isFallback: true, warning: null };
  }

  selectAudioCodec(availableCodecs = [], profile) {
    if (!availableCodecs || availableCodecs.length === 0) {
      return { selected: null, isFallback: false, warning: null };
    }

    const normalized = availableCodecs.map(c => this._normalizeAudio(c));

    if (this.mode === VoxCodecPolicyMode.CUSTOM && this.preferredAudioCodec !== VoxAudioCodecPreference.AUTO) {
      const target = this.preferredAudioCodec;
      if (normalized.includes(target)) {
        const isDecSupported = profile.isAudioDecodeSupported(target);
        const isPtSupported = profile.isAudioPassthroughSupported(target);
        const isSupported = isDecSupported || (this.passthroughEnabled && isPtSupported);
        const warning = !isSupported
          ? 'Формат не заявлен как поддерживаемый устройством. Воспроизведение может не работать.'
          : (!isDecSupported && isPtSupported ? 'EAC3 — доступен только через совместимый аудиовыход' : null);
        return { selected: target, isFallback: !isSupported, warning };
      }
    }

    if (this.mode === VoxCodecPolicyMode.MAX_COMPATIBILITY) {
      if (normalized.includes('aac')) return { selected: 'aac', isFallback: false, warning: null };
      if (normalized.includes('opus') && profile.isAudioDecodeSupported('opus')) {
        return { selected: 'opus', isFallback: false, warning: null };
      }
    }

    // Standard hierarchy: EAC3 -> AC3 -> Opus -> AAC
    if (normalized.includes('eac3')) {
      const dec = profile.isAudioDecodeSupported('eac3');
      const pt = profile.isAudioPassthroughSupported('eac3');
      if (dec || (this.passthroughEnabled && pt)) {
        const warning = (!dec && pt) ? 'EAC3 — доступен только через совместимый аудиовыход' : null;
        return { selected: 'eac3', isFallback: false, warning };
      }
    }

    if (normalized.includes('ac3')) {
      const dec = profile.isAudioDecodeSupported('ac3');
      const pt = profile.isAudioPassthroughSupported('ac3');
      if (dec || (this.passthroughEnabled && pt)) {
        return { selected: 'ac3', isFallback: false, warning: null };
      }
    }

    if (normalized.includes('opus') && profile.isAudioDecodeSupported('opus')) {
      return { selected: 'opus', isFallback: false, warning: null };
    }

    if (normalized.includes('aac')) {
      return { selected: 'aac', isFallback: false, warning: null };
    }

    return { selected: normalized[0], isFallback: true, warning: null };
  }

  _normalizeVideo(codec) {
    const lower = (codec || '').toLowerCase();
    if (lower.includes('av01') || lower.includes('av1')) return 'av1';
    if (lower.includes('vp9') || lower.includes('vp09')) return 'vp9';
    if (lower.includes('avc') || lower.includes('h264')) return 'avc';
    if (lower.includes('hevc') || lower.includes('h265')) return 'hevc';
    return lower;
  }

  _normalizeAudio(codec) {
    const lower = (codec || '').toLowerCase();
    if (lower.includes('eac3') || lower.includes('ec-3')) return 'eac3';
    if (lower.includes('ac3') || lower.includes('ac-3')) return 'ac3';
    if (lower.includes('opus')) return 'opus';
    if (lower.includes('mp4a') || lower.includes('aac')) return 'aac';
    return lower;
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    VoxCodecPolicy,
    VoxCodecPolicyMode,
    VoxVideoCodecPreference,
    VoxAudioCodecPreference
  };
}
