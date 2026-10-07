'use strict';

/**
 * Детерминированный MOCK конвейер инференса для тестирования топологии,
 * очередей и протоколов без необходимости скачивания гигабайтных ML весов.
 */
class MockPipeline {
  constructor(config = {}) {
    this.sampleRate = config.sampleRate || 16000;
  }

  /**
   * Имитация Faster-Whisper STT
   */
  async runStt(audioBuffer, metadata = {}) {
    const start = Date.now();
    // Имитация минимальной задержки инференса (5-15 мс)
    await new Promise(r => setTimeout(r, 10));
    const durationSec = audioBuffer ? (audioBuffer.length / (this.sampleRate * 2)).toFixed(1) : '2.0';
    const text = `Live audio stream segment #${metadata.sequence || 0} (${durationSec}s)`;
    const latency = Date.now() - start;
    return {
      text,
      language: metadata.sourceLanguage || 'en',
      latencyMs: latency
    };
  }

  /**
   * Имитация Marian NMT машинного перевода
   */
  async runTranslation(sourceText, targetLanguage = 'ru') {
    const start = Date.now();
    await new Promise(r => setTimeout(r, 5));
    const translatedText = `Перевод прямой трансляции: ${sourceText} [RU]`;
    const latency = Date.now() - start;
    return {
      translatedText,
      targetLanguage,
      latencyMs: latency
    };
  }

  /**
   * Имитация Piper TTS нейросинтеза
   */
  async runTts(translatedText, durationMs = 2000) {
    const start = Date.now();
    await new Promise(r => setTimeout(r, 10));

    // Генерируем детерминированный PCM s16le буфер заданной длительности (16kHz, mono, 2 байта/сэмпл)
    const totalSamples = Math.floor((this.sampleRate * durationMs) / 1000);
    const buffer = Buffer.alloc(totalSamples * 2);

    const freq = 440.0; // Нота Ля
    for (let i = 0; i < totalSamples; i++) {
      const t = i / this.sampleRate;
      // Мягкий синусоидальный тон с затуханием к краям фрагмента
      const envelope = Math.sin(Math.PI * (i / totalSamples));
      const sampleVal = Math.round(1500 * envelope * Math.sin(2 * Math.PI * freq * t));
      buffer.writeInt16LE(sampleVal, i * 2);
    }

    const latency = Date.now() - start;
    return {
      audioBuffer: buffer,
      sampleRate: this.sampleRate,
      channels: 1,
      encoding: 'pcm_s16le',
      latencyMs: latency
    };
  }
}

module.exports = { MockPipeline };
