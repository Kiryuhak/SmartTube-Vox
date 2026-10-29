/**
 * SmartTube VOX Diagnostics — Zero-dependency Pure JS ZIP Builder.
 * Implements PKWARE ZIP Specification (Stored / uncompressed mode).
 * Compatible with Node.js, Cloudflare Workers, and modern browsers.
 */

// CRC32 table
const CRC_TABLE = new Uint32Array(256);
for (let i = 0; i < 256; i++) {
  let c = i;
  for (let k = 0; k < 8; k++) {
    c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
  }
  CRC_TABLE[i] = c >>> 0;
}

export function crc32(buffer) {
  let crc = 0xFFFFFFFF;
  const len = buffer.length;
  let i = 0;
  const rem = len & 7;
  const unrolled = len - rem;
  while (i < unrolled) {
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buffer[i++]) & 0xFF];
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buffer[i++]) & 0xFF];
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buffer[i++]) & 0xFF];
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buffer[i++]) & 0xFF];
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buffer[i++]) & 0xFF];
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buffer[i++]) & 0xFF];
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buffer[i++]) & 0xFF];
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buffer[i++]) & 0xFF];
  }
  while (i < len) {
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buffer[i++]) & 0xFF];
  }
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

/**
 * Converts a JS Date to MS-DOS date and time format.
 */
export function dateToDosTime(date = new Date()) {
  const d = date instanceof Date ? date : new Date(date);
  const year = Math.max(1980, d.getFullYear());
  const month = d.getMonth() + 1;
  const day = d.getDate();
  const hours = d.getHours();
  const minutes = d.getMinutes();
  const seconds = Math.floor(d.getSeconds() / 2);

  const dosDate = ((year - 1980) << 9) | (month << 5) | day;
  const dosTime = (hours << 11) | (minutes << 5) | seconds;
  return { dosDate, dosTime };
}

/**
 * Normalizes file path to prevent ZIP Slip vulnerabilities:
 * - Replaces backslashes with forward slashes
 * - Strips leading slashes and drive letters
 * - Eliminates '..' and '.' path segments
 * - Rejects paths attempting directory traversal
 */
