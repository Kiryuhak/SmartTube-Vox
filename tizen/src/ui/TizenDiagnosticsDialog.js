const { safeLogger, VoxLogCategory, VoxLogCode } = typeof require !== 'undefined'
  ? require('../diagnostics/TizenSafeLogger')
  : { safeLogger: null, VoxLogCategory: {}, VoxLogCode: {} };

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
 * Диалог подробной и безопасной диагностики и журнала ошибок для Samsung Tizen.
 * Предоставляет просмотр отчёта (v2), журнал ошибок, копирование, очистку
 * и безопасную отправку анонимного отчёта разработчикам с согласия пользователя.
 */
class TizenDiagnosticsDialog {
  constructor(options = {}) {
    this.container = options.container || null;
    this.onClose = options.onClose || null;
    this.endpointUrl = options.endpointUrl || 'https://vox-diagnostics.amn2402.workers.dev/v1/report';
    this.lastReportId = null;
    this.logger = options.logger || safeLogger;
  }

  generateReport(profile, policy, errorCategory = SafeErrorCategory.UNKNOWN) {
    const lines = [];
    lines.push('=== SmartTube VOX — Диагностика Samsung Tizen ===\n');
    lines.push('Платформа: Tizen');
    lines.push(`Производитель: ${profile.manufacturer || 'Samsung'}`);
    lines.push(`Модель: ${profile.model || 'TizenSmartTV'}`);
    lines.push(`Система: ${profile.osName || 'Tizen'} ${profile.osVersion || '7.0'}`);
    lines.push(`Категория ошибки: ${errorCategory}\n`);

    lines.push('--- ВИДЕОДЕКОДЕРЫ ---');
    for (const [key, cap] of Object.entries(profile.videoCodecs || {})) {
      const status = cap.capability === 'supported' ? 'Поддерживается' :
        cap.capability === 'unsupported' ? 'Не поддерживается' : 'Неизвестно';
      const src = cap.detectionSource ? ` [${cap.detectionSource}]` : '';
      lines.push(`${key.toUpperCase()}: ${status}${src}`);
    }
    lines.push('');

    lines.push('--- АУДИОДЕКОДЕРЫ И PASSTHROUGH ---');
    for (const [key, cap] of Object.entries(profile.audioCodecs || {})) {
      const dec = cap.decodeCapability === 'supported' ? 'Поддерживается' :
        cap.decodeCapability === 'unsupported' ? 'Не поддерживается' : 'Неизвестно';
      const pt = cap.passthroughCapability === 'supported' ? 'Поддерживается' :
        cap.passthroughCapability === 'unsupported' ? 'Не поддерживается' : 'Неизвестно';
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

    if (this.logger) {
      const events = this.logger.getRecentEvents(10);
      if (events.length > 0) {
        lines.push('\n--- ПОСЛЕДНИЕ СОБЫТИЯ ЖУРНАЛА ---');
        for (const ev of events) {
          const time = new Date(ev.timestamp).toISOString().substring(11, 19);
          lines.push(`• [${time}] [${ev.category}] ${ev.code}: ${ev.message}`);
        }
      }
    }

    return lines.join('\n');
  }

  generateReportJson(profile, policy, errorCategory = SafeErrorCategory.UNKNOWN) {
    const recVideo = policy ? policy.selectVideoCodec(['av1', 'vp9', 'avc'], profile) : { selected: 'avc' };
    const recAudio = policy ? policy.selectAudioCodec(['eac3', 'ac3', 'opus', 'aac'], profile) : { selected: 'aac' };

    const recentEvents = this.logger ? this.logger.getRecentEvents(50) : [];

    return {
      schema: 'vox-diagnostic-report-v2',
      timestamp: Date.now(),
      appVersion: '32.56-vox.7',
      appVersionCode: 2446008,
      platform: 'Tizen',
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
      safeRecentEvents: recentEvents,
    };
  }

  render(profile, policy) {
    if (!this.container) return;

    const eventCount = this.logger ? this.logger.getEventCount() : 0;
    const journalBtnText = eventCount > 0 ? `Журнал ошибок (${eventCount})` : 'Журнал ошибок';

    this.container.innerHTML = `
      <div class="vox-dialog-overlay" id="voxDiagnosticsOverlay">
        <div class="vox-dialog-card vox-dialog-large">
          <div class="vox-dialog-header">
            <h2 class="vox-dialog-title">Диагностика и логи</h2>
          </div>
          <div class="vox-dialog-body" id="voxDiagBody">
            <p class="vox-dialog-subtitle">Выберите действие для диагностики и журнала событий:</p>
            <div class="vox-diagnostics-actions-list">
              <button class="vox-btn vox-btn-primary" id="btnDiagView" tabindex="1">Посмотреть отчёт</button>
              <button class="vox-btn vox-btn-secondary" id="btnDiagJournal" tabindex="2">${journalBtnText}</button>
              <button class="vox-btn vox-btn-secondary" id="btnDiagCopyJournal" tabindex="3">Скопировать журнал</button>
              <button class="vox-btn vox-btn-secondary" id="btnDiagSend" tabindex="4">Отправить разработчику</button>
              <button class="vox-btn vox-btn-flat" id="btnDiagClearJournal" tabindex="5">Очистить журнал</button>
              <button class="vox-btn vox-btn-flat" id="btnDiagCancel" tabindex="6">Закрыть</button>
            </div>
          </div>
        </div>
      </div>
    `;

    const btnView = this.container.querySelector('#btnDiagView');
    const btnJournal = this.container.querySelector('#btnDiagJournal');
    const btnCopyJournal = this.container.querySelector('#btnDiagCopyJournal');
    const btnSend = this.container.querySelector('#btnDiagSend');
    const btnClear = this.container.querySelector('#btnDiagClearJournal');
    const btnCancel = this.container.querySelector('#btnDiagCancel');

    if (btnView) btnView.addEventListener('click', () => this.viewReport(profile, policy));
    if (btnJournal) btnJournal.addEventListener('click', () => this.viewJournal(profile, policy));
    if (btnCopyJournal) btnCopyJournal.addEventListener('click', () => this.copyJournal());
    if (btnSend) btnSend.addEventListener('click', () => this.showConsentDialog(profile, policy));
    if (btnClear) btnClear.addEventListener('click', () => this.showClearConfirmDialog(profile, policy));
    if (btnCancel) {
      btnCancel.addEventListener('click', () => {
        this.dismiss();
        if (this.onClose) this.onClose();
      });
    }

    if (btnView) btnView.focus();
  }

  viewReport(profile, policy) {
    const reportText = this.generateReport(profile, policy);
    const body = this.container.querySelector('#voxDiagBody');
    if (!body) return;

    body.innerHTML = `
      <div class="vox-dialog-report-view">
        <pre class="vox-report-pre">${reportText}</pre>
        <div class="vox-dialog-actions">
          <button class="vox-btn vox-btn-primary" id="btnReportCopy" tabindex="1">Скопировать отчёт</button>
          <button class="vox-btn vox-btn-secondary" id="btnReportSend" tabindex="2">Отправить разработчику</button>
          <button class="vox-btn vox-btn-flat" id="btnReportBack" tabindex="3">Назад</button>
        </div>
      </div>
    `;

    const btnReportCopy = this.container.querySelector('#btnReportCopy');
    const btnReportSend = this.container.querySelector('#btnReportSend');
    const btnReportBack = this.container.querySelector('#btnReportBack');

    if (btnReportCopy) {
      btnReportCopy.addEventListener('click', () => this.copyReport(profile, policy));
      btnReportCopy.focus();
    }
    if (btnReportSend) btnReportSend.addEventListener('click', () => this.showConsentDialog(profile, policy));
    if (btnReportBack) btnReportBack.addEventListener('click', () => this.render(profile, policy));
  }

  viewJournal(profile, policy) {
    const body = this.container.querySelector('#voxDiagBody');
    if (!body) return;

    const events = this.logger ? this.logger.getEvents() : [];

    if (events.length === 0) {
      body.innerHTML = `
        <div class="vox-dialog-empty-state">
          <p class="vox-dialog-empty-text">Ошибок пока не зафиксировано.</p>
          <div class="vox-dialog-actions">
            <button class="vox-btn vox-btn-primary" id="btnJournalBack" tabindex="1">Назад</button>
          </div>
        </div>
      `;
      const btnBack = this.container.querySelector('#btnJournalBack');
      if (btnBack) {
        btnBack.addEventListener('click', () => this.render(profile, policy));
        btnBack.focus();
      }
      return;
    }

    let itemsHtml = '';
    events.slice(0, 20).forEach((ev, idx) => {
      const timeStr = new Date(ev.timestamp).toISOString().substring(11, 19);
      itemsHtml += `
        <div class="vox-journal-item" tabindex="${idx + 1}">
          <div class="vox-journal-item-header">
            <span class="vox-journal-time">[${timeStr}]</span>
            <span class="vox-journal-level vox-level-${ev.level.toLowerCase()}">[${ev.level}]</span>
            <span class="vox-journal-code">${ev.code}</span>
          </div>
          <div class="vox-journal-item-msg">${ev.message}</div>
        </div>
      `;
    });

    body.innerHTML = `
      <div class="vox-dialog-journal-view">
        <div class="vox-journal-list">${itemsHtml}</div>
        <div class="vox-dialog-actions">
          <button class="vox-btn vox-btn-primary" id="btnCopyAllJournal" tabindex="21">Скопировать журнал</button>
          <button class="vox-btn vox-btn-flat" id="btnClearJournalView" tabindex="22">Очистить</button>
          <button class="vox-btn vox-btn-flat" id="btnJournalBack" tabindex="23">Назад</button>
        </div>
      </div>
    `;

    const btnCopy = this.container.querySelector('#btnCopyAllJournal');
    const btnClear = this.container.querySelector('#btnClearJournalView');
    const btnBack = this.container.querySelector('#btnJournalBack');

    if (btnCopy) btnCopy.addEventListener('click', () => this.copyJournal());
    if (btnClear) btnClear.addEventListener('click', () => this.showClearConfirmDialog(profile, policy));
    if (btnBack) {
      btnBack.addEventListener('click', () => this.render(profile, policy));
      btnBack.focus();
    }
  }

  copyReport(profile, policy) {
    const text = this.generateReport(profile, policy);
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      navigator.clipboard.writeText(text).catch(() => {});
    }
    this.showMessage('Отчёт скопирован в буфер обмена');
  }

