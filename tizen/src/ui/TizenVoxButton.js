/**
 * Состояния кнопки VOX Перевод на панели управления плеера Tizen.
 */
const TizenVoxButtonState = {
  IDLE: 'idle',
  REQUESTING: 'requesting',
  READY: 'ready',
  PLAYING: 'playing',
  ERROR: 'error'
};

class TizenVoxButton {
  constructor(options = {}) {
    this.container = options.container || null;
    this.state = options.initialState || TizenVoxButtonState.IDLE;
    this.onClick = options.onClick || null;
    this.buttonElement = null;
  }

  setState(newState) {
    this.state = newState;
    this.update();
  }

  getState() {
    return this.state;
  }

  render() {
    if (!this.container) return;

    this.container.innerHTML = `
      <button class="vox-player-btn" id="btnVoxTranslate" tabindex="10">
        <span class="vox-btn-icon">🗣️</span>
        <span class="vox-btn-label">${this._getLabel()}</span>
      </button>
    `;

    this.buttonElement = this.container.querySelector('#btnVoxTranslate');
    if (this.buttonElement && this.onClick) {
      this.buttonElement.addEventListener('click', () => this.onClick(this.state));
    }
  }

  update() {
    if (!this.buttonElement) return;

    const labelEl = this.buttonElement.querySelector('.vox-btn-label');
    if (labelEl) {
      labelEl.textContent = this._getLabel();
    }

    this.buttonElement.className = `vox-player-btn vox-state-${this.state}`;
  }

  _getLabel() {
    switch (this.state) {
      case TizenVoxButtonState.REQUESTING:
        return 'Переводим...';
      case TizenVoxButtonState.READY:
        return 'Перевод готов';
      case TizenVoxButtonState.PLAYING:
        return 'Перевод включён';
      case TizenVoxButtonState.ERROR:
        return 'Ошибка перевода';
      case TizenVoxButtonState.IDLE:
      default:
        return 'Перевести';
    }
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { TizenVoxButton, TizenVoxButtonState };
}
