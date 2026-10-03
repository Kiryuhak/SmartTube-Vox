/**
 * Диалог подробной и безопасной диагностики для Samsung Tizen.
 */
class TizenDiagnosticsDialog {
  constructor(options = {}) {
    this.container = options.container || null;
    this.onClose = options.onClose || null;
  }

  generateReport(profile, policy) {
    const lines = [];
    lines.push('=== SmartTube VOX — Диагностика Samsung Tizen ===\n');
    lines.push(`Платформа: ${profile.platform}`);
    lines.push(`Производитель: ${profile.manufacturer}`);
    lines.push(`Модель: ${profile.model}`);
    lines.push(`Система: ${profile.osName} ${profile.osVersion}\n`);

    lines.push('--- ВИДЕОДЕКОДЕРЫ ---');
    for (const [key, cap] of Object.entries(profile.videoCodecs)) {
      const status = cap.capability === 'supported' ? 'Поддерживается' : 'Не поддерживается';
      lines.push(`${key.toUpperCase()}: ${status}`);
    }
    lines.push('');

    lines.push('--- АУДИОДЕКОДЕРЫ И PASSTHROUGH ---');
    for (const [key, cap] of Object.entries(profile.audioCodecs)) {
      const dec = cap.decodeCapability === 'supported' ? 'Поддерживается' : 'Не поддерживается';
      const pt = cap.passthroughCapability === 'supported' ? 'Поддерживается' : 'Не поддерживается';
      lines.push(`${key.toUpperCase()}: Декод: ${dec}, Passthrough: ${pt}`);
    }
    lines.push('');

    lines.push('--- ЭКРАН ---');
    lines.push(`Разрешение: ${profile.display.maxWidth}x${profile.display.maxHeight}`);
    lines.push('');

    lines.push('--- РЕКОМЕНДАЦИЯ VOX ---');
    const recVideo = policy.selectVideoCodec(['av1', 'vp9', 'avc'], profile);
    const recAudio = policy.selectAudioCodec(['eac3', 'ac3', 'opus', 'aac'], profile);
    lines.push(`Видеокодек: ${recVideo.selected ? recVideo.selected.toUpperCase() : 'AUTO'}`);
    lines.push(`Аудиокодек: ${recAudio.selected ? recAudio.selected.toUpperCase() : 'AUTO'}`);

    return lines.join('\n');
  }

  render(profile, policy) {
    if (!this.container) return;

    const reportText = this.generateReport(profile, policy);

    this.container.innerHTML = `
      <div class="vox-dialog-overlay" id="voxDiagnosticsOverlay">
        <div class="vox-dialog-card vox-dialog-large">
          <div class="vox-dialog-header">
            <h2 class="vox-dialog-title">Диагностика совместимости</h2>
          </div>
          <div class="vox-dialog-body">
            <pre class="vox-diagnostics-pre">${reportText}</pre>
          </div>
          <div class="vox-dialog-actions">
            <button class="vox-btn vox-btn-primary" id="btnDiagClose" tabindex="1">Закрыть</button>
          </div>
        </div>
      </div>
    `;

    const btnClose = this.container.querySelector('#btnDiagClose');
    if (btnClose) {
      btnClose.addEventListener('click', () => {
        this.dismiss();
        if (this.onClose) this.onClose();
      });
      btnClose.focus();
    }
  }

  dismiss() {
    if (this.container) {
      this.container.innerHTML = '';
    }
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { TizenDiagnosticsDialog };
}
