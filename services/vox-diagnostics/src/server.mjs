#!/usr/bin/env node

/**
 * SmartTube VOX Diagnostics — Standalone HTTP Server
 * 
 * Provides production-ready Node.js runtime for the VOX Diagnostics service,
 * bridging standard Node.js HTTP server to handleRequest(request, env, storage).
 */

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { handleRequest } from './diagnostics.mjs';
import { ReportStorage } from './storage.mjs';

function loadSecureEnv() {
  const candidates = [
    path.join(os.homedir(), '.config', 'smarttube-vox', 'vox-diagnostics.env'),
    path.resolve(process.cwd(), '.env'),
  ];
  for (const envPath of candidates) {
    if (fs.existsSync(envPath)) {
      try {
        const content = fs.readFileSync(envPath, 'utf8');
        for (const line of content.split('\n')) {
          const trimmed = line.trim();
          if (!trimmed || trimmed.startsWith('#')) continue;
          const eqIdx = trimmed.indexOf('=');
          if (eqIdx > 0) {
            const key = trimmed.slice(0, eqIdx).trim();
            let val = trimmed.slice(eqIdx + 1).trim();
            if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
              val = val.slice(1, -1);
            }
            if (process.env[key] === undefined || process.env[key] === '') {
              process.env[key] = val;
            }
          }
        }
      } catch (_) {}
    }
  }
}

loadSecureEnv();

const PORT = parseInt(process.env.PORT || '8787', 10);
const HOST = process.env.HOST || '127.0.0.1';

// Build environment object for diagnostics service
const env = {
  ...process.env,
  TELEGRAM_NOTIFICATIONS_ENABLED: process.env.TELEGRAM_NOTIFICATIONS_ENABLED || 'false',
  TELEGRAM_BOT_TOKEN: process.env.TELEGRAM_BOT_TOKEN || '',
  TELEGRAM_CHAT_ID: process.env.TELEGRAM_CHAT_ID || '',
  TELEGRAM_AGGREGATION_WINDOW_MINUTES: process.env.TELEGRAM_AGGREGATION_WINDOW_MINUTES || '15',
  DASHBOARD_BASE_URL: process.env.DASHBOARD_BASE_URL || '',
};

const storage = new ReportStorage(null);

const server = http.createServer(async (req, res) => {
  try {
    const protocol = req.headers['x-forwarded-proto'] || 'http';
    const hostHeader = req.headers['host'] || `${HOST}:${PORT}`;
    const url = `${protocol}://${hostHeader}${req.url}`;

    const headers = new Headers();
    for (const [k, v] of Object.entries(req.headers)) {
      if (Array.isArray(v)) {
        v.forEach(val => headers.append(k, val));
      } else if (v !== undefined) {
        headers.set(k, v);
      }
    }

    const chunks = [];
    for await (const chunk of req) {
      chunks.push(chunk);
    }
    const bodyBuffer = (req.method !== 'GET' && req.method !== 'HEAD' && chunks.length > 0)
      ? Buffer.concat(chunks)
      : undefined;

    const request = new Request(url, {
      method: req.method,
      headers,
      body: bodyBuffer,
    });

    const response = await handleRequest(request, env, storage);

    res.statusCode = response.status;
    response.headers.forEach((val, key) => {
      res.setHeader(key, val);
    });

    const responseBuffer = Buffer.from(await response.arrayBuffer());
    res.end(responseBuffer);
  } catch (err) {
    console.error('[VOX Diagnostics Server Error]:', err.message);
    res.statusCode = 500;
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ error: 'internal_server_error' }));
  }
});

server.listen(PORT, HOST, () => {
  console.log(`[VOX Diagnostics] Server listening on http://${HOST}:${PORT}`);
  console.log(`[VOX Diagnostics] TELEGRAM_NOTIFICATIONS_ENABLED: ${env.TELEGRAM_NOTIFICATIONS_ENABLED}`);
  console.log(`[VOX Diagnostics] TELEGRAM_BOT_TOKEN: ${env.TELEGRAM_BOT_TOKEN ? 'CONFIGURED' : 'MISSING'}`);
  console.log(`[VOX Diagnostics] TELEGRAM_CHAT_ID: ${env.TELEGRAM_CHAT_ID ? 'CONFIGURED' : 'MISSING'}`);
});

function handleShutdown(signal) {
  console.log(`[VOX Diagnostics] Received ${signal}, shutting down gracefully...`);
  server.close(() => {
    console.log('[VOX Diagnostics] Server stopped.');
    process.exit(0);
  });
  setTimeout(() => {
    console.error('[VOX Diagnostics] Forcefully terminating.');
    process.exit(1);
  }, 5000).unref();
}

process.on('SIGTERM', () => handleShutdown('SIGTERM'));
process.on('SIGINT', () => handleShutdown('SIGINT'));
