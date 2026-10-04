const SafeErrorCategory = {
  PLAYBACK_INIT_FAILED: 'PLAYBACK_INIT_FAILED',
  CODEC_UNSUPPORTED: 'CODEC_UNSUPPORTED',
  AUDIO_OUTPUT_UNAVAILABLE: 'AUDIO_OUTPUT_UNAVAILABLE',
  TRANSLATION_TIMEOUT: 'TRANSLATION_TIMEOUT',
  SYNC_DRIFT_HIGH: 'SYNC_DRIFT_HIGH',
  NETWORK_UNAVAILABLE: 'NETWORK_UNAVAILABLE',
  DOWNLOAD_UNSUPPORTED: 'DOWNLOAD_UNSUPPORTED',
  UNKNOWN: 'UNKNOWN'
};

/**
 * Диалог подробной и безопасной диагностики для Samsung Tizen.
 * Предоставляет просмотр, копирование и безопасную отправку анонимного отчёта разработчикам.
 */
class TizenDiagnosticsDialog {
  constructor(options = {}) {
    this.container = options.container || null;
    this.onClose = options.onClose || null;
    this.endpointUrl = options.endpointUrl || 'http://127.0.0.1:8765/v1/report';
    this.lastReportId = null;
  }

  generateReport(profile, policy, errorCategory = SafeErrorCategory.UNKNOWN) {
    const lines = [];
    lines.push('=== SmartTube VOX — Диагностика Samsung Tizen ===\n');
    lines.push(`Платформа: ${profile.platform}`);
    lines.push(`Производитель: ${profile.manufacturer}`);
    lines.push(`Модель: ${profile.model}`);
    lines.push(`Система: ${profile.osName} ${profile.osVersion}`);
    lines.push(`Категория ошибки: ${errorCategory}\n`);

    lines.push('--- ВИДЕОДЕКОДЕРЫ ---');
    for (const [key, cap] of Object.entries(profile.videoCodecs || {})) {
      const status = cap.capability === 'supported' ? 'Поддерживается' : 'Не поддерживается';
      const src = cap.detectionSource ? ` [${cap.detectionSource}]` : '';
      lines.push(`${key.toUpperCase()}: ${status}${src}`);
    }
    lines.push('');

    lines.push('--- АУДИОДЕКОДЕРЫ И PASSTHROUGH ---');
    for (const [key, cap] of Object.entries(profile.audioCodecs || {})) {
      const dec = cap.decodeCapability === 'supported' ? 'Поддерживается' : 'Не поддерживается';
      const pt = cap.passthroughCapability === 'supported' ? 'Поддерживается' : 'Не поддерживается';
      lines.push(`${key.toUpperCase()}: Декод: ${dec}, Passthrough: ${pt}`);
    }
    lines.push('');

    lines.push('--- ЭКРАН ---');
    if (profile.display) {
      lines.push(`Разрешение: ${profile.display.maxWidth}x${profile.display.maxHeight}`);
    }
    lines.push('');

    lines.push('--- РЕКОМЕНДАЦИЯ VOX ---');
    const recVideo = policy ? policy.selectVideoCodec(['av1', 'vp9', 'avc'], profile) : { selected: 'avc' };
    const recAudio = policy ? policy.selectAudioCodec(['eac3', 'ac3', 'opus', 'aac'], profile) : { selected: 'aac' };
    lines.push(`Видеокодек: ${recVideo.selected ? recVideo.selected.toUpperCase() : 'AUTO'}`);
    lines.push(`Аудиокодек: ${recAudio.selected ? recAudio.selected.toUpperCase() : 'AUTO'}`);

    return lines.join('\n');
  }