export function sanitizeZipEntryPath(rawPath) {
  if (typeof rawPath !== 'string' || !rawPath.trim()) {
    throw new Error('Invalid zip entry path: must be a non-empty string');
  }
  let normalized = rawPath.replace(/\\/g, '/').replace(/^\/+/, '');
  normalized = normalized.replace(/^[a-zA-Z]:\//, '');
  
  const segments = normalized.split('/').filter(Boolean);
  const safeSegments = [];
  for (const seg of segments) {
    if (seg === '.' || seg === '') continue;
    if (seg === '..') {
      throw new Error(`Security Exception: Directory traversal ("..") detected in zip path: ${rawPath}`);
    }
    // Allow safe alphanumeric, dashes, dots, underscores
    const cleaned = seg.replace(/[^a-zA-Z0-9_\-\.\(\) ]/g, '_');
    safeSegments.push(cleaned);
  }
  if (safeSegments.length === 0) {
    throw new Error('Invalid zip entry path after sanitization');
  }
  return safeSegments.join('/');
}

export class ZipWriter {
  constructor() {
    this.entries = [];
  }

  /**
   * Adds a file entry to the archive.
   * @param {string} path - Virtual file path in zip (e.g. "NEW/VOX-12345678.json")
   * @param {string|Uint8Array|Buffer} content - Content string or binary buffer
   * @param {Date} [date] - File modification timestamp
   */
  addFile(path, content, date = new Date()) {
    const safePath = sanitizeZipEntryPath(path);
    let dataBuffer;
    if (typeof content === 'string') {
      dataBuffer = new TextEncoder().encode(content);
    } else if (content instanceof Uint8Array) {
      dataBuffer = content;
    } else if (typeof Buffer !== 'undefined' && Buffer.isBuffer(content)) {
      dataBuffer = new Uint8Array(content.buffer, content.byteOffset, content.byteLength);
    } else {
      dataBuffer = new TextEncoder().encode(JSON.stringify(content, null, 2));
    }

    const pathBytes = new TextEncoder().encode(safePath);
    const checksum = crc32(dataBuffer);
    const { dosDate, dosTime } = dateToDosTime(date);

    this.entries.push({
      path: safePath,
      pathBytes,
      data: dataBuffer,
      size: dataBuffer.length,
      crc: checksum,
      dosDate,
      dosTime,
    });
    return this;
  }

  /**
   * Compiles the archive into a contiguous Uint8Array (standard PKWARE ZIP).
   * @returns {Uint8Array}
   */
  build() {
    let totalSize = 0;
    // Calculate local headers + data
    for (const entry of this.entries) {
      entry.localHeaderOffset = totalSize;
      totalSize += 30 + entry.pathBytes.length + entry.size;
    }

    const centralDirectoryStart = totalSize;
    let centralDirectorySize = 0;
    for (const entry of this.entries) {
      centralDirectorySize += 46 + entry.pathBytes.length;
    }
    totalSize += centralDirectorySize;
    totalSize += 22; // EOCD size

    const buffer = new Uint8Array(totalSize);
    const view = new DataView(buffer.buffer);
    let offset = 0;

    // 1. Write Local File Headers + File Data
    for (const entry of this.entries) {
      // Local file header signature: 0x04034b50
      view.setUint32(offset, 0x04034b50, true);
      view.setUint16(offset + 4, 20, true); // Version needed to extract (2.0)
      view.setUint16(offset + 6, 0x0800, true); // Flags (bit 11 = UTF-8 encoding)
      view.setUint16(offset + 8, 0, true); // Compression method: 0 (Stored)
      view.setUint16(offset + 10, entry.dosTime, true);
      view.setUint16(offset + 12, entry.dosDate, true);
      view.setUint32(offset + 14, entry.crc, true);
      view.setUint32(offset + 18, entry.size, true); // Compressed size
      view.setUint32(offset + 22, entry.size, true); // Uncompressed size
      view.setUint16(offset + 26, entry.pathBytes.length, true);
      view.setUint16(offset + 28, 0, true); // Extra field length
      offset += 30;

      buffer.set(entry.pathBytes, offset);
      offset += entry.pathBytes.length;

      buffer.set(entry.data, offset);
      offset += entry.size;
    }

    // 2. Write Central Directory Headers
    for (const entry of this.entries) {
      // Central directory file header signature: 0x02014b50
      view.setUint32(offset, 0x02014b50, true);
      view.setUint16(offset + 4, 20, true); // Version made by
      view.setUint16(offset + 6, 20, true); // Version needed
      view.setUint16(offset + 8, 0x0800, true); // Flags (UTF-8)
      view.setUint16(offset + 10, 0, true); // Compression method: 0 (Stored)
      view.setUint16(offset + 12, entry.dosTime, true);
      view.setUint16(offset + 14, entry.dosDate, true);
      view.setUint32(offset + 16, entry.crc, true);
      view.setUint32(offset + 20, entry.size, true);
      view.setUint32(offset + 24, entry.size, true);
      view.setUint16(offset + 28, entry.pathBytes.length, true);
      view.setUint16(offset + 30, 0, true); // Extra field length
      view.setUint16(offset + 32, 0, true); // Comment length
      view.setUint16(offset + 34, 0, true); // Disk number start
      view.setUint16(offset + 36, 0, true); // Internal attributes
      view.setUint32(offset + 38, 0, true); // External attributes
      view.setUint32(offset + 42, entry.localHeaderOffset, true); // Offset of local header
      offset += 46;

      buffer.set(entry.pathBytes, offset);
      offset += entry.pathBytes.length;
    }

    // 3. Write End of Central Directory Record (EOCD)
    // EOCD signature: 0x06054b50
    view.setUint32(offset, 0x06054b50, true);
    view.setUint16(offset + 4, 0, true); // Disk number
    view.setUint16(offset + 6, 0, true); // Disk with central directory
    view.setUint16(offset + 8, this.entries.length, true); // Total entries on disk
    view.setUint16(offset + 10, this.entries.length, true); // Total entries
    view.setUint32(offset + 12, centralDirectorySize, true); // Size of central directory
    view.setUint32(offset + 16, centralDirectoryStart, true); // Offset of central directory
    view.setUint16(offset + 20, 0, true); // Comment length

    return buffer;
  }
}
