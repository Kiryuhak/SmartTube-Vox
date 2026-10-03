const assert = require('assert');
const { TizenTranslatedAudioController } = require('../src/audio/TizenTranslatedAudioController');

function run() {
  console.log('Testing Tizen Translated Audio Controller...');

  const controller = new TizenTranslatedAudioController();

  // 1. Prepare
  const prepOk = controller.prepare('https://example.com/translated.mp3');
  assert.strictEqual(prepOk, true);
  assert.strictEqual(controller.isPrepared, true);

  // 2. Play
  const playOk = controller.play();
  assert.strictEqual(playOk, true);
  assert.strictEqual(controller.isPlaying, true);

  // 3. Seek
  controller.seek(45.5);
  assert.strictEqual(controller.getPosition(), 45.5);

  // 4. Pause & Stop
  controller.pause();
  assert.strictEqual(controller.isPlaying, false);

  controller.stop();
  assert.strictEqual(controller.isPrepared, false);
  assert.strictEqual(controller.getPosition(), 0);

  // 5. Video Drift & Sync
  const mockVideo = { currentTime: 60.0 };
  const syncController = new TizenTranslatedAudioController({
    videoElement: mockVideo,
    driftThresholdSec: 0.15
  });
  syncController.isPrepared = true;
  syncController.isPlaying = true;
  syncController.currentPositionSec = 60.05; // 50ms drift (< 150ms threshold)

  let syncRes = syncController.syncToVideo();
  assert.strictEqual(syncRes.inSync, true);
  assert.strictEqual(syncRes.resynced, false);

  syncController.currentPositionSec = 60.50; // 500ms drift (> 150ms threshold)
  syncRes = syncController.syncToVideo();
  assert.strictEqual(syncRes.inSync, false);
  assert.strictEqual(syncRes.resynced, true);
  assert.strictEqual(syncController.getPosition(), 60.0);

  console.log('  ✓ Tizen Translated Audio Controller tests PASS');
}

module.exports = { run };
