import assert from 'node:assert/strict';
import test from 'node:test';
import { DatabaseSync } from 'node:sqlite';
import { RateGate } from '../src/index.mjs';

function makeGate() {
  const db = new DatabaseSync(':memory:');
  const sql = {
    exec(statement, ...args) {
      const prepared = db.prepare(statement);
      const rows = statement.trimStart().toUpperCase().startsWith('SELECT')
        ? prepared.all(...args) : (prepared.run(...args), []);
      return { toArray: () => rows };
    },
  };
  const storage = {
    sql,
    transactionSync(callback) {
      db.exec('BEGIN');
      try {
        const result = callback();
        db.exec('COMMIT');
        return result;
      } catch (error) {
        db.exec('ROLLBACK');
        throw error;
      }
    },
  };
  return { gate: new RateGate({ storage }), db };
}

function check(gate, ip, codeHash) {
  return gate.fetch(new Request('https://rate-gate.internal/check', {
    method: 'POST', body: JSON.stringify({ ip, code_hash: codeHash }),
  }));
}

test('minimum device polling interval and per-IP budget are enforced', async () => {
  const { gate, db } = makeGate();
  const oldNow = Date.now;
  let now = 1_000_000;
  Date.now = () => now;
  try {
    assert.equal((await check(gate, '192.0.2.1', 'a'.repeat(64))).status, 204);
    assert.equal((await check(gate, '192.0.2.1', 'a'.repeat(64))).status, 429);
    now += 5_000;
    assert.equal((await check(gate, '192.0.2.1', 'a'.repeat(64))).status, 204);
    for (let i = 0; i < 28; i++) {
      assert.equal((await check(gate, '192.0.2.1', i.toString(16).padStart(64, '0'))).status, 204);
    }
    assert.equal((await check(gate, '192.0.2.1', 'b'.repeat(64))).status, 429);
    now += 60_000;
    assert.equal((await check(gate, '192.0.2.1', 'b'.repeat(64))).status, 204);
  } finally {
    Date.now = oldNow;
    db.close();
  }
});

test('global budget is shared across IPs', async () => {
  const { gate, db } = makeGate();
  try {
    for (let i = 0; i < 600; i++) {
      const codeHash = i.toString(16).padStart(64, '0');
      assert.equal((await check(gate, `192.0.${Math.floor(i / 255)}.${i % 255}`, codeHash)).status, 204);
    }
    assert.equal((await check(gate, '198.51.100.1', 'f'.repeat(64))).status, 429);
  } finally {
    db.close();
  }
});
