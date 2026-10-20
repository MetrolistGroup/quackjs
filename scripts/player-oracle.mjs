#!/usr/bin/env node
// Execute the complete, hash-pinned players. Never import the Kotlin implementation.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const fixtures = new URL('../fixtures/', import.meta.url);
const read = name => readFileSync(new URL(name, fixtures), 'utf8');
const sha256 = text => createHash('sha256').update(text).digest('hex');
const manifest = JSON.parse(read('manifest.json'));
const globals = read('oracle-globals.js');
const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-';

function challenges() {
    let state = 0x51a7c0de;
    const random = length => Array.from({ length }, () => {
        state ^= state << 13;
        state ^= state >>> 17;
        state ^= state << 5;
        return alphabet[(state >>> 0) % alphabet.length];
    }).join('');
    return {
        signature: [
            { name: 'alphabet', input: alphabet },
            { name: 'reverse-alphabet', input: [...alphabet].reverse().join('') },
            { name: 'repeated', input: 'A'.repeat(86) },
            { name: 'alternating', input: '_-'.repeat(43) },
            ...[16, 31, 43, 63, 64, 65, 85, 86, 87, 127, 128, 255].map(length =>
                ({ name: `seeded-${length}`, input: random(length) })),
        ],
        n: [
            { name: 'faraday-probe', input: 'KdrqFlzJXl9EcCwlmEy' },
            { name: 'alphabet', input: alphabet },
            { name: 'reverse-alphabet', input: [...alphabet].reverse().join('') },
            { name: 'repeated', input: 'z'.repeat(19) },
            { name: 'alternating', input: '_-'.repeat(16) },
            ...[15, 16, 19, 31, 32, 63, 64, 65, 127, 128].map(length =>
                ({ name: `seeded-${length}`, input: random(length) })),
        ],
    };
}

function solve(player, inputs) {
    const code = read(player.file);
    assert.equal(Buffer.byteLength(code), player.bytes, `${player.id}: size mismatch`);
    assert.equal(sha256(code), player.sha256, `${player.id}: SHA-256 mismatch`);
    assert.match(player.signatureExpression, /^[A-Za-z0-9_$]+\(\d+,\d+,INPUT\)$/);
    assert.match(player.nClass, /^[A-Za-z0-9_$]+$/);
    const trailer = '})(_yt_player);';
    assert.equal(code.split(trailer).length, 2, 'expected exactly one player IIFE trailer');
    const exports = `;globalThis.__quackSig=function(input){return ${player.signatureExpression.replace('INPUT', 'input')};};` +
        `globalThis.__quackN=function(input){return new g.${player.nClass}('https://x.googlevideo.com/videoplayback?n='+encodeURIComponent(input),true).get('n');};`;
    // No Node capabilities or timers are exposed to the player, and evaluation is bounded.
    const context = vm.createContext(Object.create(null), { codeGeneration: { strings: false, wasm: false } });
    vm.runInContext(globals, context, { timeout: 30_000 });
    vm.runInContext(code.replace(trailer, exports + trailer), context, { timeout: 30_000, filename: player.file });
    const result = JSON.parse(vm.runInContext(`globalThis.__inputs=${JSON.stringify(inputs)}; JSON.stringify({
        signature: __inputs.signature.map(c => ({...c, output: __quackSig(c.input)})),
        n: __inputs.n.map(c => ({...c, output: __quackN(c.input)}))
    })`, context, { timeout: 30_000 }));
    for (const kind of ['signature', 'n']) {
        for (const sample of result[kind]) {
            assert.equal(typeof sample.output, 'string', `${player.id}/${kind}/${sample.name}: not a string`);
            assert.ok(!sample.output.startsWith('enhanced_except_'), `${player.id}: n error sentinel`);
            if (kind === 'n') {
                assert.notEqual(sample.output, sample.input, `${player.id}/${sample.name}: n unchanged`);
                assert.match(sample.output, /^[A-Za-z0-9_-]{5,}$/);
            }
        }
        assert.ok(result[kind].some(c => c.output !== c.input), `${player.id}/${kind}: identity-only oracle`);
    }
    const probe = result.n.find(c => c.name === 'faraday-probe');
    assert.notEqual(probe.input, probe.output, `${player.id}: n probe unchanged`);
    assert.match(probe.output, /^[A-Za-z0-9_-]{5,}$/);
    return { id: player.id, sha256: player.sha256, ...result };
}

assert.ok(process.argv.slice(2).every(arg => arg === '--write'), 'usage: node scripts/player-oracle.mjs [--write]');
const inputs = challenges();
const players = manifest.players.map(player => solve(player, inputs));
// Re-evaluate from fresh contexts, including deterministic input generation, before writing anything.
assert.deepEqual(challenges(), inputs);
assert.deepEqual(manifest.players.map(player => solve(player, inputs)), players);
for (const kind of ['signature', 'n']) {
    assert.equal(new Set(players.map(p => JSON.stringify(p[kind].map(c => c.output)))).size, players.length,
        `${kind}: selected players must have materially different transforms`);
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
