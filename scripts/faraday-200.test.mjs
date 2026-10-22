import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildManifest, checkSource, resolveHints, validateManifest } from './faraday-200.mjs';
import { challenges, sha256, solve } from './player-oracle-lib.mjs';

const encode = data => Buffer.from(JSON.stringify(data));
const configs = { schemaVersion: 1, players: {
    aaaaaaaa: { sig: 'Ab(1,2,INPUT)', nClass: 'CD', sts: 123, aliases: ['bbbbbbbb', 'cccccccc'] },
} };
const release = (id, generatedAt) => ({ id, bytes: encode({ schemaVersion: 1, playerHash: id, generatedAt,
    playerUrl: `https://www.youtube.com/s/player/${id}/player_ias.vflset/en_GB/base.js`, sha256: 'a'.repeat(64) }) });
const releases = [release('aaaaaaaa', '2026-01-01T00:00:00.000Z'), release('cccccccc', '2026-02-01T00:00:00.000Z'),
    release('bbbbbbbb', '2026-02-01T00:00:00.000Z')];
const registry = { schemaVersion: 1, players: releases.map(r => ({ ...JSON.parse(r.bytes),
    status: 'validated', validator: 'faraday', configPath: 'registry/player_configs.json',
    firstSeenAt: '2020-01-01T00:00:00.000Z' })) };

test('release time wins over config/registry/hash order; ties use ascending hash; primary and aliases resolve', () => {
    const m = buildManifest(encode(registry), encode(configs), releases, 2);
    assert.deepEqual(m.players.map(p => p.id), ['bbbbbbbb', 'cccccccc']);
    assert.deepEqual(resolveHints(configs, 'aaaaaaaa'), resolveHints(configs, 'bbbbbbbb'));
    assert.equal(m.snapshot.registrySha256, sha256(encode(registry)));
    assert.equal(m.snapshot.configSha256, sha256(encode(configs)));
    assert.equal(m.players[0].releaseRecordSha256, sha256(releases[2].bytes));
    assert.equal(m.snapshot.releaseSetSha256,
        buildManifest(encode(registry), encode(configs), [...releases].reverse(), 2).snapshot.releaseSetSha256);
});

test('missing or ambiguous aliases and release/registry provenance disagreements fail closed', () => {
    assert.throws(() => resolveHints(configs, 'dddddddd'), /exactly one/);
    assert.throws(() => resolveHints({ players: { ...configs.players, bbbbbbbb: configs.players.aaaaaaaa } }, 'bbbbbbbb'), /exactly one/);
    const changed = structuredClone(registry);
    changed.players[0].sha256 = 'b'.repeat(64);
    assert.throws(() => buildManifest(encode(changed), encode(configs), releases, 2), /hash disagreement/);
    changed.players[0].sha256 = 'a'.repeat(64);
    changed.players[0].playerUrl = changed.players[0].playerUrl.replace('en_GB', 'en_US');
    assert.throws(() => buildManifest(encode(changed), encode(configs), releases, 2), /URL disagreement/);
    assert.throws(() => buildManifest(encode(registry), encode(configs), [releases[0], releases[0], releases[2]], 2), /duplicate release/);
});

test('hash and byte bounds reject missing/truncated/changed source; manifest URL changes are not substitutions', () => {
    const bytes = Buffer.alloc(100_000, 'a');
    const p = { id: 'aaaaaaaa', sha256: sha256(bytes) };
    assert.equal(checkSource(bytes, p), bytes);
    assert.throws(() => checkSource(Buffer.alloc(99_999), p), /byte bounds/);
    assert.throws(() => checkSource(Buffer.alloc(100_000, 'b'), p), /SHA-256 mismatch/);
    const m = JSON.parse(readFileSync(new URL('../fixtures/faraday-200/manifest.json', import.meta.url)));
    validateManifest(m);
    m.players[0].url = m.players[0].url.replace('en_GB', 'en_US');
    assert.throws(() => validateManifest(m));
});

test('read-only CLI records every missing source and fails without rewriting goldens', () => {
    const directory = mkdtempSync(join(tmpdir(), 'quackjs-faraday-test-'));
    const goldenPath = new URL('../fixtures/faraday-200/goldens.json', import.meta.url);
    const before = readFileSync(goldenPath);
    try {
        const report = join(directory, 'report.json');
        const run = spawnSync(process.execPath, [fileURLToPath(new URL('./faraday-200.mjs', import.meta.url)),
            'oracle', `--cache=${directory}`, `--report=${report}`], { encoding: 'utf8', timeout: 10_000 });
        assert.equal(run.status, 1, run.stderr);
        const result = JSON.parse(readFileSync(report));
        assert.equal(result.passed, 0);
        assert.equal(result.failed, 200);
        assert.equal(result.statuses.length, 200);
        assert.ok(result.statuses.every(p => p.status === 'fail' && p.reason.includes('ENOENT')));
        assert.deepEqual(readFileSync(goldenPath), before);
    } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('deterministic vectors and the full-source oracle reject invalid n results (no catch-return-input)', () => {
    const inputs = challenges();
    assert.deepEqual(inputs, challenges());
    assert.equal(inputs.signature.length, 16);
    assert.equal(inputs.n.length, 15);
    const evaluate = body => {
        const code = `var _yt_player={};(function(g){function Ab(a,b,input){return input.split('').reverse().join('')};g.CD=function(url){var input=decodeURIComponent(url.split('n=')[1]);this.get=function(){${body}}}})(_yt_player);`;
        const player = { id: 'test', sha256: sha256(code), signatureExpression: 'Ab(1,2,INPUT)', nClass: 'CD' };
        return solve(player, inputs, code, '');
    };
    assert.equal(evaluate("return 'abcde_'").n.length, 15);
    for (const [body, error] of [
        ["return input", /n unchanged/], ["return null", /not a string/],
        ["return undefined", /not a string/], ["throw Error('broken')", /broken/],
        ["return 'enhanced_except_bad'", /n error sentinel/], ["return '&&&&&&'", /regular expression/],
    ]) assert.throws(() => evaluate(body), error);
});
