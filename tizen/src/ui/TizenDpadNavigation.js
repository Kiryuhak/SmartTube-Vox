/**
 * Обработчик навигации пультом ДУ (DPAD) для Tizen TV и Desktop Preview.
 */
const TizenKeyCodes = {
  // Samsung Tizen TV Remote Keys
  TIZEN_LEFT: 37,
  TIZEN_UP: 38,
  TIZEN_RIGHT: 39,
  TIZEN_DOWN: 40,
  TIZEN_ENTER: 13,
  TIZEN_BACK: 10009, // Tizen TV Return key
  ESCAPE: 27,        // Desktop preview Back key

  // Media Keys
  PLAY: 415,
  PAUSE: 19,
  STOP: 413,
  FAST_FORWARD: 417,
  REWIND: 412
};

class TizenDpadNavigation {
  constructor(documentObj = (typeof document !== 'undefined' ? document : null)) {
    this.document = documentObj;
    this.focusIndex = 0;
    this.focusableElements = [];
    this.onBackCallback = null;
    this.onEnterCallback = null;
  }

  init() {
    this.refreshFocusables();
    if (this.document) {
      this.document.addEventListener('keydown', this.handleKeyDown.bind(this));
    }
  }

  refreshFocusables() {
    if (!this.document) return;
    this.focusableElements = Array.from(
      this.document.querySelectorAll('button:not([disabled]), [tabindex]:not([tabindex="-1"])')
    );
    if (this.focusableElements.length > 0 && !this.document.activeElement) {
      this.focusIndex = 0;
      this.focusableElements[0].focus();
    }
  }

  handleKeyDown(event) {
    const keyCode = event.keyCode;

    switch (keyCode) {
      case TizenKeyCodes.TIZEN_LEFT:
      case TizenKeyCodes.TIZEN_UP:
        event.preventDefault();
        this.moveFocus(-1);
        break;

      case TizenKeyCodes.TIZEN_RIGHT:
      case TizenKeyCodes.TIZEN_DOWN:
        event.preventDefault();
        this.moveFocus(1);
        break;

      case TizenKeyCodes.TIZEN_ENTER:
        if (this.onEnterCallback) {
          this.onEnterCallback(this.getCurrentFocusedElement());
        }
        break;

      case TizenKeyCodes.TIZEN_BACK:
      case TizenKeyCodes.ESCAPE:
        event.preventDefault();
        if (this.onBackCallback) {
          this.onBackCallback();
        }
        break;
    }
  }

  moveFocus(delta) {
    this.refreshFocusables();
    if (this.focusableElements.length === 0) return;

    this.focusIndex = (this.focusIndex + delta + this.focusableElements.length) % this.focusableElements.length;
    const target = this.focusableElements[this.focusIndex];
    if (target && typeof target.focus === 'function') {
      target.focus();
    }
  }

  getCurrentFocusedElement() {
    return this.focusableElements[this.focusIndex] || null;
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { TizenDpadNavigation, TizenKeyCodes };
}
