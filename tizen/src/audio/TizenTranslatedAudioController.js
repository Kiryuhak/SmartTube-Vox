/**
 * Контроллер воспроизведения и синхронизации переведённой аудиодорожки на Tizen.
 */
class TizenTranslatedAudioController {
  constructor(options = {}) {
    this.audioElement = options.audioElement || null;
    this.videoElement = options.videoElement || null;
    this.driftThresholdSec = options.driftThresholdSec || 0.15; // 150ms концептуальный порог рассинхрона
    this.isPlaying = false;
    this.isPrepared = false;
    this.currentPositionSec = 0;
    this.volume = options.volume !== undefined ? options.volume : 1.0;
  }

  prepare(audioUrl) {
    if (!audioUrl) {
      this.isPrepared = false;
      return false;
    }
    if (this.audioElement) {
      this.audioElement.src = audioUrl;
      this.audioElement.volume = this.volume;
      this.audioElement.load();
    }
    this.isPrepared = true;
    return true;
  }

  play() {
    if (!this.isPrepared) return false;
    this.isPlaying = true;
    if (this.audioElement) {
      this.audioElement.play().catch(() => {});
    }
    return true;
  }

  pause() {
    this.isPlaying = false;
    if (this.audioElement) {
      this.audioElement.pause();
    }
    return true;
  }

  seek(positionSec) {
    this.currentPositionSec = Math.max(0, positionSec);
    if (this.audioElement) {
      this.audioElement.currentTime = this.currentPositionSec;
    }
    return true;
  }

  stop() {
    this.pause();
    this.isPrepared = false;
    this.currentPositionSec = 0;
    if (this.audioElement) {
      this.audioElement.removeAttribute('src');
      this.audioElement.load();
    }
  }

  getPosition() {
    if (this.audioElement) {
      return this.audioElement.currentTime || 0;
    }
    return this.currentPositionSec;
  }

  setVolume(volumePercent) {
    this.volume = Math.max(0, Math.min(1.0, volumePercent / 100));
    if (this.audioElement) {
      this.audioElement.volume = this.volume;
    }
  }

  /**
   * Синхронизация дорожки перевода с позицией основного видео.
   * Если рассинхрон превышает driftThresholdSec, выполняет корректировку времени.
   */
  syncToVideo() {
    if (!this.videoElement || !this.isPlaying || !this.isPrepared) {
      return { inSync: true, driftSec: 0 };
    }

    const videoPos = this.videoElement.currentTime || 0;
    const audioPos = this.getPosition();
    const drift = Math.abs(videoPos - audioPos);

    if (drift > this.driftThresholdSec) {
      this.seek(videoPos);
      return { inSync: false, driftSec: drift, resynced: true };
    }

    return { inSync: true, driftSec: drift, resynced: false };
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { TizenTranslatedAudioController };
}
