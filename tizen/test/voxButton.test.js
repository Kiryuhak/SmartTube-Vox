const assert = require('assert');
const { TizenVoxButton, TizenVoxButtonState } = require('../src/ui/TizenVoxButton');

function run() {
  console.log('Testing Tizen VOX Button States...');

  const btn = new TizenVoxButton({ initialState: TizenVoxButtonState.IDLE });
  assert.strictEqual(btn.getState(), TizenVoxButtonState.IDLE);
  assert.strictEqual(btn._getLabel(), 'Перевести');

  btn.setState(TizenVoxButtonState.REQUESTING);
  assert.strictEqual(btn.getState(), TizenVoxButtonState.REQUESTING);
  assert.strictEqual(btn._getLabel(), 'Переводим...');

  btn.setState(TizenVoxButtonState.PLAYING);
  assert.strictEqual(btn.getState(), TizenVoxButtonState.PLAYING);
  assert.strictEqual(btn._getLabel(), 'Перевод включён');

  btn.setState(TizenVoxButtonState.ERROR);
  assert.strictEqual(btn.getState(), TizenVoxButtonState.ERROR);
  assert.strictEqual(btn._getLabel(), 'Ошибка перевода');

  console.log('  ✓ Tizen VOX Button tests PASS');
}

module.exports = { run };
