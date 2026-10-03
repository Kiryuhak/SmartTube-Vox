/**
 * Диалог первого запуска проверки совместимости VOX для Samsung Tizen.
 */
class TizenFirstRunDialog {
  constructor(options = {}) {
    this.container = options.container || null;
    this.onAutoScan = options.onAutoScan || null;
    this.onManualConfig = options.onManualConfig || null;
    this.onLater = options.onLater || null;
  }

  render() {
    if (!this.container) return;

    this.container.innerHTML = `
      <div class="vox-dialog-overlay" id="voxFirstRunOverlay">
        <div class="vox-dialog-card">
          <div class="vox-dialog-header">
            <h2 class="vox-dialog-title">Проверка совместимости VOX</h2>
          </div>
          <div class="vox-dialog-body">
            <p class="vox-dialog-text">
              SmartTube VOX может автоматически подобрать видео и аудиоформаты,
              которые лучше подходят вашему телевизору Samsung.
            </p>
          </div>
          <div class="vox-dialog-actions">
            <button class="vox-btn vox-btn-primary" id="btnAutoScan" tabindex="1">Проверить автоматически</button>
            <button class="vox-btn" id="btnManualConfig" tabindex="2">Настроить вручную</button>
            <button class="vox-btn" id="btnLater" tabindex="3">Позже</button>
          </div>
        </div>
      </div>
    `;

    this._bindEvents();
  }

  renderResult(profile) {
    if (!this.container) return;

    const avcStatus = profile.videoCodecs.avc?.capability === 'supported' ? 'Поддерживается' : 'Не поддерживается';
    const vp9Status = profile.videoCodecs.vp9?.capability === 'supported' ? 'Поддерживается' : 'Не поддерживается';
    const av1Status = profile.videoCodecs.av1?.capability === 'supported' ? 'Поддерживается' : 'Не поддерживается';

    const aacStatus = profile.audioCodecs.aac?.decodeCapability === 'supported' ? 'Поддерживается' : 'Не поддерживается';
    const opusStatus = profile.audioCodecs.opus?.decodeCapability === 'supported' ? 'Поддерживается' : 'Не поддерживается';
    const ac3Status = profile.audioCodecs.ac3?.decodeCapability === 'supported' ? 'Поддерживается' : 'Не поддерживается';
    const eac3Status = profile.audioCodecs.eac3?.decodeCapability === 'supported' ? 'Поддерживается' : (profile.audioCodecs.eac3?.passthroughCapability === 'supported' ? 'Только passthrough' : 'Не поддерживается');

    this.container.innerHTML = `
      <div class="vox-dialog-overlay" id="voxScanResultOverlay">
        <div class="vox-dialog-card">
          <div class="vox-dialog-header">
            <h2 class="vox-dialog-title">Проверка завершена</h2>
          </div>
          <div class="vox-dialog-body">
            <div class="vox-profile-summary">
              <div class="vox-summary-item"><strong>Устройство:</strong> ${profile.manufacturer} ${profile.model}</div>
              <div class="vox-summary-item"><strong>Режим:</strong> Автоматически</div>
              <div class="vox-summary-subtitle">Видео:</div>
              <div class="vox-codec-item">• AVC — ${avcStatus}</div>
              <div class="vox-codec-item">• VP9 — ${vp9Status}</div>
              <div class="vox-codec-item">• AV1 — ${av1Status}</div>
              <div class="vox-summary-subtitle">Аудио:</div>
              <div class="vox-codec-item">• AAC — ${aacStatus}</div>
              <div class="vox-codec-item">• Opus — ${opusStatus}</div>
              <div class="vox-codec-item">• AC3 — ${ac3Status}</div>
              <div class="vox-codec-item">• EAC3 — ${eac3Status}</div>
            </div>
          </div>
          <div class="vox-dialog-actions">
            <button class="vox-btn vox-btn-primary" id="btnResultOk" tabindex="1">Понятно</button>
            <button class="vox-btn" id="btnResultDiagnostics" tabindex="2">Диагностика</button>
          </div>
        </div>
      </div>
    `;
  }

  _bindEvents() {
    const btnAuto = this.container.querySelector('#btnAutoScan');
    const btnManual = this.container.querySelector('#btnManualConfig');
    const btnLater = this.container.querySelector('#btnLater');

    if (btnAuto && this.onAutoScan) btnAuto.addEventListener('click', this.onAutoScan);
    if (btnManual && this.onManualConfig) btnManual.addEventListener('click', this.onManualConfig);
    if (btnLater && this.onLater) btnLater.addEventListener('click', this.onLater);
  }

  dismiss() {
    if (this.container) {
      this.container.innerHTML = '';
    }
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { TizenFirstRunDialog };
}
