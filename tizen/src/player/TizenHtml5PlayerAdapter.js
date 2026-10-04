const { TizenPlayerState, TizenPlayerBackend } = require('./TizenPlayerState');

/**
 * Адаптер воспроизведения через стандартный HTML5 Video Element / MSE.
 * Используется как надёжный Fallback, если AVPlay недоступен (десктоп, веб-превью, эмулятор).
 */
class TizenHtml5PlayerAdapter {
  constructor(options = {}) {
    this.videoElement = options.videoElement || (typeof document !== 'undefined' ? document.createElement('video') : null);
    this.listener = options.listener || null;
    this.state = TizenPlayerState.IDLE;
    this.url = null;
    this.currentPositionMs = 0;
    this.durationMs = 0;
    this._attachDomEvents();
  }

  isAvailable() {
    return !!this.videoElement;
  }

  open(url) {
    if (!url) return false;
    this.url = url;
    if (this.videoElement) {
      this.videoElement.src = url;
      this.videoElement.load();
    }
    this._setState(TizenPlayerState.IDLE);
    return true;
  }

  prepare() {
    if (!this.url) return false;
    this._setState(TizenPlayerState.PREPARING);
    if (this.videoElement) {
      this.durationMs = (this.videoElement.duration || 0) * 1000;
    }
    this._setState(TizenPlayerState.READY);
    return true;
  }

  prepareAsync(onSuccess, onError) {
    if (!this.url) {
      if (onError) onError('No URL provided');
      return;
    }
    this._setState(TizenPlayerState.PREPARING);
    if (this.videoElement) {
      const onLoaded = () => {
        this.durationMs = (this.videoElement.duration || 0) * 1000;
        this._setState(TizenPlayerState.READY);
        this.videoElement.removeEventListener('loadedmetadata', onLoaded);
        if (onSuccess) onSuccess();
      };
      this.videoElement.addEventListener('loadedmetadata', onLoaded);
      this.videoElement.load();
    } else {
      this._setState(TizenPlayerState.READY);
      if (onSuccess) onSuccess();
    }
  }

  play() {
    if (this.state === TizenPlayerState.IDLE || this.state === TizenPlayerState.ERROR) {
      return false;
    }
    if (this.videoElement) {
      this.videoElement.play().catch(() => {});
    }
    this._setState(TizenPlayerState.PLAYING);
    return true;
  }

  pause() {
    if (this.videoElement) {
      this.videoElement.pause();
    }
    this._setState(TizenPlayerState.PAUSED);
    return true;
  }

  seek(positionMs) {
    const targetMs = Math.max(0, positionMs);
    this.currentPositionMs = targetMs;
    if (this.videoElement) {
      this.videoElement.currentTime = targetMs / 1000;
    }
    return true;
  }

  stop() {
    if (this.videoElement) {
      this.videoElement.pause();
      this.videoElement.removeAttribute('src');
      this.videoElement.load();
    }
    this._setState(TizenPlayerState.IDLE);
    this.currentPositionMs = 0;
    return true;
  }

  close() {
    this.stop();
    this.url = null;
    return true;
  }

  getCurrentTime() {
    if (this.videoElement) {
      return (this.videoElement.currentTime || 0) * 1000;
    }
    return this.currentPositionMs;
  }

  getDuration() {
    if (this.videoElement && !isNaN(this.videoElement.duration)) {
      return this.videoElement.duration * 1000;
    }
    return this.durationMs;
  }

  setDisplayRect(x, y, width, height) {
    if (this.videoElement && this.videoElement.style) {
      this.videoElement.style.position = 'absolute';
      this.videoElement.style.left = `${x}px`;
      this.videoElement.style.top = `${y}px`;
      this.videoElement.style.width = `${width}px`;
      this.videoElement.style.height = `${height}px`;
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
    return TizenPlayerBackend.HTML5;
  }

  _setState(newState, errorInfo = null) {
    this.state = newState;
    if (this.listener && this.listener.onStateChange) {
      this.listener.onStateChange(newState, errorInfo);
    }
  }

  _attachDomEvents() {
    if (!this.videoElement) return;
    this.videoElement.addEventListener('waiting', () => {
      this._setState(TizenPlayerState.BUFFERING);
    });
    this.videoElement.addEventListener('playing', () => {
      this._setState(TizenPlayerState.PLAYING);
    });
    this.videoElement.addEventListener('pause', () => {
      if (this.state !== TizenPlayerState.ENDED) {
        this._setState(TizenPlayerState.PAUSED);
      }
    });
    this.videoElement.addEventListener('ended', () => {
      this._setState(TizenPlayerState.ENDED);
    });
    this.videoElement.addEventListener('timeupdate', () => {
      const ms = (this.videoElement.currentTime || 0) * 1000;
      this.currentPositionMs = ms;
      if (this.listener && this.listener.onTimeUpdate) {
        this.listener.onTimeUpdate(ms);
      }
    });
    this.videoElement.addEventListener('error', (e) => {
      this._setState(TizenPlayerState.ERROR, 'HTML5 video element error');
    });
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { TizenHtml5PlayerAdapter };
}
