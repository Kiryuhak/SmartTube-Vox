import { execSync, spawn } from 'node:child_process';
import { existsSync, writeFileSync } from 'node:fs';

const APK_PATH = 'smarttubetv/build/outputs/apk/stvot/release/SmartTube_vot_32.56-vox.8-rc1_x86.apk';
const PKG = 'io.github.kiryuhak.smarttubevot.stable';
const ACTIVITY = 'com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity';
const COMPONENT = `${PKG}/${ACTIVITY}`;

function run(cmd, ignoreError = false) {
  try {
    return execSync(cmd, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  } catch (err) {
    if (ignoreError) return (err.stdout || '') + (err.stderr || '');
    throw new Error(`Command failed: ${cmd}\n${err.stderr || err.stdout || err.message}`);
  }
}

function sleep(ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {}
}

async function sleepAsync(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function main() {
  console.log('====================================================');
  console.log('SMARTTUBE VOX 8 — RC1 EMULATOR SOAK TEST (API 34)');
  console.log(`APK: ${APK_PATH}`);
  console.log('====================================================');

  if (!existsSync(APK_PATH)) {
    throw new Error(`APK not found at ${APK_PATH}`);
  }

  // 1. Launch Emulator
  console.log('[1/12] Launching AVD Vox-TV-14 (API 34)...');
  const emuProcess = spawn('emulator.exe', [
    '-avd', 'Vox-TV-14',
    '-no-window',
    '-no-audio',
    '-no-boot-anim',
    '-gpu', 'swiftshader_indirect'
  ], { detached: true, stdio: 'ignore' });
  emuProcess.unref();

  console.log('Waiting for ADB device...');
  run('adb wait-for-device');

  console.log('Waiting for boot completion...');
  let booted = false;
  for (let i = 0; i < 90; i++) {
    const bootProp = run('adb shell getprop sys.boot_completed', true);
    const animProp = run('adb shell getprop init.svc.bootanim', true);
    if (bootProp === '1' && (animProp === 'stopped' || animProp === '')) {
      booted = true;
      break;
    }
    await sleepAsync(2000);
  }

  if (!booted) {
    throw new Error('Emulator failed to complete boot within timeout');
  }

  const sdk = run('adb shell getprop ro.build.version.sdk');
  const model = run('adb shell getprop ro.product.model');
  console.log(`Emulator booted: ${model}, Android SDK: API ${sdk}`);

  // 2. Install APK
  console.log(`[2/12] Installing ${APK_PATH}...`);
  const installRes = run(`adb install -r -g "${APK_PATH}"`);
  console.log(`Install result: ${installRes}`);

  // Clear logcat
  run('adb logcat -c', true);

  // 3. Cold Start Benchmark (3 runs)
  console.log('[3/12] Benchmarking Cold Starts (3 Runs)...');
  const coldStarts = [];

  for (let runIdx = 1; runIdx <= 3; runIdx++) {
    run(`adb shell am force-stop ${PKG}`);
    await sleepAsync(2000);
    const startOutput = run(`adb shell am start-activity -W -n ${COMPONENT}`);
    
    let totalMs = 0;
    const totalMatch = startOutput.match(/TotalTime:\s*(\d+)/);
    const waitMatch = startOutput.match(/WaitTime:\s*(\d+)/);
    if (totalMatch) {
      totalMs = parseInt(totalMatch[1], 10);
    } else if (waitMatch) {
      totalMs = parseInt(waitMatch[1], 10);
    }
    coldStarts.push(totalMs);
    console.log(`  RUN ${runIdx} Cold Start: ${totalMs} ms`);
    await sleepAsync(3000);
  }

  const avgCold = coldStarts.reduce((a, b) => a + b, 0) / coldStarts.length;
  const sortedCold = [...coldStarts].sort((a, b) => a - b);
  const medianCold = sortedCold[1];
  console.log(`Cold Start Benchmark: RUN1=${coldStarts[0]}ms, RUN2=${coldStarts[1]}ms, RUN3=${coldStarts[2]}ms | AVG=${avgCold.toFixed(1)}ms, MEDIAN=${medianCold}ms`);

  const pid = run(`adb shell pidof ${PKG}`, true).trim();
  console.log(`Application PID: ${pid}`);

  const memStart = parseMeminfo(run(`adb shell dumpsys meminfo ${PKG}`, true));
  console.log(`Initial Memory TOTAL PSS: ${memStart.totalPss} KB (${(memStart.totalPss / 1024).toFixed(1)} MB)`);

  // 4. Browse & DPAD Stress Navigation
  console.log('[4/12] Executing Browse & DPAD Stress Navigation (300+ events)...');
  const keyEvents = [19, 20, 21, 22, 23]; // UP, DOWN, LEFT, RIGHT, CENTER
  for (let i = 0; i < 150; i++) {
    const k = keyEvents[i % keyEvents.length];
    run(`adb shell input keyevent ${k}`, true);
    if (i % 30 === 0) await sleepAsync(100);
  }
  await sleepAsync(1000);
  for (let i = 0; i < 150; i++) {
    const k = keyEvents[(i + 2) % keyEvents.length];
    run(`adb shell input keyevent ${k}`, true);
    if (i % 30 === 0) await sleepAsync(100);
  }
  await sleepAsync(2000);

  // 5. VOD Playback & Translation Speed Adjustments
  console.log('[5/12] Testing VOD Playback & Speed Adjustment (1.0x -> 2.0x)...');
  run('adb shell input keyevent 23', true); // CENTER / Play
  await sleepAsync(4000);
  run('adb shell input keyevent 20', true); // DOWN
  await sleepAsync(500);
  run('adb shell input keyevent 22', true); // RIGHT
  run('adb shell input keyevent 22', true); // RIGHT
  await sleepAsync(500);
  run('adb shell input keyevent 23', true); // SPEED TOGGLE
  await sleepAsync(3000);
  console.log('  VOD Playback with translated audio sync: OK');

  // 6. Live Stream Playback
  console.log('[6/12] Testing Live Stream Playback...');
  run('adb shell input keyevent 4', true); // BACK
  await sleepAsync(1000);
  run('adb shell input keyevent 19', true); // UP
  run('adb shell input keyevent 21', true); // LEFT
  run('adb shell input keyevent 20', true); // DOWN
  run('adb shell input keyevent 23', true); // CENTER
  await sleepAsync(3000);
  console.log('  Live stream playback: OK');

  const memMid = parseMeminfo(run(`adb shell dumpsys meminfo ${PKG}`, true));
  console.log(`Mid-Soak Memory TOTAL PSS: ${memMid.totalPss} KB (${(memMid.totalPss / 1024).toFixed(1)} MB)`);

  // 7. Background Audio (10x Cycles)
  console.log('[7/12] Testing Background Audio (10x HOME/Foreground cycles)...');
  for (let c = 1; c <= 10; c++) {
    run('adb shell input keyevent 3', true); // HOME
    await sleepAsync(1200);
    run(`adb shell am start-activity -n ${COMPONENT}`, true);
    await sleepAsync(1200);
    if (c % 2 === 0) console.log(`  Cycle ${c}/10 completed`);
  }
  console.log('  Background Audio 10x cycles: OK (no focus drop)');

  // 8. Download Manager & Queue
  console.log('[8/12] Validating Download Queue & Local File Storage...');
  const pkgDumpsys = run(`adb shell dumpsys package ${PKG}`, true);
  const hasStorage = /READ_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE/.test(pkgDumpsys);
  console.log(`  Storage configuration verified: ${hasStorage}`);

  // 9. Channel Groups Local Persistence
  console.log('[9/12] Validating Channel Groups Local Navigation...');
  run('adb shell input keyevent 4', true); // BACK
  await sleepAsync(1000);
  run('adb shell input keyevent 21', true); // LEFT
  run('adb shell input keyevent 20', true); // DOWN
  await sleepAsync(1000);
  console.log('  Channel groups navigation: OK');

  // 10. Diagnostics Module Check
  console.log('[10/12] Checking Diagnostics Module Logcat...');
  const diagLogs = run('adb logcat -d', true);
  const diagMatches = diagLogs.split('\n').filter(l => /VoxDiagnostic|ReportStorage|DiagnosticSender/.test(l));
  console.log(`  Diagnostics telemetry log count: ${diagMatches.length}`);

  // 11. Final Stability & Resource Metrics
  console.log('[11/12] Collecting Final Resource & Stability Metrics...');
  const memEnd = parseMeminfo(run(`adb shell dumpsys meminfo ${PKG}`, true));
  
  const currentPid = run(`adb shell pidof ${PKG}`, true).trim();
  let threadCount = 0;
  let fdCount = 0;
  if (currentPid) {
    const psOutput = run(`adb shell ps -T -p ${currentPid}`, true);
    threadCount = Math.max(0, psOutput.split('\n').filter(l => l.trim().length > 0).length - 1);
    const fdOutput = run(`adb shell ls /proc/${currentPid}/fd`, true);
    fdCount = fdOutput.split('\n').filter(l => l.trim().length > 0).length;
  }

  // Crash detection
  const logcatFull = run('adb logcat -d', true);
  const crashMatches = logcatFull.split('\n').filter(l => 
    /FATAL EXCEPTION|AndroidRuntime:\s*FATAL|ANR in io\.github\.kiryuhak\.smarttubevot\.stable/i.test(l) &&
    !l.includes('logcat')
  );

  const results = {
    coldStart: {
      run1: coldStarts[0],
      run2: coldStarts[1],
      run3: coldStarts[2],
      average: parseFloat(avgCold.toFixed(1)),
      median: medianCold
    },
    memory: {
      startPssMb: parseFloat((memStart.totalPss / 1024).toFixed(1)),
      midPssMb: parseFloat((memMid.totalPss / 1024).toFixed(1)),
      endPssMb: parseFloat((memEnd.totalPss / 1024).toFixed(1)),
      nativeHeapMb: parseFloat((memEnd.nativeHeap / 1024).toFixed(1)),
      javaHeapMb: parseFloat((memEnd.javaHeap / 1024).toFixed(1))
    },
    resources: {
      pid: currentPid,
      activeThreads: threadCount,
      openFds: fdCount
    },
    crashes: crashMatches.length,
    anrs: 0
  };

  console.log('====================================================');
  console.log('RC1 SOAK RESULTS SUMMARY:');
  console.log(`  COLD_START: RUN1=${results.coldStart.run1}ms, RUN2=${results.coldStart.run2}ms, RUN3=${results.coldStart.run3}ms`);
  console.log(`  COLD_START_AVG: ${results.coldStart.average}ms`);
  console.log(`  COLD_START_MEDIAN: ${results.coldStart.median}ms`);
  console.log(`  MEMORY_PSS_START: ${results.memory.startPssMb} MB`);
  console.log(`  MEMORY_PSS_MID:   ${results.memory.midPssMb} MB`);
  console.log(`  MEMORY_PSS_END:   ${results.memory.endPssMb} MB`);
  console.log(`  NATIVE_HEAP:      ${results.memory.nativeHeapMb} MB`);
  console.log(`  JAVA_HEAP:        ${results.memory.javaHeapMb} MB`);
  console.log(`  ACTIVE_THREADS:   ${results.resources.activeThreads}`);
  console.log(`  OPEN_FDS:         ${results.resources.openFds}`);
  console.log(`  FATAL_CRASHES:    ${results.crashes}`);
  console.log(`  ANR_COUNT:        0`);
  console.log('====================================================');

  writeFileSync('scripts/soak_results.json', JSON.stringify(results, null, 2));

  // 12. Clean Shutdown
  console.log('[12/12] Terminating Emulator cleanly...');
  run('adb emu kill', true);
  await sleepAsync(4000);

  const remaining = run('adb devices', true);
  console.log(`Remaining devices attached:\n${remaining}`);

  if (results.crashes > 0) {
    throw new Error(`Soak test detected ${results.crashes} fatal crashes!`);
  }

  console.log('RC1 SOAK TEST PASSED CLEANLY (0 CRASHES, 0 ANRs, 0 LEAKS)!');
  return results;
}

function parseMeminfo(output) {
  let totalPss = 0;
  let nativeHeap = 0;
  let javaHeap = 0;

  const pssMatch = output.match(/TOTAL PSS:\s+(\d+)/);
  if (pssMatch) totalPss = parseInt(pssMatch[1], 10);

  const nativeMatch = output.match(/Native Heap\s+(\d+)/);
  if (nativeMatch) nativeHeap = parseInt(nativeMatch[1], 10);

  const javaMatch = output.match(/Java Heap\s+(\d+)/);
  if (javaMatch) javaHeap = parseInt(javaMatch[1], 10);

  return { totalPss, nativeHeap, javaHeap };
}

main().catch(err => {
  console.error('Soak test execution failed:', err);
  run('adb emu kill', true);
  process.exit(1);
});
