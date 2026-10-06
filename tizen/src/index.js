/**
 * Главная точка входа приложения SmartTube VOX для Samsung Tizen.
 */
(function() {
  const isNode = typeof module !== 'undefined' && module.exports;

  let TriStateCapability, VoxPlatform, VoxDeviceProfile, VoxCodecPolicy, VoxCodecPolicyMode;
  let TizenCapabilityProvider, TizenDpadNavigation, TizenFirstRunDialog, TizenDiagnosticsDialog;

  if (isNode) {
    const platformMod = require('./platform/TizenPlatform');
    TriStateCapability = platformMod.TriStateCapability;
    VoxPlatform = platformMod.VoxPlatform;

    VoxDeviceProfile = require('./policy/VoxDeviceProfile').VoxDeviceProfile;
    const policyMod = require('./policy/VoxCodecPolicy');
    VoxCodecPolicy = policyMod.VoxCodecPolicy;
    VoxCodecPolicyMode = policyMod.VoxCodecPolicyMode;

    TizenCapabilityProvider = require('./platform/TizenCapabilityProvider').TizenCapabilityProvider;
    TizenDpadNavigation = require('./ui/TizenDpadNavigation').TizenDpadNavigation;
    TizenFirstRunDialog = require('./ui/TizenFirstRunDialog').TizenFirstRunDialog;
    TizenDiagnosticsDialog = require('./ui/TizenDiagnosticsDialog').TizenDiagnosticsDialog;
  }

  function initApp() {
    if (typeof window === 'undefined' || !window.document) return;

    const modalContainer = document.getElementById('modalContainer');

    const capProvider = new (TizenCapabilityProvider || window.TizenCapabilityProvider || Object)(window);
    const profile = capProvider.scanCapabilities ? capProvider.scanCapabilities() : {};
    const policy = new (VoxCodecPolicy || window.VoxCodecPolicy || Object)();

    // DPAD Navigation
    const nav = new (TizenDpadNavigation || window.TizenDpadNavigation || Object)(document);
    if (nav.init) nav.init();

    // First Run Dialog
    const firstRunKey = 'vox_tizen_scan_completed';
    const isScanCompleted = localStorage.getItem(firstRunKey);

    const firstRunDialog = new (TizenFirstRunDialog || window.TizenFirstRunDialog || Object)({
      container: modalContainer,
      onAutoScan: () => {
        localStorage.setItem(firstRunKey, 'true');
        firstRunDialog.renderResult(profile);
        const btnOk = modalContainer.querySelector('#btnResultOk');
        const btnDiag = modalContainer.querySelector('#btnResultDiagnostics');
        if (btnOk) btnOk.addEventListener('click', () => firstRunDialog.dismiss());
        if (btnDiag) btnDiag.addEventListener('click', () => {
          firstRunDialog.dismiss();
          showDiagnostics();
        });
      },
      onManualConfig: () => {
        localStorage.setItem(firstRunKey, 'true');
        firstRunDialog.dismiss();
        showDiagnostics();
      },
      onLater: () => {
        localStorage.setItem(firstRunKey, 'true');
        firstRunDialog.dismiss();
      }
    });

    if (!isScanCompleted && firstRunDialog.render) {
      firstRunDialog.render();
    }

    function showDiagnostics() {
      const diagDialog = new (TizenDiagnosticsDialog || window.TizenDiagnosticsDialog || Object)({
        container: modalContainer,
        onClose: () => {
          if (nav.refreshFocusables) nav.refreshFocusables();
        }
      });
      if (diagDialog.render) diagDialog.render(profile, policy);
    }

    const btnDiag = document.getElementById('btnNavDiagnostics');
    if (btnDiag) {
      btnDiag.addEventListener('click', showDiagnostics);
    }

    const btnCompat = document.getElementById('btnNavCompatibility');
    if (btnCompat) {
      btnCompat.addEventListener('click', () => {
        if (firstRunDialog.render) firstRunDialog.render();
      });
    }
  }

  if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', initApp);
    } else {
      initApp();
    }
  }

  if (isNode) {
    module.exports = { initApp };
  }
})();
