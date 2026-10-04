const { TizenPlayerState, TizenPlayerBackend } = require('./TizenPlayerState');
const { TizenAvPlayAdapter } = require('./TizenAvPlayAdapter');
const { TizenHtml5PlayerAdapter } = require('./TizenHtml5PlayerAdapter');

/**
 * Единый медиадвижок для платформы Samsung Tizen.
 * Автоматически выбирает нативный AVPlay на реальных ТВ/эмуляторе с webapis,
 * либо откатывается на стандартный HTML5/MSE видеоплеер.
 */
class TizenPlayerEngine {
  constructor(options = {}) {
    this.window = options.window || (typeof window !== 'undefined' ? window : null);
    this.listener = options.listener || null;

    const avplayAdapter = new TizenAvPlayAdapter({
      webapis: this.window && this.window.webapis ? this.window.webapis : null,
      listener: this.listener
    });

    if (avplayAdapter.isAvailable()) {
      this.adapter = avplayAdapter;
      this.backend = TizenPlayerBackend.AVPLAY;
    } else {
      this.adapter = new TizenHtml5PlayerAdapter({
        videoElement: options.videoElement,
        listener: this.listener
      });
      this.backend = TizenPlayerBackend.HTML5;
    }
  }

  getBackend() {
    return this.backend;
  }

  open(url) {
    return this.adapter.open(url);
  }

  prepare() {
    return this.adapter.prepare();
  }

  prepareAsync(onSuccess, onError) {
    return this.adapter.prepareAsync(onSuccess, onError);
  }

  play() {
    return this.adapter.play();
  }

  pause() {
    return this.adapter.pause();
  }

  seek(positionMs) {
    return this.adapter.seek(positionMs);
  }

  stop() {
    return this.adapter.stop();
  }

  close() {
    return this.adapter.close();
  }

  getCurrentTime() {
    return this.adapter.getCurrentTime();
  }

  getDuration() {
    return this.adapter.getDuration();
  }

  setDisplayRect(x, y, width, height) {
    return this.adapter.setDisplayRect(x, y, width, height);
  }

  getState() {
    return this.adapter.getState();
  }

  setListener(listener) {
    this.listener = listener;
    this.adapter.setListener(listener);
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    TizenPlayerEngine,
    TizenPlayerState,
    TizenPlayerBackend
  };
}
