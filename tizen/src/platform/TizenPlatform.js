/**
 * Единая модель платформы и трёхпозиционных возможностей для Tizen.
 */
const TriStateCapability = {
  SUPPORTED: 'supported',
  UNSUPPORTED: 'unsupported',
  UNKNOWN: 'unknown',

  isSupported(val) {
    return val === TriStateCapability.SUPPORTED;
  },
  isUnsupported(val) {
    return val === TriStateCapability.UNSUPPORTED;
  },
  isUnknown(val) {
    return val === TriStateCapability.UNKNOWN;
  },
  fromBoolean(bool) {
    if (bool === true) return TriStateCapability.SUPPORTED;
    if (bool === false) return TriStateCapability.UNSUPPORTED;
    return TriStateCapability.UNKNOWN;
  }
};

const VoxPlatform = {
  ANDROID_TV: 'android_tv',
  GOOGLE_TV: 'google_tv',
  TIZEN: 'tizen',
  UNKNOWN: 'unknown'
};

const DetectionSource = {
  REAL_PLATFORM_API: 'REAL_PLATFORM_API',
  BROWSER_CAPABILITY_HINT: 'BROWSER_CAPABILITY_HINT',
  UNKNOWN: 'UNKNOWN'
};

const PerformanceTier = {
  UNKNOWN: 'UNKNOWN',
  EMULATED: 'EMULATED',
  LOW: 'LOW',
  MID: 'MID',
  HIGH: 'HIGH'
};

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    TriStateCapability,
    VoxPlatform,
    DetectionSource,
    PerformanceTier
  };
}
