const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT_DIR = path.join(__dirname, '..');
const SRC_DIR = path.join(ROOT_DIR, 'src');
const BUILD_DIR = path.join(ROOT_DIR, 'build');
const WGT_OUTPUT_PATH = path.join(BUILD_DIR, 'SmartTube-VOX-7.0.0.wgt');

function ensureDir(dir) {
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
}

function copyRecursive(src, dest) {
  ensureDir(dest);
  const entries = fs.readdirSync(src, { withFileTypes: true });
  for (const entry of entries) {
    const srcPath = path.join(src, entry.name);
    const destPath = path.join(dest, entry.name);
    if (entry.isDirectory()) {
      copyRecursive(srcPath, destPath);
    } else {
      fs.copyFileSync(srcPath, destPath);
    }
  }
}

function bundleWebAssets() {
  console.log('[Tizen Build] Bundling web assets...');
  ensureDir(BUILD_DIR);
  copyRecursive(SRC_DIR, path.join(BUILD_DIR, 'src'));
  fs.copyFileSync(path.join(ROOT_DIR, 'config.xml'), path.join(BUILD_DIR, 'config.xml'));
  console.log('[Tizen Build] Web assets bundled successfully in:', BUILD_DIR);
}

function checkTizenCli() {
  try {
    execSync('tizen version', { stdio: ['pipe', 'pipe', 'ignore'], encoding: 'utf-8' });
    return true;
  } catch (e) {
    return false;
  }
}

function makeCrcTable() {
  let c;
  const table = [];
  for (let n = 0; n < 256; n++) {
    c = n;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    }
    table[n] = c >>> 0;
  }
  return table;
}

const crcTable = makeCrcTable();

function calculateCrc32(buf) {
  let crc = 0 ^ (-1);
  for (let i = 0; i < buf.length; i++) {
    crc = (crc >>> 8) ^ crcTable[(crc ^ buf[i]) & 0xFF];
  }
  return (crc ^ (-1)) >>> 0;
}

function packageStandardWgt(inputDir, outputWgtPath) {
  const fileEntries = [];

  function collectFiles(dir, relativePath = '') {
    const items = fs.readdirSync(dir, { withFileTypes: true });
    for (const item of items) {
      if (item.name.endsWith('.wgt')) continue;
      const fullPath = path.join(dir, item.name);
      const relPath = relativePath ? `${relativePath}/${item.name}` : item.name;
      if (item.isDirectory()) {
        collectFiles(fullPath, relPath);
      } else {
        const content = fs.readFileSync(fullPath);
        fileEntries.push({
          path: relPath.replace(/\\/g, '/'),
          content
        });
      }
    }
  }

  collectFiles(inputDir);

  const localHeaders = [];
  const centralDirHeaders = [];
  let offset = 0;

  for (const entry of fileEntries) {
    const pathBuf = Buffer.from(entry.path, 'utf8');
    const crc = calculateCrc32(entry.content);
    const uncompressedSize = entry.content.length;
    const deflated = zlib.deflateRawSync(entry.content);
    const useDeflate = deflated.length < uncompressedSize;
    const compressedData = useDeflate ? deflated : entry.content;
    const compressionMethod = useDeflate ? 8 : 0;
    const compressedSize = compressedData.length;

    const localHeader = Buffer.alloc(30 + pathBuf.length);
    localHeader.writeUInt32LE(0x04034b50, 0);
    localHeader.writeUInt16LE(20, 4);
    localHeader.writeUInt16LE(0, 6);
    localHeader.writeUInt16LE(compressionMethod, 8);
    localHeader.writeUInt16LE(0x4000, 10);
    localHeader.writeUInt16LE(0x5400, 12);
    localHeader.writeUInt32LE(crc, 14);
    localHeader.writeUInt32LE(compressedSize, 18);
    localHeader.writeUInt32LE(uncompressedSize, 22);
    localHeader.writeUInt16LE(pathBuf.length, 26);
    localHeader.writeUInt16LE(0, 28);
    pathBuf.copy(localHeader, 30);

    const localEntryOffset = offset;
    localHeaders.push(localHeader);
    localHeaders.push(compressedData);
    offset += localHeader.length + compressedData.length;

    const cdHeader = Buffer.alloc(46 + pathBuf.length);
    cdHeader.writeUInt32LE(0x02014b50, 0);
    cdHeader.writeUInt16LE(20, 4);
    cdHeader.writeUInt16LE(20, 6);
    cdHeader.writeUInt16LE(0, 8);
    cdHeader.writeUInt16LE(compressionMethod, 10);
    cdHeader.writeUInt16LE(0x4000, 12);
    cdHeader.writeUInt16LE(0x5400, 14);
    cdHeader.writeUInt32LE(crc, 16);
    cdHeader.writeUInt32LE(compressedSize, 20);
    cdHeader.writeUInt32LE(uncompressedSize, 24);
    cdHeader.writeUInt16LE(pathBuf.length, 28);
    cdHeader.writeUInt16LE(0, 30);
    cdHeader.writeUInt16LE(0, 32);
    cdHeader.writeUInt16LE(0, 34);
    cdHeader.writeUInt16LE(0, 36);
    cdHeader.writeUInt32LE(0, 38);
    cdHeader.writeUInt32LE(localEntryOffset, 42);
    pathBuf.copy(cdHeader, 46);

    centralDirHeaders.push(cdHeader);
  }

  const centralDirStart = offset;
  let centralDirSize = 0;
  for (const cdh of centralDirHeaders) {
    centralDirSize += cdh.length;
  }

  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(fileEntries.length, 8);
  eocd.writeUInt16LE(fileEntries.length, 10);
  eocd.writeUInt32LE(centralDirSize, 12);
  eocd.writeUInt32LE(centralDirStart, 16);
  eocd.writeUInt16LE(0, 20);

  const finalZipBuffer = Buffer.concat([...localHeaders, ...centralDirHeaders, eocd]);
  fs.writeFileSync(outputWgtPath, finalZipBuffer);

  return {
    path: outputWgtPath,
    size: finalZipBuffer.length,
    entriesCount: fileEntries.length
  };
}

function main() {
  const args = process.argv.slice(2);
  const bundleOnly = args.includes('--bundle-only');

  bundleWebAssets();

  if (bundleOnly) {
    console.log('[Tizen Build] Bundle only mode finished.');
    return;
  }

  const hasTizenCli = checkTizenCli();
  if (hasTizenCli) {
    try {
      console.log('[Tizen WGT] Building development WGT package with Tizen CLI...');
      execSync(`tizen package -t wgt -s dev -- ${BUILD_DIR}`, { stdio: 'inherit' });
      console.log('[Tizen WGT] WGT package built successfully via Tizen CLI.');
      return;
    } catch (e) {
      console.warn('[Tizen WGT] Tizen CLI packaging failed, falling back to standard WGT packager:', e.message);
    }
  }

  console.log('[Tizen WGT] Packaging standard W3C Widget (WGT) package...');
  const result = packageStandardWgt(BUILD_DIR, WGT_OUTPUT_PATH);
  console.log(`[Tizen WGT] WGT artifact created: ${result.path} (${result.size} bytes, ${result.entriesCount} files)`);
  console.log('[Tizen WGT] Signing profile: dev/standard W3C widget');
}

if (require.main === module) {
  main();
}

module.exports = { bundleWebAssets, checkTizenCli, packageStandardWgt };
