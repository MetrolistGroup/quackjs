#!/usr/bin/env node
// usage: faraday-200-compare.mjs <baseline.json> <candidate.json>
// Pairs players by id. A ratio above 1 means the candidate is faster or smaller.
import { readFileSync } from 'node:fs';

const [baseline, candidate] = process.argv.slice(2, 4).map(p => JSON.parse(readFileSync(p, 'utf8')));
const median = xs => { const s = [...xs].sort((a, b) => a - b), m = s.length >> 1; return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };
const quantile = (s, q) => s[Math.min(s.length - 1, Math.floor(q * s.length))];
const value = (p, key) => Array.isArray(p[key]) ? (p[key].length ? median(p[key]) : undefined) : (p[key] > 0 ? p[key] : undefined);
const byId = new Map(candidate.players.map(p => [p.id, p]));
const metrics = [['coldMs', 'cold ms'], ['firstCallMs', 'first call ms'], ['warmNNs', 'warm n ns/op'], ['warmSignatureNs', 'warm sig ns/op'],
    ['coldBytes', 'cold alloc B'], ['warmNBytes', 'warm n alloc B/op'], ['warmSignatureBytes', 'warm sig alloc B/op'], ['retainedBytes', 'retained B']];
const pad = (x, n) => String(x).padStart(n);
const fmt = x => x >= 1000 ? x.toFixed(0) : x.toPrecision(4);
console.log(`${baseline.label} -> ${candidate.label}; failed ${baseline.failed} -> ${candidate.failed}; missing from candidate: ${baseline.players.filter(p => !byId.has(p.id)).length}`);
for (const p of candidate.players) if (p.failure) console.log(`FAILED ${p.id}: ${p.failure}`);
console.log(`${'metric'.padEnd(21)}${pad('base med', 12)}${pad('cand med', 12)}${pad('geomean x', 11)}${pad('p5 x', 9)}${pad('median x', 10)}${pad('p95 x', 9)}${pad('worse>5%', 10)}${pad('n', 5)}`);
for (const [key, name] of metrics) {
    const pairs = baseline.players.flatMap(a => {
        const b = byId.get(a.id), x = value(a, key), y = b && !b.failure && !a.failure ? value(b, key) : undefined;
        return x && y ? [[x, y]] : [];
    });
    if (!pairs.length) continue;
    const ratios = pairs.map(([x, y]) => x / y).sort((a, b) => a - b);
    const geomean = Math.exp(ratios.reduce((sum, r) => sum + Math.log(r), 0) / ratios.length);
    console.log(`${name.padEnd(21)}${pad(fmt(median(pairs.map(p => p[0]))), 12)}${pad(fmt(median(pairs.map(p => p[1]))), 12)}${pad(geomean.toFixed(3), 11)}${pad(quantile(ratios, 0.05).toFixed(3), 9)}${pad(median(ratios).toFixed(3), 10)}${pad(quantile(ratios, 0.95).toFixed(3), 9)}${pad(ratios.filter(r => r < 1 / 1.05).length, 10)}${pad(ratios.length, 5)}`);
}
