#!/usr/bin/env node
// Public, pinned git objects only. Never reads a Faraday working tree, .env or cookies.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, rename, stat, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { challenges, sha256, solve } from './player-oracle-lib.mjs';

export const snapshot = '882624d59ccd921aa4cbf528e046ae61e137be47';
const root = fileURLToPath(new URL('../', import.meta.url));
const corpus = join(root, 'fixtures/faraday-200');
const hashPattern = /^[a-f0-9]{64}$/;
const idPattern = /^[a-f0-9]{8}$/;
const ordering = 'release.generatedAt descending, playerHash ascending (ASCII); generatedAt records successful derivation/validation, not firstSeenAt or config insertion order';
const json = data => JSON.parse(data.toString('utf8'));
const pretty = data => JSON.stringify(data, null, 2) + '\n';

export function resolveHints(configs, id) {
    const matches = Object.entries(configs.players).filter(([primary, c]) => primary === id || (c.aliases ?? []).includes(id));
    assert.equal(matches.length, 1, `${id}: expected exactly one primary/alias config`);
    const [configPrimaryHash, c] = matches[0];
    assert.match(c.sig, /^[A-Za-z0-9_$]+\(\d+,\d+,INPUT\)$/);
    assert.match(c.nClass, /^[A-Za-z0-9_$]+$/);
    assert.ok(Number.isSafeInteger(c.sts) && c.sts > 0, `${id}: invalid sts`);
    return { configPrimaryHash, signatureExpression: c.sig, nClass: c.nClass, signatureTimestamp: c.sts };
}