  generateReportJson(profile, policy, errorCategory = SafeErrorCategory.UNKNOWN) {
    const recVideo = policy ? policy.selectVideoCodec(['av1', 'vp9', 'avc'], profile) : { selected: 'avc' };
    const recAudio = policy ? policy.selectAudioCodec(['eac3', 'ac3', 'opus', 'aac'], profile) : { selected: 'aac' };

    return {
      schema: 'vox-diagnostic-report-v1',
      timestamp: Date.now(),
      appVersion: '32.56-vox.7-dev',
      appVersionCode: 2446007,
      platform: profile.platform || 'Samsung Tizen',
      manufacturer: profile.manufacturer || 'Samsung',
      model: profile.model || 'TizenSmartTV',
      osName: profile.osName || 'Tizen',
      osVersion: profile.osVersion || '7.0',
      sdkInt: 0,
      deviceTier: profile.performanceTier || 'Samsung Tizen Smart TV',
      videoCodecs: profile.videoCodecs || {},
      audioCodecs: profile.audioCodecs || {},
      display: profile.display || {},
      errorCategory: errorCategory,
      currentPolicy: {
        mode: (policy && policy.mode) || 'auto',
        preferredVideoCodec: (policy && policy.preferredVideoCodec) || 'auto',
        preferredAudioCodec: (policy && policy.preferredAudioCodec) || 'auto',
      },
      recommendedSettings: {
        preferredVideoCodec: recVideo.selected || 'auto',
        preferredAudioCodec: recAudio.selected || 'auto',
      },
    };
  }

  render(profile, policy) {
    if (!this.container) return;

    this.container.innerHTML = `
      <div class="vox-dialog-overlay" id="voxDiagnosticsOverlay">
        <div class="vox-dialog-card vox-dialog-large">
          <div class="vox-dialog-header">
            <h2 class="vox-dialog-title">Диагностика совместимости</h2>
          </div>
          <div class="vox-dialog-body" id="voxDiagBody">
            <p class="vox-dialog-subtitle">Выберите действие для отчёта совместимости устройства:</p>
            <div class="vox-diagnostics-actions-list">
              <button class="vox-btn vox-btn-primary" id="btnDiagView" tabindex="1">Посмотреть отчёт</button>
              <button class="vox-btn vox-btn-secondary" id="btnDiagCopy" tabindex="2">Скопировать отчёт</button>
              <button class="vox-btn vox-btn-secondary" id="btnDiagSend" tabindex="3">Отправить разработчику</button>
              <button class="vox-btn vox-btn-flat" id="btnDiagCancel" tabindex="4">Закрыть</button>
            </div>
          </div>
        </div>
      </div>
    `;

    const btnView = this.container.querySelector('#btnDiagView');
    const btnCopy = this.container.querySelector('#btnDiagCopy');
    const btnSend = this.container.querySelector('#btnDiagSend');
    const btnCancel = this.container.querySelector('#btnDiagCancel');

    if (btnView) {
      btnView.addEventListener('click', () => this.showReportView(profile, policy));
      btnView.focus();
    }
    if (btnCopy) {
      btnCopy.addEventListener('click', () => this.copyReport(profile, policy));
    }
    if (btnSend) {
      btnSend.addEventListener('click', () => this.showSendConsentDialog(profile, policy));
    }
    if (btnCancel) {
      btnCancel.addEventListener('click', () => {
        this.dismiss();
        if (this.onClose) this.onClose();
      });
    }
  }

  showReportView(profile, policy) {
    const reportText = this.generateReport(profile, policy);
    const body = this.container.querySelector('#voxDiagBody');
    if (!body) return;

    body.innerHTML = `
      <pre class="vox-diagnostics-pre">${reportText}</pre>
      <div class="vox-dialog-actions">
        <button class="vox-btn vox-btn-secondary" id="btnReportBack" tabindex="1">Назад</button>
        <button class="vox-btn vox-btn-primary" id="btnReportCopy" tabindex="2">Скопировать</button>
      </div>
    `;

    const btnBack = this.container.querySelector('#btnReportBack');
    const btnReportCopy = this.container.querySelector('#btnReportCopy');

    if (btnBack) {
      btnBack.addEventListener('click', () => this.render(profile, policy));
      btnBack.focus();
    }
    if (btnReportCopy) {
      btnReportCopy.addEventListener('click', () => this.copyReport(profile, policy));
    }
  }

