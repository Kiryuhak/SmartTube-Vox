import fs from 'fs';

const dir = 'common/build/test-results/testStvotDebugUnitTest';
if (fs.existsSync(dir)) {
  const files = fs.readdirSync(dir).filter(f => f.endsWith('.xml'));
  let totalTests = 0, totalFailures = 0;
  for (const f of files) {
    const content = fs.readFileSync(dir + '/' + f, 'utf8');
    const m = content.match(/tests="(\d+)"\s+skipped="(\d+)"\s+failures="(\d+)"/);
    if (m) {
      totalTests += parseInt(m[1]);
      totalFailures += parseInt(m[3]);
      console.log(`${f}: tests=${m[1]}, failures=${m[3]}`);
    }
  }
  console.log(`COMMON TOTAL: tests=${totalTests}, failures=${totalFailures}`);
}

const tvDir = 'smarttubetv/build/test-results/testStvotDebugUnitTest';
if (fs.existsSync(tvDir)) {
  const tvFiles = fs.readdirSync(tvDir).filter(f => f.endsWith('.xml'));
  let tvTests = 0, tvFailures = 0;
  for (const f of tvFiles) {
    const content = fs.readFileSync(tvDir + '/' + f, 'utf8');
    const m = content.match(/tests="(\d+)"\s+skipped="(\d+)"\s+failures="(\d+)"/);
    if (m) {
      tvTests += parseInt(m[1]);
      tvFailures += parseInt(m[3]);
      console.log(`${f}: tests=${m[1]}, failures=${m[3]}`);
    }
  }
  console.log(`SMARTTUBETV TOTAL: tests=${tvTests}, failures=${tvFailures}`);
}
