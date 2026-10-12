import { execSync } from 'child_process';
import fs from 'fs';

const sql = "SELECT payload_json FROM reports WHERE report_id = 'VOX-0C69F5';";
fs.writeFileSync('temp_q.sql', sql, 'utf8');
try {
  const output = execSync('npx.cmd wrangler d1 execute vox_diagnostics_db --remote --command="SELECT payload_json FROM reports WHERE report_id = \'VOX-0C69F5\';"', { encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 });
  const start = output.indexOf('[\n  {\n');
  const end = output.lastIndexOf(']') + 1;
  const jsonStr = output.substring(start, end);
  const parsed = JSON.parse(jsonStr);
  const rawPayload = parsed[0].results[0].payload_json;
  const payload = JSON.parse(rawPayload);
  fs.writeFileSync('C:/Users/user/.gemini/antigravity/brain/f3e296bd-2d1e-4494-822f-41bc7401fbb8/scratch/vox_0c69f5_payload.json', JSON.stringify(payload, null, 2), 'utf8');
  console.log('Successfully saved VOX-0C69F5 payload to scratch! Size:', rawPayload.length);
} catch (e) {
  console.error('Error:', e);
}
