'use strict';

const http = require('http');
const { GatewayApp } = require('./gateway_app');

const PORT = parseInt(process.env.PORT || '8788', 10);
const HOST = process.env.HOST || '0.0.0.0';

function createServer(options = {}) {
  const app = new GatewayApp(options);
  const server = http.createServer((req, res) => {
    app.handleRequest(req, res);
  });
  return { server, app };
}

if (require.main === module) {
  const { server } = createServer();
  server.listen(PORT, HOST, () => {
    console.log(`[SmartTube VOX Live Gateway] Server running at http://${HOST}:${PORT}`);
    console.log(`[SmartTube VOX Live Gateway] Health endpoint: http://${HOST}:${PORT}/health`);
  });

  const shutdown = () => {
    console.log('\n[SmartTube VOX Live Gateway] Gracefully shutting down...');
    server.close(() => {
      console.log('[SmartTube VOX Live Gateway] Closed.');
      process.exit(0);
    });
  };

  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

module.exports = {
  createServer
};
