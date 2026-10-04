const { TizenPlayerState, TizenPlayerBackend } = require('./TizenPlayerState');

/**
 * Адаптер нативного воспроизведения через Samsung Tizen AVPlay API (webapis.avplay).
 * Clean-room реализация для телевизоров Samsung Tizen.
 */
class TizenAvPlayAdapter {
  constructor(options = {}) {
    this.webapis = options.webapis || (typeof window !== 'undefined' && window.webapis ? window.webapis : null);
    this.listener = options.listener || null;
    this.state = TizenPlayerState.IDLE;
    this.url = null;
    this.durationMs = 0;
    this.currentPositionMs = 0;
  }

  isAvailable() {
    return !!(this.webapis && this.webapis.avplay);
  }

  open(url) {
    if (!url) return false;
    this.url = url;
    if (this.isAvailable()) {
      try {
        this.webapis.avplay.open(url);
        this._setupAvPlayListeners();
      } catch (e) {
        this._setState(TizenPlayerState.ERROR, e.message);
        return false;
      }
    }
    this._setState(TizenPlayerState.IDLE);
    return true;
  }

  prepare() {
    if (!this.url) return false;
    this._setState(TizenPlayerState.PREPARING);
    if (this.isAvailable()) {
      try {
        this.webapis.avplay.prepare();
        this.durationMs = this.webapis.avplay.getDuration();
        this._setState(TizenPlayerState.READY);
        return true;
      } catch (e) {
        this._setState(TizenPlayerState.ERROR, e.message);
        return false;
      }
    }
    this.durationMs = 0;
    this._setState(TizenPlayerState.READY);
    return true;
  }

  prepareAsync(onSuccess, onError) {
    if (!this.url) {
      if (onError) onError('No URL provided');
      return;
    }
    this._setState(TizenPlayerState.PREPARING);
    if (this.isAvailable()) {
      try {
        this.webapis.avplay.prepareAsync(
          () => {
            this.durationMs = this.webapis.avplay.getDuration();
            this._setState(TizenPlayerState.READY);
            if (onSuccess) onSuccess();
          },
          (err) => {
            this._setState(TizenPlayerState.ERROR, err);
            if (onError) onError(err);
          }
        );
        return;
      } catch (e) {
        this._setState(TizenPlayerState.ERROR, e.message);
        if (onError) onError(e.message);
        return;
      }
    }
    this._setState(TizenPlayerState.READY);
    if (onSuccess) onSuccess();
  }

  play() {
    if (this.state === TizenPlayerState.IDLE || this.state === TizenPlayerState.ERROR) {
      return false;
    }
    if (this.isAvailable()) {
      try {
        this.webapis.avplay.play();
      } catch (e) {
        this._setState(TizenPlayerState.ERROR, e.message);
        return false;
      }
    }
    this._setState(TizenPlayerState.PLAYING);
    return true;
  }

  pause() {
    if (this.state !== TizenPlayerState.PLAYING && this.state !== TizenPlayerState.BUFFERING) {
      return false;
    }
    if (this.isAvailable()) {
      try {
        this.webapis.avplay.pause();
      } catch (e) {
        this._setState(TizenPlayerState.ERROR, e.message);
        return false;
      }
    }
    this._setState(TizenPlayerState.PAUSED);
    return true;
  }

  seek(positionMs) {
    const targetMs = Math.max(0, positionMs);
    this.currentPositionMs = targetMs;
    if (this.isAvailable()) {
      try {
        this.webapis.avplay.seekTo(targetMs, () => {}, () => {});
      } catch (e) {
        return false;
      }
    }
    return true;
  }

  stop() {
    if (this.isAvailable()) {
      try {
        this.webapis.avplay.stop();
      } catch (e) {}
    }
    this._setState(TizenPlayerState.IDLE);
    this.currentPositionMs = 0;
    return true;
  }

  close() {
    if (this.isAvailable()) {
      try {
        this.webapis.avplay.close();
      } catch (e) {}
    }
    this._setState(TizenPlayerState.IDLE);
    this.url = null;
    return true;
  }

  getCurrentTime() {
    if (this.isAvailable()) {
      try {
        return this.webapis.avplay.getCurrentTime();
      } catch (e) {
        return this.currentPositionMs;
      }
    }
    return this.currentPositionMs;
  }

  getDuration() {
    if (this.isAvailable()) {
      try {
        return this.webapis.avplay.getDuration();
      } catch (e) {
        return this.durationMs;
      }
    }
    return this.durationMs;
  }

  setDisplayRect(x, y, width, height) {
    if (this.isAvailable()) {
      try {
        this.webapis.avplay.setDisplayRect(x, y, width, height);
        return true;
      } catch (e) {
        return false;
      }
    }
    return true;
  }

  setListener(listener) {
    this.listener = listener;
  }

  getState() {
    return this.state;
  }

  getBackendName() {
    return TizenPlayerBackend.AVPLAY;
  }

  _setState(newState, errorInfo = null) {
    this.state = newState;
    if (this.listener && this.listener.onStateChange) {
      this.listener.onStateChange(newState, errorInfo);
    }
  }

  _setupAvPlayListeners() {
    if (!this.isAvailable()) return;
    const avplayListener = {
      onbufferingstart: () => {
        this._setState(TizenPlayerState.BUFFERING);
      },
      onbufferingcomplete: () => {
        this._setState(TizenPlayerState.PLAYING);
      },
      oncurrentplaytime: (currentTimeMs) => {
        this.currentPositionMs = currentTimeMs;
        if (this.listener && this.listener.onTimeUpdate) {
          this.listener.onTimeUpdate(currentTimeMs);
        }
      },
      onerror: (eventType) => {
        this._setState(TizenPlayerState.ERROR, `AVPlay error: ${eventType}`);
      },
      onstreamcompleted: () => {
        this._setState(TizenPlayerState.ENDED);
      }
    };
    try {
      this.webapis.avplay.setListener(avplayListener);
    } catch (e) {}
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { TizenAvPlayAdapter };
}
