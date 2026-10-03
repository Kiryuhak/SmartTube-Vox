const assert = require('assert');
const { TizenDpadNavigation, TizenKeyCodes } = require('../src/ui/TizenDpadNavigation');

function run() {
  console.log('Testing Tizen DPAD Navigation...');

  assert.strictEqual(TizenKeyCodes.TIZEN_ENTER, 13);
  assert.strictEqual(TizenKeyCodes.TIZEN_BACK, 10009);
  assert.strictEqual(TizenKeyCodes.ESCAPE, 27);

  const mockNav = new TizenDpadNavigation(null);
  mockNav.focusableElements = [{ id: 'btn1' }, { id: 'btn2' }, { id: 'btn3' }];
  mockNav.focusIndex = 0;

  mockNav.moveFocus(1);
  assert.strictEqual(mockNav.focusIndex, 1);

  mockNav.moveFocus(1);
  assert.strictEqual(mockNav.focusIndex, 2);

  mockNav.moveFocus(1); // wrap around
  assert.strictEqual(mockNav.focusIndex, 0);

  mockNav.moveFocus(-1); // wrap around backwards
  assert.strictEqual(mockNav.focusIndex, 2);

  console.log('  ✓ Tizen DPAD Navigation tests PASS');
}

module.exports = { run };
