/**
 * Нормализованные состояния медиаплеера на платформе Samsung Tizen.
 */
const TizenPlayerState = {
  IDLE: 'IDLE',
  PREPARING: 'PREPARING',
  READY: 'READY',
  PLAYING: 'PLAYING',
  PAUSED: 'PAUSED',
  BUFFERING: 'BUFFERING',
  ENDED: 'ENDED',
  ERROR: 'ERROR'
};

const TizenPlayerBackend = {
  AVPLAY: 'AVPLAY',
  HTML5: 'HTML5',
  MSE: 'MSE',
  MOCK: 'MOCK'
};

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { TizenPlayerState, TizenPlayerBackend };
}
