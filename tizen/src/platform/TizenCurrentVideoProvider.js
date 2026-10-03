/**
 * Провайдер текущего идентификатора видео YouTube для Tizen.
 */
class TizenCurrentVideoProvider {
  constructor(windowObj = (typeof window !== 'undefined' ? window : null)) {
    this.window = windowObj;
    this.lastDetectedVideoId = null;
  }

  getCurrentVideoId() {
    if (!this.window) return this.lastDetectedVideoId;

    // 1. Поиск в URL / hash
    const loc = this.window.location;
    if (loc) {
      const match = loc.href.match(/[?&]v=([a-zA-Z0-9_-]{11})/);
      if (match && match[1]) {
        this.lastDetectedVideoId = match[1];
        return match[1];
      }
    }

    // 2. Поиск в DOM / HTML5 video data attributes
    if (this.window.document) {
      const videoEl = this.window.document.querySelector('video');
      if (videoEl) {
        const videoId = videoEl.getAttribute('data-youtube-id') || videoEl.dataset?.videoId;
        if (videoId && videoId.length === 11) {
          this.lastDetectedVideoId = videoId;
          return videoId;
        }
      }

      // 3. Поиск в мета-тегах или Cobalt player state
      const meta = this.window.document.querySelector('meta[name="youtube-video-id"]');
      if (meta && meta.content) {
        this.lastDetectedVideoId = meta.content;
        return meta.content;
      }
    }

    return this.lastDetectedVideoId;
  }

  setMockVideoId(videoId) {
    this.lastDetectedVideoId = videoId;
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { TizenCurrentVideoProvider };
}
