'use strict';

const { BackendApp } = require('./backend_app');
const config = require('./config');

const app = new BackendApp(config);

app.listen(config.port, config.host)
  .then((addr) => {
    console.log(`[VOX Live Backend] Listening on http://${addr.address}:${addr.port} (mode: ${config.backendMode})`);
  })
  .catch((err) => {
    console.error('[VOX Live Backend] Failed to start:', err);
    process.exit(1);
  });

process.on('SIGINT', async () => {
  console.log('[VOX Live Backend] Shutting down...');
  await app.close();
  process.exit(0);
});

process.on('SIGTERM', async () => {
  console.log('[VOX Live Backend] Terminating...');
  await app.close();
  process.exit(0);
});