export function buildManifest(registryBytes, configBytes, releases, count) {
    const registry = json(registryBytes), configs = json(configBytes);
    assert.equal(registry.schemaVersion, 1);
    assert.equal(configs.schemaVersion, 1);
    const indexed = new Map(registry.players.map(p => [p.playerHash, p]));
    assert.equal(indexed.size, registry.players.length, 'duplicate registry player');
    assert.equal(releases.length, indexed.size, 'release/registry count mismatch');
    const seen = new Set();
    const records = releases.map(({ id, bytes }) => {
        assert.match(id, idPattern);
        assert.ok(!seen.has(id), `duplicate release ${id}`);
        seen.add(id);
        const r = json(bytes), p = indexed.get(id);
        assert.equal(r.schemaVersion, 1);
        assert.equal(r.playerHash, id);
        assert.ok(p, `${id}: no registry entry`);
        assert.equal(p.status, 'validated');
        assert.equal(p.validator, 'faraday');
        assert.equal(p.configPath, 'registry/player_configs.json');
        assert.equal(p.sha256, r.sha256, `${id}: registry/release source hash disagreement`);
        assert.equal(p.playerUrl, r.playerUrl, `${id}: registry/release URL disagreement`);
        assert.match(r.sha256, hashPattern);
        assert.equal(new Date(r.generatedAt).toISOString(), r.generatedAt, `${id}: invalid generatedAt`);
        assert.match(r.playerUrl, new RegExp(`^https://www\\.youtube\\.com/s/player/${id}/[A-Za-z0-9_./-]+/base\\.js$`));
        return { id, generatedAt: r.generatedAt, url: r.playerUrl, sha256: r.sha256,
            releaseRecordSha256: sha256(bytes) };
    });
    assert.ok(count > 0 && count <= records.length);
    const players = records.sort((a, b) => (a.generatedAt > b.generatedAt ? -1 : a.generatedAt < b.generatedAt ? 1 : 0) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
        .slice(0, count).map(p => ({ ...p, ...resolveHints(configs, p.id) }));
    return {
        schemaVersion: 1,
        snapshot: { repository: 'MetrolistGroup/faraday', commit: snapshot,
            registryPath: 'registry/player-registry.json', registrySha256: sha256(registryBytes),
            configPath: 'registry/player_configs.json', configSha256: sha256(configBytes),
            releasePath: 'registry/releases/<playerHash>.json', releaseRecordCount: records.length,
            // Digest covers every release, including records outside the selected cohort.
            releaseSetSha256: sha256(releases.map(r => `${r.id}:${sha256(r.bytes)}\n`).sort().join('')) },
        selection: { count, ordering }, players,
    };
}

async function pinnedManifest(repository) {
    const git = args => execFileSync('git', ['-C', repository, ...args], { maxBuffer: 4 * 1024 * 1024 });
    const blob = path => git(['show', `${snapshot}:${path}`]);
    const releases = git(['ls-tree', '-r', '--name-only', snapshot, 'registry/releases']).toString().trim().split('\n').map(path => {
        assert.match(path, /^registry\/releases\/[a-f0-9]{8}\.json$/);
        return { id: path.split('/').at(-1).slice(0, -5), bytes: blob(path) };
    });
    const manifest = buildManifest(blob('registry/player-registry.json'), blob('registry/player_configs.json'), releases, 200);
    validateManifest(manifest);
    return manifest;
}

export function validateManifest(m) {
    assert.equal(m.schemaVersion, 1);
    assert.equal(m.snapshot.repository, 'MetrolistGroup/faraday');
    assert.equal(m.snapshot.commit, snapshot);
    assert.equal(m.snapshot.registrySha256, 'd95e24ba4bc4651eb5c6b3394b75c7523faed6e7780773f29a57886e083a89e3');
    assert.equal(m.snapshot.configSha256, 'bbec1d8a20fc65a661ee590d7c4e3ce7cfc99478a2a8af5f5aa05d1151a66037');
    assert.equal(m.snapshot.releaseRecordCount, 438);
    assert.match(m.snapshot.releaseSetSha256, hashPattern);
    assert.equal(m.selection.ordering, ordering);
    assert.equal(m.selection.count, 200);
    assert.equal(m.players.length, 200);
    assert.equal(new Set(m.players.map(p => p.id)).size, 200);
    assert.equal(m.players[0].id, '3f99f1e0');
    assert.equal(m.players[0].generatedAt, '2026-10-22T18:07:26.380Z');
    assert.equal(m.players.at(-1).id, '9c9b2502');
    assert.equal(m.players.at(-1).generatedAt, '2026-08-31T13:07:22.286Z');
    for (const [index, p] of m.players.entries()) {
        assert.match(p.id, idPattern);
        assert.match(p.sha256, hashPattern);
        assert.match(p.releaseRecordSha256, hashPattern);
        assert.match(p.configPrimaryHash, idPattern);
        assert.equal(p.url, `https://www.youtube.com/s/player/${p.id}/player_ias.vflset/en_GB/base.js`);
        if (index) {
            const prev = m.players[index - 1];
            assert.ok(prev.generatedAt > p.generatedAt || (prev.generatedAt === p.generatedAt && prev.id < p.id), 'wrong release order');
        }
    }
}

const maxBytes = 8 * 1024 * 1024;
export function checkSource(bytes, player) {
    assert.ok(bytes.length >= 100_000 && bytes.length <= maxBytes, `${player.id}: source byte bounds`);
    assert.equal(sha256(bytes), player.sha256, `${player.id}: source SHA-256 mismatch`);
    return bytes;
}
async function source(cache, p) {
    const path = join(cache, 'sources', p.id + '.js');
    const { size } = await stat(path);
    assert.ok(size >= 100_000 && size <= maxBytes, `${p.id}: cached source byte bounds`);
    return checkSource(await readFile(path), p);
}

async function download(cache, p) {
    const path = join(cache, 'sources', p.id + '.js');
    try { return { bytes: (await source(cache, p)).length, cached: true }; }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    let bytes;
    for (let attempt = 1; attempt <= 3; attempt++) {
        try {
            const response = await fetch(p.url, { signal: AbortSignal.timeout(90_000), redirect: 'error' });
            if (!response.ok) { await response.body?.cancel(); throw new Error(`HTTP ${response.status}`); }
            if (Number(response.headers.get('content-length') ?? 0) > maxBytes) {
                await response.body.cancel(); throw new Error('Content-Length exceeds source bounds');
            }
            const chunks = [];
            let total = 0;
            for await (const chunk of response.body) {
                total += chunk.length;
                assert.ok(total <= maxBytes, 'download exceeds source bounds');
                chunks.push(chunk);
            }
            bytes = Buffer.concat(chunks);
            break;
        } catch (error) {
            if (attempt === 3) throw error;
            await new Promise(done => setTimeout(done, attempt * 1000));
        }
    }
    checkSource(bytes, p); // Hash failures are recorded, never substituted or accepted as new fixtures.
    await writeFile(path + '.part', bytes);
    await rename(path + '.part', path);
    return { bytes: bytes.length, cached: false };
}

async function main() {
    const [command, ...args] = process.argv.slice(2);
    const options = Object.fromEntries(args.filter(a => a.startsWith('--') && a.includes('=')).map(a => {
        const at = a.indexOf('='); return [a.slice(2, at), a.slice(at + 1)];
    }));
    const write = args.includes('--write');
    assert.ok(args.every(a => a === '--write' || /^--(cache|report|faraday)=.+/.test(a)), 'unknown option');
    assert.ok(['manifest', 'provenance', 'download', 'oracle'].includes(command),
        'usage: faraday-200.mjs manifest|provenance --faraday=REPO | download|oracle [--cache=DIR] [--report=FILE] [--write]');
    assert.ok(!write || command === 'oracle', '--write is only for intentional oracle regeneration');
    await mkdir(corpus, { recursive: true });
    const manifestPath = join(corpus, 'manifest.json');
    if (command === 'manifest' || command === 'provenance') {
        assert.ok(options.faraday, '--faraday must point to a public local git checkout containing the pinned commit');
        const rebuilt = await pinnedManifest(options.faraday);
        if (command === 'manifest') await writeFile(manifestPath, pretty(rebuilt));
        else assert.deepEqual(json(await readFile(manifestPath)), rebuilt);
        console.log(`${command}: 200/438 release records; primary/alias resolution and pinned provenance passed`);
        return;
    }
    const manifestBytes = await readFile(manifestPath), manifest = json(manifestBytes);
    validateManifest(manifest);
    const cache = resolve(options.cache ?? join(root, 'build/faraday-200'));
    await mkdir(join(cache, 'sources'), { recursive: true });
    const reportPath = resolve(options.report ?? join(cache, `${command}-report.json`));
    const statuses = new Array(200);
    const goldenPath = join(corpus, 'goldens.json');
    const inputs = challenges();
    let globals = '', stored, oracleFailure;
    if (command === 'oracle') {
        try { globals = await readFile(join(root, 'fixtures/oracle-globals.js'), 'utf8'); }
        catch (error) { oracleFailure = error; }
    }
    const oracle = { engine: 'node', version: process.version, globalsSha256: sha256(globals), freshContexts: 2, timeoutMs: 30_000 };
    if (command === 'oracle' && !write && !oracleFailure) {
        try {
            stored = json(await readFile(goldenPath));
            assert.equal(stored.schemaVersion, 1);
            assert.equal(stored.manifestSha256, sha256(manifestBytes));
            assert.equal(stored.oracle.globalsSha256, oracle.globalsSha256);
            assert.deepEqual(stored.inputs, inputs);
            assert.deepEqual(stored.players.map(p => p.id), manifest.players.map(p => p.id));
        } catch (error) { oracleFailure = error; }
    }
    const players = new Array(200);
    let next = 0;
    async function worker() {
        while (next < manifest.players.length) {
            const index = next++, p = manifest.players[index];
            try {
                if (command === 'download') {
                    statuses[index] = { id: p.id, status: 'pass', ...await download(cache, p) };
                } else {
                    if (oracleFailure) throw oracleFailure;
                    const bytes = await source(cache, p);
                    const first = solve(p, inputs, bytes, globals);
                    assert.deepEqual(challenges(), inputs);
                    assert.deepEqual(solve(p, inputs, bytes, globals), first, `${p.id}: fresh-context nondeterminism`);
                    const compact = { id: p.id, sha256: p.sha256,
                        signature: first.signature.map(c => c.output), n: first.n.map(c => c.output) };
                    if (!write) assert.deepEqual(compact, stored.players[index], `${p.id}: stored golden mismatch`);
                    players[index] = compact;
                    statuses[index] = { id: p.id, status: 'pass', bytes: bytes.length, signature: inputs.signature.length, n: inputs.n.length };
                }
            } catch (error) {
                statuses[index] = { id: p.id, status: 'fail', reason: String(error), cause: error.cause ? String(error.cause) : undefined };
            }
            console.log(`${command} ${index + 1}/200 ${p.id}: ${statuses[index].status}${statuses[index].reason ? ' ' + statuses[index].reason : ''}`);
        }
    }
    await Promise.all(Array.from({ length: command === 'download' ? 4 : 1 }, worker));
    const failed = statuses.filter(s => s.status !== 'pass').length;
    const report = { schemaVersion: 1, command, snapshot, manifestSha256: sha256(manifestBytes),
        node: process.version, cache, passed: 200 - failed, failed, statuses };
    await mkdir(resolve(reportPath, '..'), { recursive: true });
    await writeFile(reportPath, pretty(report));
    if (command === 'oracle' && write && !failed) {
        // Shared inputs once, one compact record per player, so reviews need no 200 huge fixtures.
        const header = JSON.stringify({ schemaVersion: 1, manifestSha256: sha256(manifestBytes), oracle, inputs });
        await writeFile(goldenPath + '.part', header.slice(0, -1) + ',"players":[\n' + players.map(p => JSON.stringify(p)).join(',\n') + '\n]}\n');
        await rename(goldenPath + '.part', goldenPath);
    }
    console.log(`${command}: ${200 - failed}/200 passed; report ${reportPath}`);
    if (failed) process.exitCode = 1;
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
    main().catch(error => { console.error(error); process.exitCode = 1; });
}