  copyJournal() {
    const text = this.logger ? this.logger.getFormattedJournal() : 'Ошибок пока не зафиксировано.';
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      navigator.clipboard.writeText(text).catch(() => {});
    }
    this.showMessage('Журнал скопирован в буфер обмена');
  }

  showClearConfirmDialog(profile, policy) {
    const body = this.container.querySelector('#voxDiagBody');
    if (!body) return;

    body.innerHTML = `
      <div class="vox-dialog-consent">
        <p class="vox-dialog-text">Удалить сохранённый диагностический журнал?</p>
        <div class="vox-dialog-actions">
          <button class="vox-btn vox-btn-primary" id="btnConfirmClear" tabindex="1">Удалить</button>
          <button class="vox-btn vox-btn-flat" id="btnCancelClear" tabindex="2">Отмена</button>
        </div>
      </div>
    `;

    const btnConfirm = this.container.querySelector('#btnConfirmClear');
    const btnCancel = this.container.querySelector('#btnCancelClear');

    if (btnConfirm) {
      btnConfirm.addEventListener('click', () => {
        if (this.logger) {
          this.logger.clearLogs();
        }
        this.showMessage('Журнал ошибок очищен');
        this.render(profile, policy);
      });
      btnConfirm.focus();
    }
    if (btnCancel) btnCancel.addEventListener('click', () => this.render(profile, policy));
  }

  showConsentDialog(profile, policy) {
    const body = this.container.querySelector('#voxDiagBody');
    if (!body) return;

    body.innerHTML = `
      <div class="vox-dialog-consent">
        <h3 class="vox-dialog-subheading">Отправить анонимный диагностический отчёт?</h3>
        <p class="vox-dialog-desc">
          В отчёт входят:<br>
          • версия приложения;<br>
          • модель и версия системы;<br>
          • информация о поддерживаемых кодеках;<br>
          • последние безопасные ошибки VOX.<br><br>
          <em>Не отправляются аккаунты, история просмотров, пароли, токены, cookies и другие персональные данные.</em>
        </p>
        <div class="vox-dialog-actions">
          <button class="vox-btn vox-btn-primary" id="btnConfirmSend" tabindex="1">Отправить</button>
          <button class="vox-btn vox-btn-secondary" id="btnConsentViewReport" tabindex="2">Посмотреть отчёт</button>
          <button class="vox-btn vox-btn-flat" id="btnCancelSend" tabindex="3">Отмена</button>
        </div>
      </div>
    `;

    const btnConfirm = this.container.querySelector('#btnConfirmSend');
    const btnView = this.container.querySelector('#btnConsentViewReport');
    const btnCancel = this.container.querySelector('#btnCancelSend');

    if (btnConfirm) {
      btnConfirm.addEventListener('click', () => this.sendReport(profile, policy));
      btnConfirm.focus();
    }
    if (btnView) btnView.addEventListener('click', () => this.viewReport(profile, policy));
    if (btnCancel) btnCancel.addEventListener('click', () => this.render(profile, policy));
  }

  async sendReport(profile, policy) {
    const body = this.container.querySelector('#voxDiagBody');
    if (body) {
      body.innerHTML = `
        <div class="vox-dialog-sending">
          <p class="vox-dialog-text">Отправка анонимного отчёта...</p>
        </div>
      `;
    }

    const payload = this.generateReportJson(profile, policy);

    try {
      let res;
      if (typeof fetch !== 'undefined') {
        res = await fetch(this.endpointUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        });
      } else {
        throw new Error('DIRECT_SEND_NOT_CONFIGURED');
      }

      if (!res.ok) {
        throw new Error(`HTTP error ${res.status}`);
      }

      const data = await res.json();
      const reportId = data.reportId || 'VOX-OK';
      this.lastReportId = reportId;

      if (this.logger) {
        this.logger.i(VoxLogCategory.DIAGNOSTICS, VoxLogCode.DIAGNOSTIC_SEND_SUCCESS, 'Отчёт успешно отправлен', { reportId });
      }

      if (body) {
        body.innerHTML = `
          <div class="vox-dialog-success">
            <h3 class="vox-dialog-subheading">Отчёт отправлен. Спасибо!</h3>
            <p class="vox-dialog-code-label">Код отчёта:</p>
            <div class="vox-report-id-badge">${reportId}</div>
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
      if (this.logger) {
        this.logger.e(VoxLogCategory.DIAGNOSTICS, VoxLogCode.DIAGNOSTIC_SEND_FAILED, 'Не удалось отправить отчёт', null, e);
      }

      if (body) {
        body.innerHTML = `
          <div class="vox-dialog-error">
            <p class="vox-error-text">Не удалось отправить отчёт (${e.message}).</p>
            <p class="vox-dialog-subdesc">Журнал сохранён на устройстве. Вы можете скопировать его вручную.</p>
            <div class="vox-dialog-actions">
              <button class="vox-btn vox-btn-primary" id="btnErrorRetry" tabindex="1">Повторить</button>
              <button class="vox-btn vox-btn-secondary" id="btnErrorCopy" tabindex="2">Скопировать журнал</button>
              <button class="vox-btn vox-btn-flat" id="btnErrorClose" tabindex="3">Закрыть</button>
            </div>
          </div>
        `;

        const btnRetry = this.container.querySelector('#btnErrorRetry');
        const btnErrorCopy = this.container.querySelector('#btnErrorCopy');
        const btnErrorClose = this.container.querySelector('#btnErrorClose');

        if (btnRetry) {
          btnRetry.addEventListener('click', () => this.sendReport(profile, policy));
          btnRetry.focus();
        }
        if (btnErrorCopy) btnErrorCopy.addEventListener('click', () => this.copyJournal());
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
