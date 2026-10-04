const capabilitiesTest = require('./capabilities.test');
const codecPolicyTest = require('./codecPolicy.test');
const dpadNavTest = require('./dpadNavigation.test');
const diagnosticsTest = require('./diagnostics.test');
const voxButtonTest = require('./voxButton.test');
const audioCtrlTest = require('./translatedAudioController.test');
const playerEngineTest = require('./playerEngine.test');

console.log('==============================================');
console.log('Running SmartTube VOX Tizen Platform Test Suite');
console.log('==============================================\n');

let passed = 0;
let failed = 0;

const suites = [
  capabilitiesTest,
  codecPolicyTest,
  dpadNavTest,
  diagnosticsTest,
  voxButtonTest,
  audioCtrlTest,
  playerEngineTest
];

for (const suite of suites) {
  try {
    suite.run();
    passed++;
  } catch (err) {
    console.error('  ✗ TEST SUITE FAILED:', err);
    failed++;
  }
}

console.log('\n==============================================');
console.log(`Tizen Test Summary: ${passed} passed, ${failed} failed`);
console.log('==============================================');

if (failed > 0) {
  process.exit(1);
} else {
  process.exit(0);
}
