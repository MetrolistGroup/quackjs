// Independent full-source oracle shared by the offline and opt-in corpora. No Kotlin imports.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import vm from 'node:vm';

export const sha256 = data => createHash('sha256').update(data).digest('hex');
const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-';

export function challenges() {
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

export function solve(player, inputs, source, globals) {
    assert.equal(sha256(source), player.sha256, `${player.id}: SHA-256 mismatch`);
    if (player.bytes !== undefined) assert.equal(Buffer.byteLength(source), player.bytes, `${player.id}: size mismatch`);
    assert.match(player.signatureExpression, /^[A-Za-z0-9_$]+\(\d+,\d+,INPUT\)$/);
    assert.match(player.nClass, /^[A-Za-z0-9_$]+$/);
    const code = source.toString('utf8');
    assert.ok(Buffer.from(code).equals(Buffer.from(source)), 'source is not valid UTF-8');
    const trailer = '})(_yt_player);';
    assert.equal(code.split(trailer).length, 2, 'expected exactly one player IIFE trailer');
    const exports = `;globalThis.__quackSig=function(input){return ${player.signatureExpression.replace('INPUT', 'input')};};` +
        `globalThis.__quackN=function(input){return new g.${player.nClass}('https://x.googlevideo.com/videoplayback?n='+encodeURIComponent(input),true).get('n');};`;
    // Node VM is NOT a security sandbox. No Node capabilities/timers are exposed; eval is bounded.
    const context = vm.createContext(Object.create(null), { codeGeneration: { strings: false, wasm: false } });
    vm.runInContext(globals, context, { timeout: 30_000 });
    vm.runInContext(code.replace(trailer, exports + trailer), context, { timeout: 30_000, filename: player.id + '.js' });
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
    return { id: player.id, sha256: player.sha256, ...result };
}
