import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const source = fileURLToPath(new URL('../src/admin_app.js', import.meta.url));
const target = fileURLToPath(new URL('../src/admin_client.mjs', import.meta.url));
writeFileSync(target, `// Generated from admin_app.js. Run npm run build:client.\nexport const ADMIN_APP_JS = ${JSON.stringify(readFileSync(source, 'utf8'))};\n`);
