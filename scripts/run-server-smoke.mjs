import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { closeSync, copyFileSync, createReadStream, createWriteStream, existsSync, mkdirSync, openSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';

const argumentsList = process.argv.slice(2);
const argument = name => argumentsList.find(value => value.startsWith(`--${name}=`))?.slice(name.length + 3);
const workspace = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const expectedSha256 = argument('expected-sha256') ?? '0fe70e00aa52d9bca273b3411aa7c3bb038e8210837313afd36662c24470b57b';
const jarPath = resolve(workspace, argument('jar') ?? 'universal/build/libs/memorysweep-universal-3.0.0.jar');
const evidenceRoot = join(workspace, 'test-results/server-smoke');
const cancellation = new AbortController();
const trackedProcesses = new Set();
let interrupted = false;
let signalCleanup;

if (argumentsList.includes('--help')) {
    console.log('Usage: node scripts/run-server-smoke.mjs --eula-accepted [--versions=1.18.2,26.2] [--loaders=fabric,forge,neoforge] [--expected-sha256=<candidate SHA-256>]');
    console.log('Read and acknowledge https://aka.ms/MinecraftEULA before passing --eula-accepted. Without that flag this script performs no setup, downloads, or evidence writes.');
    process.exit(0);
}
if (!argumentsList.includes('--eula-accepted')) {
    console.error('EULA acknowledgment required: read https://aka.ms/MinecraftEULA and confirm acceptance before explicitly passing --eula-accepted. No setup, downloads, or evidence writes performed.');
    process.exit(2);
}
if (process.platform !== 'win32') throw new Error('This scoped harness supports Windows process ownership and installer argument files only.');
if (!/^[a-f0-9]{64}$/i.test(expectedSha256)) throw new Error('Invalid expected candidate SHA-256.');
if (!existsSync(jarPath)) throw new Error(`Candidate JAR does not exist: ${jarPath}`);
const candidateHash = createHash('sha256').update(readFileSync(jarPath)).digest('hex');
if (candidateHash !== expectedSha256.toLowerCase()) throw new Error(`Candidate SHA-256 mismatch: ${candidateHash}`);
const packagePath = process.env.MEMORYSWEEP_LAUNCHER ?? join(process.env.APPDATA, 'npm/node_modules/minecraft-mod-mcp/dist/index.js');
const launcher = await import(pathToFileURL(packagePath));
const versionsData = launcher.loadVersionsData();
const defaultCases = [
    { version: '1.18.2', loader: 'fabric' },
    { version: '1.18.2', loader: 'forge' },
    { version: '1.21.1', loader: 'neoforge' },
    { version: '26.2', loader: 'fabric' },
    { version: '26.2', loader: 'neoforge' }
];
const selectedVersions = argument('versions')?.split(',');
const selectedLoaders = argument('loaders')?.split(',');
if (selectedVersions?.some(version => !/^\d+(?:\.\d+){1,2}$/.test(version))) throw new Error('Invalid Minecraft version filter.');
if (selectedLoaders?.some(loader => !['fabric', 'forge', 'neoforge'].includes(loader))) throw new Error('Invalid loader filter.');
const cases = selectedVersions || selectedLoaders
    ? (selectedVersions ?? [...new Set(defaultCases.map(testCase => testCase.version))]).flatMap(version => {
        const metadata = launcher.getVersion(versionsData, version);
        if (!metadata) throw new Error(`Minecraft ${version} is absent from the installed loader catalog.`);
        return launcher.loaders(metadata).filter(loader => !selectedLoaders || selectedLoaders.includes(loader)).map(loader => ({ version, loader }));
    })
    : defaultCases;
if (!cases.length) throw new Error('No supported Minecraft/loader combinations match the filters.');

function immutableJson(path, record) {
    writeFileSync(path, JSON.stringify(record, null, 2), { flag: 'wx' });
}

function assertCandidate(attempt) {
    const current = createHash('sha256').update(readFileSync(jarPath)).digest('hex');
    const copied = createHash('sha256').update(readFileSync(attempt.modPath)).digest('hex');
    if (current !== expectedSha256.toLowerCase() || copied !== current) throw new Error('Candidate or isolated Mod copy changed during the attempt.');
}

function javaFor(version) {
    const [major, minor, patch = 0] = version.split('.').map(Number);
    const target = major >= 26 ? 25 : minor >= 21 || (minor === 20 && patch >= 5) ? 21 : minor <= 16 ? 8 : 17;
    const candidate = launcher.detectJavas().find(java => java.version === target);
    if (!candidate) throw new Error(`Java ${target} is not installed.`);
    return { version: target, path: join(candidate.path, 'bin/java.exe') };
}

function startProcess(executable, args, options) {
    const startedAt = Date.now();
    const child = spawn(executable, args, options);
    const state = { child, pid: child.pid, startedAt, exited: false, closed: false, code: null, signal: null, error: null, intentionalTeardown: false };
    state.completion = new Promise(complete => {
        child.once('error', error => { state.error = error.message; });
        child.once('exit', (code, signal) => {
            state.exited = true;
            state.exitedAt = Date.now();
            state.code = code;
            state.signal = signal;
        });
        child.once('close', (code, signal) => {
            state.closed = true;
            if (!state.exited && !state.error) {
                state.exited = true;
                state.exitedAt = Date.now();
                state.code = code;
                state.signal = signal;
            }
            complete(state);
        });
    });
    trackedProcesses.add(state);
    return state;
}

function processRecord(state) {
    return { pid: state.pid, startedAt: new Date(state.startedAt).toISOString(), exited: state.exited, closed: state.closed,
        code: state.code, signal: state.signal, error: state.error, intentionalTeardown: state.intentionalTeardown };
}

function pidAlive(pid) {
    if (!pid) return false;
    try { process.kill(pid, 0); return true; } catch (error) {
        if (error.code === 'ESRCH') return false;
        throw error;
    }
}

async function processSnapshot() {
    const query = startProcess('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
        "Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,@{Name='StartedAt';Expression={if ($_.CreationDate) {$_.CreationDate.ToUniversalTime().ToString('o')}}} | ConvertTo-Json -Compress"],
    { stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    query.child.stdout.on('data', data => { output += data.toString(); });
    query.child.stderr.resume();
    const completed = await Promise.race([query.completion, delay(15000).then(() => 'timeout')]);
    if (completed === 'timeout') {
        query.child.kill();
        await query.completion;
        throw new Error('Process ownership query timed out.');
    }
    if (query.error || query.code !== 0 || query.signal) throw new Error('Cannot verify process ownership.');
    const parsed = JSON.parse(output || '[]');
    return Array.isArray(parsed) ? parsed : [parsed];
}

async function terminateOwnedTree(state) {
    if (!state.pid) return { confirmed: true, processes: [] };
    state.intentionalTeardown = true;
    const snapshot = await processSnapshot();
    const root = snapshot.find(item => item.ProcessId === state.pid);
    if (root && (state.exited || Math.abs(Date.parse(root.StartedAt) - state.startedAt) > 3000)) {
        throw new Error(`PID ${state.pid} ownership changed; refusing termination.`);
    }
    const earliest = root ? Date.parse(root.StartedAt) : state.startedAt - 20;
    const latest = state.exitedAt ?? Infinity;
    const owned = root ? [root] : [];
    const parentIds = new Set([state.pid]);
    let added = true;
    while (added) {
        added = false;
        for (const item of snapshot) {
            if (parentIds.has(item.ProcessId) || !parentIds.has(item.ParentProcessId)) continue;
            const created = Date.parse(item.StartedAt);
            if (!Number.isFinite(created) || created < earliest || created > latest) continue;
            parentIds.add(item.ProcessId);
            owned.push(item);
            added = true;
        }
    }
    for (const item of owned.reverse()) {
        const current = (await processSnapshot()).find(process => process.ProcessId === item.ProcessId);
        if (!current) continue;
        if (current.StartedAt !== item.StartedAt) throw new Error(`PID ${item.ProcessId} was reused; refusing termination.`);
        const killer = startProcess('taskkill.exe', ['/PID', String(item.ProcessId), '/F'], { stdio: 'ignore' });
        await killer.completion;
        if ((killer.error || killer.code !== 0) && pidAlive(item.ProcessId)) throw new Error(`Failed to stop owned PID ${item.ProcessId}.`);
    }
    await Promise.race([state.completion, delay(10000)]);
    const remaining = await processSnapshot();
    if (owned.some(item => remaining.some(process => process.ProcessId === item.ProcessId && process.StartedAt === item.StartedAt))) {
        throw new Error('Owned process tree termination was not confirmed.');
    }
    if (!state.closed) throw new Error('Owned subprocess did not close after termination.');
    return { confirmed: true, processes: owned.map(item => ({ pid: item.ProcessId, startedAt: item.StartedAt })) };
}

async function stopOnSignal(signal) {
    if (signalCleanup) return signalCleanup;
    interrupted = true;
    cancellation.abort(new Error(`Interrupted by ${signal}`));
    signalCleanup = (async () => {
        const failures = [];
        for (const state of [...trackedProcesses].filter(process => !process.closed)) {
            try { await terminateOwnedTree(state); } catch (error) { failures.push(error.message); }
        }
        if (failures.length) console.error(`Signal cleanup errors: ${failures.join('; ')}`);
        process.exitCode = 1;
    })();
    return signalCleanup;
}
for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(signal, () => { void stopOnSignal(signal); });

async function fileHash(path, algorithm) {
    const hash = createHash(algorithm);
    for await (const chunk of createReadStream(path)) hash.update(chunk);
    return hash.digest('hex');
}

async function responseFor(url, signal) {
    const response = await fetch(url, { signal });
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${url}`);
    return response;
}

async function jsonFor(url, signal) {
    return (await responseFor(url, signal)).json();
}

async function sidecarSha1(url, signal) {
    const response = await fetch(`${url}.sha1`, { signal });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(`Checksum HTTP ${response.status}: ${url}.sha1`);
    const match = (await response.text()).trim().match(/^([a-f0-9]{40})(?:\s|$)/i);
    if (!match) throw new Error(`Invalid official SHA-1 response: ${url}.sha1`);
    return match[1].toLowerCase();
}

async function download(attempt, url, destination, expectedSha1, signal, cachedPath) {
    let source = url;
    if (expectedSha1 && cachedPath && existsSync(cachedPath) && await fileHash(cachedPath, 'sha1') === expectedSha1) {
        copyFileSync(cachedPath, destination);
        source = cachedPath;
    } else {
        const response = await responseFor(url, signal);
        if (!response.body) throw new Error(`Download has no body: ${url}`);
        await pipeline(Readable.fromWeb(response.body), createWriteStream(destination, { flags: 'wx' }), { signal });
    }
    const actualSha1 = await fileHash(destination, 'sha1');
    if (expectedSha1 && actualSha1 !== expectedSha1) throw new Error(`SHA-1 mismatch for ${url}: ${actualSha1}`);
    attempt.downloads.push({ url, destination, source, sha1: actualSha1, expectedSha1: expectedSha1 ?? null,
        verified: Boolean(expectedSha1), sha256: await fileHash(destination, 'sha256') });
}

async function install(attempt, metadata, java, signal) {
    const manifestUrl = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';
    const manifest = await jsonFor(manifestUrl, signal);
    const versionEntry = manifest.versions.find(version => version.id === attempt.version);
    if (!versionEntry?.sha1) throw new Error(`Official manifest lacks version/SHA-1 for ${attempt.version}.`);
    const versionPath = join(attempt.directory, 'mojang-version.json');
    await download(attempt, versionEntry.url, versionPath, versionEntry.sha1, signal);
    const version = JSON.parse(readFileSync(versionPath, 'utf8'));
    if (version.id !== attempt.version || !version.downloads?.server?.sha1) throw new Error('Invalid official dedicated-server manifest.');
    const server = version.downloads.server;
    await download(attempt, server.url, join(attempt.serverDirectory, 'server.jar'), server.sha1, signal);
    let installerUrl;
    let installerPath;
    let installerArgs;
    if (attempt.loader === 'fabric') {
        const fabricLoaders = await jsonFor(`https://meta.fabricmc.net/v2/versions/loader/${attempt.version}`, signal);
        const loaderVersion = metadata.fabric_loader ?? fabricLoaders.find(item => item.loader.stable)?.loader.version ?? fabricLoaders[0]?.loader.version;
        if (!loaderVersion || !fabricLoaders.some(item => item.loader.version === loaderVersion)) throw new Error('Requested Fabric loader absent from official metadata.');
        const installers = await jsonFor('https://meta.fabricmc.net/v2/versions/installer', signal);
        const installerVersion = installers.find(item => item.stable)?.version ?? installers[0]?.version;
        if (!installerVersion || !/^[0-9.]+$/.test(installerVersion)) throw new Error('No valid official Fabric installer.');
        attempt.loaderVersion = loaderVersion;
        attempt.installerVersion = installerVersion;
        installerUrl = `https://maven.fabricmc.net/net/fabricmc/fabric-installer/${installerVersion}/fabric-installer-${installerVersion}.jar`;
        installerPath = join(attempt.directory, 'fabric-installer.jar');
        installerArgs = ['-jar', installerPath, 'server', '-mcversion', attempt.version, '-loader', loaderVersion, '-dir', attempt.serverDirectory];
    } else {
        const loaderVersion = attempt.loader === 'forge' ? metadata.forge : attempt.version === '1.20.4' ? '20.4.251' : metadata.neoforge;
        if (!loaderVersion || !/^[0-9][0-9A-Za-z.\-]*$/.test(loaderVersion)) throw new Error('Catalog lacks a valid official loader version.');
        attempt.loaderVersion = loaderVersion;
        const coordinates = attempt.loader === 'forge' ? `net/minecraftforge/forge/${loaderVersion}/forge-${loaderVersion}-installer.jar`
            : `net/neoforged/neoforge/${loaderVersion}/neoforge-${loaderVersion}-installer.jar`;
        installerUrl = `${attempt.loader === 'forge' ? 'https://maven.minecraftforge.net/' : 'https://maven.neoforged.net/releases/'}${coordinates}`;
        installerPath = join(attempt.directory, 'loader-installer.jar');
        installerArgs = ['-jar', installerPath, '--installServer', attempt.serverDirectory];
    }
    const checksumUrl = `${installerUrl}.sha1`;
    const verifiedChecksums = {
        'https://maven.neoforged.net/releases/net/neoforged/neoforge/26.2.0.75/neoforge-26.2.0.75-installer.jar.sha1': '2579833394c258c5eb5fdb44db865b6f16058e44'
    };
    const expectedSha1 = verifiedChecksums[checksumUrl] ?? await sidecarSha1(installerUrl, signal);
    attempt.installerChecksum = { url: checksumUrl, sha1: expectedSha1, source: verifiedChecksums[checksumUrl] ? 'previously-verified-official-sidecar' : 'live-official-sidecar' };
    const cachedPath = attempt.loader === 'fabric' ? undefined
        : join(launcher.versionsDir(), '.tmp', `${attempt.loader}-${attempt.loaderVersion}-installer.jar`);
    await download(attempt, installerUrl, installerPath, expectedSha1, signal, cachedPath);
    immutableJson(join(attempt.directory, 'installer-launch.json'), { java: java.path, args: installerArgs, installerUrl });
    const output = openSync(join(attempt.directory, 'installer-stdout.log'), 'wx');
    const errors = openSync(join(attempt.directory, 'installer-stderr.log'), 'wx');
    let installer;
    try {
        if (signal.aborted) throw signal.reason;
        installer = startProcess(java.path, installerArgs, { cwd: attempt.serverDirectory, stdio: ['ignore', output, errors] });
        const aborted = new Promise(complete => signal.addEventListener('abort', () => complete('timeout'), { once: true }));
        const outcome = await Promise.race([installer.completion, aborted]);
        if (outcome === 'timeout') {
            attempt.installTeardown = await terminateOwnedTree(installer);
            throw new Error(`Installation interrupted/timed out after 360 seconds: ${signal.reason}`);
        }
        attempt.installerProcess = processRecord(installer);
        attempt.installTeardown = await terminateOwnedTree(installer);
        if (installer.error || !installer.exited || installer.code !== 0 || installer.signal || interrupted) throw new Error(`Official installer failed: ${installer.error ?? installer.code ?? installer.signal}`);
        assertCandidate(attempt);
    } finally {
        if (installer && !installer.closed) await terminateOwnedTree(installer);
        closeSync(output);
        closeSync(errors);
    }
    if (attempt.loader === 'fabric') {
        const serverLauncher = join(attempt.serverDirectory, 'fabric-server-launch.jar');
        if (!existsSync(serverLauncher)) throw new Error('Fabric installer produced no dedicated-server launcher.');
        return ['-jar', serverLauncher, 'nogui'];
    }
    const namespace = attempt.loader === 'forge' ? 'net/minecraftforge/forge' : 'net/neoforged/neoforge';
    const argumentPath = join(attempt.serverDirectory, 'libraries', namespace, attempt.loaderVersion, 'win_args.txt');
    if (existsSync(argumentPath)) return [`@${argumentPath}`, 'nogui'];
    const expectedNames = attempt.loader === 'forge' ? [`forge-${attempt.loaderVersion}.jar`, `forge-${attempt.version}-${attempt.loaderVersion}-server.jar`]
        : [`neoforge-${attempt.loaderVersion}-server.jar`, `neoforge-${attempt.loaderVersion}.jar`];
    const legacyJar = expectedNames.map(name => join(attempt.serverDirectory, name)).find(existsSync);
    if (!legacyJar) throw new Error('Official installer produced no expected dedicated-server argument file or legacy launcher.');
    return ['-jar', legacyJar, 'nogui'];
}

function gameLogs(attempt) {
    return ['stdout.log', 'stderr.log', 'server/logs/latest.log'].map(relative => join(attempt.directory, relative))
        .filter(existsSync).map(path => readFileSync(path, 'utf8')).join('\n');
}

function runtimeSignals(attempt) {
    const logs = gameLogs(attempt);
    const gcPath = join(attempt.directory, 'gc.log');
    const gc = existsSync(gcPath) ? readFileSync(gcPath, 'utf8') : '';
    return { logs, gc, runtimeStarted: /\[MemorySweep\] universal runtime started/.test(logs),
        dedicatedServerStarted: /Done \([^)]+\)!/.test(logs),
        serverCleanupCount: (logs.match(/\[MemorySweep\] server cleanup completed/g) ?? []).length,
        explicitGcCount: (gc.match(/System\.gc\(\)/g) ?? []).length,
        serverTickActive: /\[MemorySweep\] server tick active/.test(logs) };
}