  copyReport(profile, policy) {
    const reportText = this.generateReport(profile, policy);
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      navigator.clipboard.writeText(reportText).catch(() => {});
    }
    this.showMessage('Отчёт скопирован в буфер обмена');
  }

  showSendConsentDialog(profile, policy) {
    const body = this.container.querySelector('#voxDiagBody');
    if (!body) return;

    body.innerHTML = `
      <p class="vox-dialog-desc">Отправить анонимный отчёт совместимости разработчикам SmartTube VOX?</p>
      <p class="vox-dialog-subdesc">Отчёт содержит только технические параметры устройства и не включает персональные данные, токены или сетевые адреса.</p>
      <div class="vox-dialog-actions">
        <button class="vox-btn vox-btn-primary" id="btnConfirmSend" tabindex="1">Отправить</button>
        <button class="vox-btn vox-btn-secondary" id="btnCancelSend" tabindex="2">Отмена</button>
      </div>
    `;

    const btnConfirm = this.container.querySelector('#btnConfirmSend');
    const btnCancel = this.container.querySelector('#btnCancelSend');

    if (btnConfirm) {
      btnConfirm.addEventListener('click', () => this.performSend(profile, policy));
      btnConfirm.focus();
    }
    if (btnCancel) {
      btnCancel.addEventListener('click', () => this.render(profile, policy));
    }
  }

  async performSend(profile, policy) {
    const body = this.container.querySelector('#voxDiagBody');
    if (body) {
      body.innerHTML = '<p class="vox-dialog-desc">Отправка диагностического отчёта...</p>';
    }

    const payload = this.generateReportJson(profile, policy);

    try {
      if (typeof fetch === 'undefined') {
        throw new Error('DIRECT_SEND_NOT_CONFIGURED');
      }

      const response = await fetch(this.endpointUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const resJson = await response.json();
      const reportId = resJson.reportId || 'VOX-UNKNOWN';
      this.lastReportId = reportId;

      if (body) {
        body.innerHTML = `
          <div class="vox-dialog-success">
            <h3 class="vox-success-title">Отчёт отправлен. Спасибо!</h3>
            <p class="vox-report-code">Код отчёта: <strong>${reportId}</strong></p>
            <p class="vox-dialog-subdesc">Вы можете сообщить этот код при обращении в поддержку.</p>
            <div class="vox-dialog-actions">
              <button class="vox-btn vox-btn-primary" id="btnCopyReportId" tabindex="1">Скопировать код</button>
              <button class="vox-btn vox-btn-secondary" id="btnSuccessClose" tabindex="2">Закрыть</button>
            </div>
          </div>
        `;

        const btnCopyId = this.container.querySelector('#btnCopyReportId');
        const btnSuccessClose = this.container.querySelector('#btnSuccessClose');

        if (btnCopyId) {
          btnCopyId.addEventListener('click', () => {
            if (typeof navigator !== 'undefined' && navigator.clipboard) {
              navigator.clipboard.writeText(reportId).catch(() => {});
            }
            this.showMessage(`Код отчёта ${reportId} скопирован`);
          });
          btnCopyId.focus();
        }
        if (btnSuccessClose) {
          btnSuccessClose.addEventListener('click', () => {
            this.dismiss();
            if (this.onClose) this.onClose();
          });
        }
      }
    } catch (e) {
      if (body) {
        body.innerHTML = `
          <div class="vox-dialog-error">
            <p class="vox-error-text">Не удалось отправить отчёт (${e.message}).</p>
            <p class="vox-dialog-subdesc">Вы можете скопировать отчёт вручную.</p>
            <div class="vox-dialog-actions">
              <button class="vox-btn vox-btn-primary" id="btnErrorCopy" tabindex="1">Скопировать отчёт</button>
              <button class="vox-btn vox-btn-secondary" id="btnErrorClose" tabindex="2">Закрыть</button>
            </div>
          </div>
        `;

        const btnErrorCopy = this.container.querySelector('#btnErrorCopy');
        const btnErrorClose = this.container.querySelector('#btnErrorClose');

        if (btnErrorCopy) {
          btnErrorCopy.addEventListener('click', () => this.copyReport(profile, policy));
          btnErrorCopy.focus();
        }
        if (btnErrorClose) {
          btnErrorClose.addEventListener('click', () => {
            this.dismiss();
            if (this.onClose) this.onClose();
          });
        }
      }
    }
  }

  showMessage(msg) {
    if (typeof alert !== 'undefined') {
      alert(msg);
    }
  }

  dismiss() {
    if (this.container) {
      this.container.innerHTML = '';
    }
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { TizenDiagnosticsDialog, SafeErrorCategory };
}
