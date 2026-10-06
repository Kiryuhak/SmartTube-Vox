const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { bundleWebAssets } = require('../tools/build-wgt');

function run() {
  console.log('Testing Tizen browser bundle...');
  bundleWebAssets();
  const buildDir = path.join(__dirname, '..', 'build', 'src');
  const html = fs.readFileSync(path.join(buildDir, 'index.html'), 'utf8');
  assert.ok(html.includes('src="app.bundle.js"'));
  const bundle = fs.readFileSync(path.join(buildDir, 'app.bundle.js'), 'utf8');
  let onReady = null;
  const document = {
    readyState: 'loading',
    addEventListener(event, callback) {
      if (event === 'DOMContentLoaded') onReady = callback;
    }
  };
  vm.runInNewContext(bundle, { document, window: { document } }, { timeout: 2000 });
  assert.strictEqual(typeof onReady, 'function');
  assert.ok(fs.existsSync(path.join(buildDir, 'icon.png')));
  console.log('  ✓ Tizen browser bundle PASS');
}

module.exports = { run };
