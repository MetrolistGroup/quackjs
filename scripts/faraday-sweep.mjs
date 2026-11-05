#!/usr/bin/env node
// Check every player in the current Faraday registry, not the pinned cohort. Reads public git objects only.
// usage: faraday-sweep.mjs --faraday=REPO [--ref=origin/master] --cache=DIR [--limit=N]
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { download, resolveHints } from './faraday-200.mjs';
import { challenges, solve } from './player-oracle-lib.mjs';

const options = Object.fromEntries(process.argv.slice(2).map(a => a.slice(2).split('=')));
const { faraday, ref = 'origin/master', cache, limit } = options;
if (!faraday || !cache) throw new Error('usage: faraday-sweep.mjs --faraday=REPO [--ref=origin/master] --cache=DIR [--limit=N]');
const blob = path => JSON.parse(execFileSync('git', ['-C', faraday, 'show', `${ref}:${path}`], { maxBuffer: 64 * 1024 * 1024 }));
const registry = blob('registry/player-registry.json'), configs = blob('registry/player_configs.json');
const globals = await readFile(fileURLToPath(new URL('../fixtures/oracle-globals.js', import.meta.url)), 'utf8');
const inputs = challenges();
const entries = registry.players.slice(-(limit ?? registry.players.length));
await mkdir(join(cache, 'sources'), { recursive: true });

const players = [], failures = [];
for (const [index, entry] of entries.entries()) {
    const id = entry.playerHash;
    const p = { id, url: entry.playerUrl, sha256: entry.sha256 };
    try {
        Object.assign(p, resolveHints(configs, id));
        await download(cache, p);
        const bytes = await readFile(join(cache, 'sources', `${id}.js`));
        const first = solve(p, inputs, bytes, globals);
        players.push({ ...p, signature: first.signature.map(c => c.output), n: first.n.map(c => c.output) });
        console.log(`${index + 1}/${entries.length} ${id}: oracle ok`);
    } catch (error) {
        failures.push({ id, url: p.url, reason: String(error).slice(0, 500) });
        console.log(`${index + 1}/${entries.length} ${id}: FAIL ${String(error).slice(0, 200)}`);
    }
}
const commit = execFileSync('git', ['-C', faraday, 'rev-parse', ref]).toString().trim();
await writeFile(join(cache, 'sweep.json'), JSON.stringify({ commit, current: registry.current.playerHash, total: entries.length, inputs, players, failures }));
console.log(`Oracle: ${players.length}/${entries.length} players at ${commit}; ${failures.length} failed`);
