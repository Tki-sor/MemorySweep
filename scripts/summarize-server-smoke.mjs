import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const workspace = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const evidenceRoot = join(workspace, 'test-results/server-smoke');
const artifact = join(workspace, 'universal/build/libs/memorysweep-universal-3.0.0.jar');
const sha256 = createHash('sha256').update(readFileSync(artifact)).digest('hex');
const results = [];

for (const directory of readdirSync(evidenceRoot, { withFileTypes: true })) {
    if (!directory.isDirectory() || !/^\d+(?:\.\d+){1,2}-(?:fabric|forge|neoforge)$/.test(directory.name)) continue;
    const latestPath = join(evidenceRoot, directory.name, 'result.json');
    if (!existsSync(latestPath)) continue;
    const latest = JSON.parse(readFileSync(latestPath, 'utf8'));
    let result = latest;
    if (latest.sha256 !== sha256) {
        result = { version: latest.version, loader: latest.loader, status: 'stale-needs-retest' };
    } else {
        const expectedDirectory = join(evidenceRoot, directory.name, 'attempts', String(latest.attemptId));
        const immutablePath = join(expectedDirectory, 'result.json');
        if (!/^[a-f0-9-]{36}$/i.test(String(latest.attemptId)) || resolve(latest.directory) !== resolve(expectedDirectory)
                || !existsSync(immutablePath) || JSON.stringify(JSON.parse(readFileSync(immutablePath, 'utf8'))) !== JSON.stringify(latest)) {
            result = { version: latest.version, loader: latest.loader, status: 'invalid-evidence' };
        } else if (latest.status === 'dedicated-server-core-verified' && !(latest.runtimeStarted && latest.dedicatedServerStarted
                && latest.serverTickActive && latest.cleanupAndGcAfterStartup && latest.aliveBeforeStop && latest.gracefulStop
                && latest.runtimeAfterStop?.exited && latest.runtimeAfterStop.code === 0 && latest.teardown?.confirmed)) {
            result = { ...latest, status: 'invalid-evidence' };
        }
    }
    results.push(result);
}

results.sort((first, second) => first.version.localeCompare(second.version, undefined, { numeric: true }) || first.loader.localeCompare(second.loader));
const record = { generatedAt: new Date().toISOString(), currentJarSha256: sha256, coverage: 'Recorded dedicated-server smoke combinations only; not the full client catalog.', results };
writeFileSync(join(evidenceRoot, 'matrix.json'), JSON.stringify(record, null, 2));
const lines = ['# Dedicated-server smoke matrix', '', `JAR SHA-256: \`${sha256}\``, '', record.coverage,
    'A pass requires startup, server tick, new cleanup and explicit GC after startup, a live JVM, graceful stop and confirmed teardown. No player connection, gameplay or long-session memory claim.', '',
    '| Minecraft | Loader | Status | Started | Tick | Post-startup cleanup + GC | Alive | Graceful stop | Attempt |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- |'];
for (const result of results) {
    const attempt = result.attemptId ? `[${result.attemptId}](./${result.version}-${result.loader}/attempts/${result.attemptId}/result.json)` : '-';
    lines.push(`| ${result.version} | ${result.loader} | ${result.status} | ${result.dedicatedServerStarted ?? '-'} | ${result.serverTickActive ?? '-'} | ${result.cleanupAndGcAfterStartup ?? '-'} | ${result.aliveBeforeStop ?? '-'} | ${result.gracefulStop ?? '-'} | ${attempt} |`);
}
writeFileSync(join(evidenceRoot, 'MATRIX.md'), lines.join('\n') + '\n');
console.log(JSON.stringify(results.reduce((counts, result) => {
    counts[result.status] = (counts[result.status] ?? 0) + 1;
    return counts;
}, {}), null, 2));