async function runServer(attempt, java, gameArgs) {
    writeFileSync(join(attempt.serverDirectory, 'eula.txt'), 'eula=true\n');
    writeFileSync(join(attempt.serverDirectory, 'server.properties'), [
        'server-ip=127.0.0.1', 'server-port=0', 'online-mode=false', 'white-list=true', 'enforce-whitelist=true',
        'max-players=1', 'view-distance=4', 'simulation-distance=4', 'spawn-protection=0', 'generate-structures=false',
        'spawn-npcs=false', 'spawn-animals=false', 'spawn-monsters=false', 'allow-nether=false', 'level-name=smoke-world',
        'sync-chunk-writes=false', 'enable-query=false', 'enable-rcon=false', 'enable-status=false', 'motd=MemorySweep isolated smoke test', ''
    ].join('\n'));
    writeFileSync(join(attempt.serverDirectory, 'config/memorysweep.toml'), 'memory_sweep = true\nsweep_interval_seconds = 5\nsilent = true\n', { flag: 'wx' });
    const gcArgs = java.version === 8 ? ['-XX:+PrintGCDetails', '-XX:+PrintGCDateStamps', '-XX:+PrintGCTimeStamps', '-Xloggc:../gc.log']
        : ['-Xlog:gc*=info:file=../gc.log:time,uptime,level,tags'];
    const args = ['-Xms512m', '-Xmx2048m', ...gcArgs, ...gameArgs];
    immutableJson(join(attempt.directory, 'launch.json'), { candidateSha256: expectedSha256, java, loaderVersion: attempt.loaderVersion, args,
        binding: '127.0.0.1', port: 0, note: 'Port zero requests an OS-assigned listening port; whitelist is empty and status/RCON/query are disabled.' });
    const output = openSync(join(attempt.directory, 'stdout.log'), 'wx');
    const errors = openSync(join(attempt.directory, 'stderr.log'), 'wx');
    let runtime;
    let result = { status: 'server-functionality-incomplete' };
    try {
        assertCandidate(attempt);
        runtime = startProcess(java.path, args, { cwd: attempt.serverDirectory, stdio: ['pipe', output, errors] });
        runtime.child.stdin.on('error', error => { result.stdinError = error.message; });
        let deadline = Date.now() + 300000;
        let startupSnapshot;
        let verified = false;
        while (Date.now() < deadline && !interrupted && !runtime.exited && !runtime.error) {
            await delay(1000);
            const signals = runtimeSignals(attempt);
            if (signals.dedicatedServerStarted && !startupSnapshot) {
                startupSnapshot = { observedAt: new Date().toISOString(), cleanups: signals.serverCleanupCount, explicitCollections: signals.explicitGcCount };
                deadline = Date.now() + 60000;
            }
            if (startupSnapshot && signals.runtimeStarted && signals.serverCleanupCount > startupSnapshot.cleanups
                    && signals.explicitGcCount > startupSnapshot.explicitCollections && !runtime.exited && pidAlive(runtime.pid)) {
                verified = true;
                break;
            }
        }
        const signals = runtimeSignals(attempt);
        result = { ...result, runtimeStarted: signals.runtimeStarted, dedicatedServerStarted: signals.dedicatedServerStarted,
            serverTickActive: signals.serverTickActive, serverCleanupCount: signals.serverCleanupCount,
            explicitGcCount: signals.explicitGcCount, cleanupAndGcAfterStartup: verified, startupSnapshot: startupSnapshot ?? null,
            aliveBeforeStop: !runtime.exited && !runtime.error && pidAlive(runtime.pid), runtimeBeforeStop: processRecord(runtime),
            diagnostic: signals.logs.split(/\r?\n/).filter(line => /MemorySweep|memorysweep|Done \(|ERROR|Exception|Caused by/.test(line)).slice(-45) };
        assertCandidate(attempt);
        if (!runtime.exited && !runtime.error && !interrupted) {
            runtime.child.stdin.write('stop\n');
            runtime.child.stdin.end();
            result.gracefulStopRequested = true;
            const stopped = await Promise.race([runtime.completion, delay(30000).then(() => 'timeout')]);
            result.gracefulStop = stopped !== 'timeout' && runtime.exited && runtime.code === 0 && !runtime.signal && !runtime.error;
        }
        result.teardown = await terminateOwnedTree(runtime);
        result.runtimeAfterStop = processRecord(runtime);
        result.status = interrupted ? 'interrupted' : verified && result.gracefulStop && result.teardown.confirmed ? 'dedicated-server-core-verified'
            : runtime.error ? 'launch-error' : verified ? 'shutdown-failed' : signals.runtimeStarted ? 'server-functionality-incomplete' : 'startup-failed';
        if (!verified && Date.now() >= deadline) result.error = startupSnapshot
            ? 'No new server cleanup and explicit GC within 60 seconds after startup.'
            : 'Server startup exceeded 300 seconds.';
        if (runtime.error) result.error = runtime.error;
    } finally {
        if (runtime && !runtime.closed) await terminateOwnedTree(runtime);
        closeSync(output);
        closeSync(errors);
    }
    return result;
}

async function testCaseRun(testCase) {
    if (interrupted) return;
    const attemptId = randomUUID();
    const directory = join(evidenceRoot, `${testCase.version}-${testCase.loader}`, 'attempts', attemptId);
    const serverDirectory = join(directory, 'server');
    mkdirSync(join(serverDirectory, 'mods'), { recursive: true });
    mkdirSync(join(serverDirectory, 'config'), { recursive: true });
    const modPath = join(serverDirectory, 'mods/memorysweep-universal-3.0.0.jar');
    copyFileSync(jarPath, modPath);
    const attempt = { ...testCase, schemaVersion: 1, attemptId, directory, serverDirectory, modPath, sha256: expectedSha256.toLowerCase(),
        createdAt: new Date().toISOString(), eulaAcceptedExplicitly: true, downloads: [] };
    immutableJson(join(directory, 'attempt.json'), attempt);
    let stage = 'installation';
    let timer;
    let result;
    try {
        assertCandidate(attempt);
        const java = javaFor(testCase.version);
        const metadata = launcher.getVersion(versionsData, testCase.version);
        if (!metadata || !launcher.loaders(metadata).includes(testCase.loader)) throw new Error('Requested combination absent from loader catalog.');
        const installationDeadline = new AbortController();
        timer = setTimeout(() => installationDeadline.abort(new Error('Installation exceeded 360 seconds.')), 360000);
        const gameArgs = await install(attempt, metadata, java, AbortSignal.any([cancellation.signal, installationDeadline.signal]));
        clearTimeout(timer);
        immutableJson(join(directory, 'installation.json'), { ...attempt, java, gameArgs, completedAt: new Date().toISOString() });
        stage = 'server';
        result = await runServer(attempt, java, gameArgs);
    } catch (error) {
        result = { status: interrupted ? 'interrupted' : stage === 'installation' ? 'installation-failed' : 'harness-error', stage, error: error.message };
    } finally {
        clearTimeout(timer);
        try { assertCandidate(attempt); } catch (error) { result = { ...result, status: 'harness-error', error: error.message }; }
        const record = { ...attempt, ...result, finishedAt: new Date().toISOString(), client: 'not-tested', hud: 'not-tested',
            multiplayer: 'not-tested', configurationUi: 'not-tested' };
        immutableJson(join(directory, 'result.json'), record);
        writeFileSync(join(dirname(dirname(directory)), 'result.json'), JSON.stringify(record, null, 2));
        if (record.status !== 'dedicated-server-core-verified') process.exitCode = 1;
        console.log(`${testCase.version} ${testCase.loader}: ${record.status} (${attemptId})`);
    }
}

for (const testCase of cases) {
    if (interrupted) break;
    await testCaseRun(testCase);
}
if (signalCleanup) await signalCleanup;
