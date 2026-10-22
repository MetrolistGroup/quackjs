#!/usr/bin/env node
// Execute the complete, hash-pinned players. Never import the Kotlin implementation.
import assert from 'node:assert/strict';
import { gunzipSync } from 'node:zlib';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { challenges, sha256, solve } from './player-oracle-lib.mjs';

const fixtures = new URL('../fixtures/', import.meta.url);
const read = name => readFileSync(new URL(name, fixtures), 'utf8');
const readPlayer = name => {
    const bytes = readFileSync(new URL(name, fixtures));
    return name.endsWith('.gz') ? gunzipSync(bytes) : bytes;
};
const manifest = JSON.parse(read('manifest.json'));
const globals = read('oracle-globals.js');
assert.ok(process.argv.slice(2).every(arg => arg === '--write'), 'usage: node scripts/player-oracle.mjs [--write]');
const inputs = challenges();
const evaluate = () => manifest.players.map(player => solve(player, inputs, readPlayer(player.file), globals));
const players = evaluate();
// Re-evaluate from fresh contexts, including deterministic input generation, before writing anything.
assert.deepEqual(challenges(), inputs);
assert.deepEqual(evaluate(), players);
for (const kind of ['signature', 'n']) {
    assert.ok(new Set(players.map(p => JSON.stringify(p[kind].map(c => c.output)))).size >= Math.min(3, players.length),
        `${kind}: fixture set must cover at least three materially different transforms`);
}
const goldens = { schemaVersion: 1, oracle: { engine: 'node', version: process.version, globalsSha256: sha256(globals) }, players };
const path = new URL('goldens.json', fixtures);
if (process.argv.includes('--write')) {
    writeFileSync(path, JSON.stringify(goldens, null, 2) + '\n');
    console.log(`Wrote ${fileURLToPath(path)} with ${process.version}`);
} else {
    const stored = JSON.parse(read('goldens.json'));
    assert.equal(stored.schemaVersion, goldens.schemaVersion);
    assert.equal(stored.oracle.globalsSha256, goldens.oracle.globalsSha256);
    assert.deepEqual(stored.players, goldens.players);
    console.log(`Verified original full-player goldens with Node ${process.version} (recorded ${stored.oracle.version})`);
}
console.log(`${players.length} players; ${inputs.signature.length} signature + ${inputs.n.length} n cases each; all SHA-256 checks and fresh-context repeat checks passed`);
