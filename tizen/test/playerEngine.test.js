const assert = require('assert');
const { TizenPlayerState, TizenPlayerBackend } = require('../src/player/TizenPlayerState');
const { TizenAvPlayAdapter } = require('../src/player/TizenAvPlayAdapter');
const { TizenHtml5PlayerAdapter } = require('../src/player/TizenHtml5PlayerAdapter');
const { TizenPlayerEngine } = require('../src/player/TizenPlayerEngine');

function run() {
  console.log('Testing Tizen AVPlay & Media Player Engine...');

  // 1. Mock webapis.avplay
  let avplayOpenUrl = null;
  let avplayPrepared = false;
  let avplayPlaying = false;
  let avplayPaused = false;
  let avplaySeekMs = 0;
  let avplayStopped = false;
  let avplayListener = null;

  const mockWebapis = {
    avplay: {
      open: (url) => { avplayOpenUrl = url; },
      prepare: () => { avplayPrepared = true; },
      prepareAsync: (onSuccess, onError) => {
        avplayPrepared = true;
        if (onSuccess) onSuccess();
      },
      play: () => { avplayPlaying = true; avplayPaused = false; },
      pause: () => { avplayPlaying = false; avplayPaused = true; },
      seekTo: (ms, success, err) => {
        avplaySeekMs = ms;
        if (success) success();
      },
      stop: () => { avplayStopped = true; avplayPlaying = false; },
      close: () => { avplayOpenUrl = null; },
      getCurrentTime: () => 15000,
      getDuration: () => 300000,
      setDisplayRect: (x, y, w, h) => {},
      setListener: (listener) => { avplayListener = listener; }
    }
  };

  // 2. Test AVPlay Adapter
  const avAdapter = new TizenAvPlayAdapter({ webapis: mockWebapis });
  assert.strictEqual(avAdapter.isAvailable(), true);
  assert.strictEqual(avAdapter.getBackendName(), TizenPlayerBackend.AVPLAY);

  assert.strictEqual(avAdapter.open('https://example.com/stream.mp4'), true);
  assert.strictEqual(avplayOpenUrl, 'https://example.com/stream.mp4');

  assert.strictEqual(avAdapter.prepare(), true);
  assert.strictEqual(avplayPrepared, true);
  assert.strictEqual(avAdapter.getState(), TizenPlayerState.READY);

  assert.strictEqual(avAdapter.play(), true);
  assert.strictEqual(avplayPlaying, true);
  assert.strictEqual(avAdapter.getState(), TizenPlayerState.PLAYING);

  assert.strictEqual(avAdapter.pause(), true);
  assert.strictEqual(avplayPaused, true);
  assert.strictEqual(avAdapter.getState(), TizenPlayerState.PAUSED);

  assert.strictEqual(avAdapter.seek(45000), true);
  assert.strictEqual(avplaySeekMs, 45000);

  assert.strictEqual(avAdapter.getCurrentTime(), 15000);
  assert.strictEqual(avAdapter.getDuration(), 300000);

  assert.strictEqual(avAdapter.stop(), true);
  assert.strictEqual(avAdapter.getState(), TizenPlayerState.IDLE);

  // 3. Test HTML5 Adapter fallback
  const html5Adapter = new TizenHtml5PlayerAdapter();
  assert.strictEqual(html5Adapter.getBackendName(), TizenPlayerBackend.HTML5);
  assert.strictEqual(html5Adapter.open('https://example.com/fallback.mp4'), true);
  assert.strictEqual(html5Adapter.prepare(), true);
  assert.strictEqual(html5Adapter.getState(), TizenPlayerState.READY);
  assert.strictEqual(html5Adapter.play(), true);
  assert.strictEqual(html5Adapter.getState(), TizenPlayerState.PLAYING);

  // 4. Test Player Engine Auto-Selection
  const engineWithAvPlay = new TizenPlayerEngine({
    window: { webapis: mockWebapis }
  });
  assert.strictEqual(engineWithAvPlay.getBackend(), TizenPlayerBackend.AVPLAY);

  const engineFallback = new TizenPlayerEngine({
    window: {}
  });
  assert.strictEqual(engineFallback.getBackend(), TizenPlayerBackend.HTML5);

  console.log('  ✓ Tizen AVPlay & Media Player Engine tests PASS');
}

module.exports = { run };
