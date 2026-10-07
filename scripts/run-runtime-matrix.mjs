import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync, appendFileSync, openSync, closeSync, copyFileSync, statSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const workspace = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const packagePath = process.env.MEMORYSWEEP_LAUNCHER ?? join(process.env.APPDATA, 'npm/node_modules/minecraft-mod-mcp/dist/index.js');
const launcher = await import(pathToFileURL(packagePath));
const loaderInstaller = await import(pathToFileURL(join(dirname(packagePath), 'chunk-EJLS3AM5.js')));
const evidenceRoot = join(workspace, process.argv.includes('--ui') ? 'test-results/runtime-ui-matrix' : 'test-results/runtime-matrix');
const jarPath = join(workspace, 'universal/build/libs/memorysweep-universal-3.0.0.jar');
const artifactSha256 = () => createHash('sha256').update(readFileSync(jarPath)).digest('hex');
const sha256 = artifactSha256();
const versionsData = launcher.loadVersionsData();
const argumentsList = process.argv.slice(2);
const argument = name => argumentsList.find(value => value.startsWith(`--${name}=`))?.split('=').slice(1).join('=');
const mode = argument('mode') ?? 'matrix';
const versions = launcher.getVersions(versionsData).filter(version => {
    const major = Number(version.mc_version.split('.')[0]);
    const minor = Number(version.mc_version.split('.')[1]);
    return (major === 1 && minor >= 16) || (major === 26 && minor <= 3);
});
const selectedVersions = argument('versions')?.split(',');
const selectedLoaders = argument('loaders')?.split(',');
const catalog = versions.flatMap(version => launcher.loaders(version).map(loader => ({ version: version.mc_version, loader })));
const cases = catalog.filter(testCase => !selectedVersions || selectedVersions.includes(testCase.version))
    .filter(testCase => !selectedLoaders || selectedLoaders.includes(testCase.loader));
const children = new Set();
let activeAttempt;
let interrupted = false;
let shutdownPromise;
mkdirSync(evidenceRoot, { recursive: true });

function resultPath(testCase) {
    return join(evidenceRoot, `${testCase.version}-${testCase.loader}`, 'result.json');
}

function attemptDirectory(testCase, attemptId) {
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(attemptId ?? '')) {
        throw new Error('A UUID --attempt-id is required');
    }
    return join(dirname(resultPath(testCase)), 'attempts', attemptId);
}

function createAttempt(testCase) {
    const attemptId = randomUUID();
    const directory = attemptDirectory(testCase, attemptId);
    mkdirSync(dirname(directory), { recursive: true });
    mkdirSync(directory);
    const attempt = { ...testCase, schemaVersion: 4, attemptId, sha256, createdAt: new Date().toISOString(), directory };
    writeFileSync(join(directory, 'attempt.json'), JSON.stringify(attempt, null, 2), { flag: 'wx' });
    return attempt;
}

function readAttempt(testCase) {
    const attemptId = argument('attempt-id');
    const directory = attemptDirectory(testCase, attemptId);
    const attempt = JSON.parse(readFileSync(join(directory, 'attempt.json'), 'utf8'));
    if (attempt.schemaVersion !== 4 || attempt.attemptId !== attemptId || attempt.version !== testCase.version
            || attempt.loader !== testCase.loader || resolve(attempt.directory) !== directory
            || attempt.sha256 !== argument('expected-sha256')) {
        throw new Error('Attempt ownership or expected SHA-256 mismatch');
    }
    return attempt;
}

function assertArtifact(attempt) {
    if (artifactSha256() !== attempt.sha256) throw new Error('Artifact SHA-256 changed during this attempt');
}

function attemptRecord(attempt, result) {
    return { ...result, version: attempt.version, loader: attempt.loader, schemaVersion: 4,
        attemptId: attempt.attemptId, attemptPath: attempt.directory, sha256: attempt.sha256 };
}

function persist(attempt, result) {
    const record = attemptRecord(attempt, result);
    writeFileSync(join(attempt.directory, 'result.json'), JSON.stringify(record, null, 2), { flag: 'wx' });
    writeFileSync(resultPath(attempt), JSON.stringify(record, null, 2));
}

function workerResult(attempt, result) {
    writeFileSync(join(attempt.directory, 'worker-result.json'), JSON.stringify(attemptRecord(attempt, result), null, 2), { flag: 'wx' });
}

function trackSpawn(executable, args, options) {
    const child = spawn(executable, args, options);
    const state = { child, pid: child.pid, exited: false, code: null, signal: null, error: null,
        intentionalTeardown: false, startedAt: new Date().toISOString() };
    children.add(state);
    state.completion = new Promise(complete => {
        child.once('exit', (code, signal) => {
            state.exited = true;
            state.exitedAt = new Date().toISOString();
            state.code = code;
            state.signal = signal;
        });
        child.once('error', error => { state.error = error.message; });
        child.once('close', (code, signal) => {
            state.closed = true;
            if (!state.exited && !state.error) {
                state.exited = true;
                state.code = code;
                state.signal = signal;
            }
            complete(state);
        });
    });
    return state;
}

function processState(state) {
    return { pid: state.pid, exited: state.exited, closed: state.closed ?? false, code: state.code, signal: state.signal,
        error: state.error, intentionalTeardown: state.intentionalTeardown };
}

function pidAlive(pid) {
    try {
        process.kill(pid, 0);
        return true;
    } catch (error) {
        if (error.code === 'ESRCH') return false;
        throw error;
    }
}

async function processTree(state) {
    const pid = state.pid;
    if (process.platform !== 'win32') return [pid];
    const query = trackSpawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
        "Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,@{Name='StartedAt';Expression={if ($_.CreationDate) {$_.CreationDate.ToUniversalTime().ToString('o')}}} | ConvertTo-Json -Compress"],
    { stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    query.child.stdout.on('data', data => { output += data; });
    query.child.stderr.resume();
    await query.completion;
    if (query.error || query.code !== 0) throw new Error(`Could not inspect process tree: ${query.error ?? query.code}`);
    const processes = JSON.parse(output || '[]');
    const root = processes.find(item => item.ProcessId === pid);
    if (root && pidAlive(pid) && state.exited) throw new Error(`PID ${pid} was reused after exit; refusing descendant termination`);
    const rootIsOriginal = root && Math.abs(Date.parse(root.StartedAt) - Date.parse(state.startedAt)) <= 3000;
    if (root && pidAlive(pid) && !rootIsOriginal) {
        throw new Error(`PID ${pid} ownership no longer matches this test`);
    }
    const earliest = rootIsOriginal ? Date.parse(root.StartedAt) : Date.parse(state.startedAt) - 20;
    const latest = state.exitedAt ? Date.parse(state.exitedAt) : Infinity;
    const ownedProcesses = processes.filter(item => Date.parse(item.StartedAt) >= earliest && Date.parse(item.StartedAt) <= latest);
    const pids = new Set([pid]);
    let found = true;
    while (found) {
        found = false;
        for (const item of ownedProcesses) {
            if (pids.has(item.ParentProcessId) && !pids.has(item.ProcessId)) {
                pids.add(item.ProcessId);
                found = true;
            }
        }
    }
    return [...pids];
}

async function terminateTree(state) {
    if (state.termination) return state.termination;
    state.termination = (async () => {
        if (!state.pid) {
            await state.completion;
            return { confirmed: true, spawnError: state.error };
        }
        const pids = (await processTree(state)).filter(pid => !state.closed || pid !== state.pid);
        if (!pids.some(pidAlive)) {
            await state.completion;
            return { confirmed: true, alreadyClosed: true, pids };
        }
        state.intentionalTeardown = true;
        const killers = [];
        if (process.platform === 'win32') {
            const targets = pids.filter(pidAlive).reverse();
            for (const pid of targets) {
                if (!pidAlive(pid)) continue;
                const killer = trackSpawn('taskkill.exe', ['/PID', String(pid), '/F'], { stdio: 'ignore' });
                killers.push(killer);
                await killer.completion;
            }
        } else {
            state.child.kill('SIGKILL');
        }
        for (let check = 0; check < 100; check += 1) {
            if (!pids.some(pidAlive)) {
                await state.completion;
                return { confirmed: true, pids, taskkill: killers.map(processState) };
            }
            await delay(100);
        }
        throw new Error(`Process tree termination not confirmed for PID ${state.pid}; taskkill=${killers.map(killer => killer.code).join(',')}`);
    })();
    return state.termination;
}

async function shutdown(signal) {
    if (shutdownPromise) return shutdownPromise;
    interrupted = true;
    shutdownPromise = (async () => {
        const errors = [];
        for (const state of [...children].reverse()) {
            if (!state.closed) {
                try { await terminateTree(state); } catch (error) { errors.push(error.message); }
            }
        }
        if (activeAttempt && mode !== 'install' && mode !== 'run' && !existsSync(join(activeAttempt.directory, 'result.json'))) {
            persist(activeAttempt, { status: 'harness-error', error: `Interrupted by ${signal}`, teardownErrors: errors,
                finishedAt: new Date().toISOString() });
        }
        process.exit(errors.length ? 1 : signal === 'SIGINT' ? 130 : 143);
    })();
    return shutdownPromise;
}

for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(signal, () => { void shutdown(signal); });

function javaFor(version) {
    const major = Number(version.split('.')[0]);
    const minor = Number(version.split('.')[1]);
    const target = major >= 26 ? 25 : minor >= 21 || (minor === 20 && Number(version.split('.')[2] ?? 0) >= 5) ? 21 : minor <= 16 ? 8 : 17;
    const candidates = launcher.detectJavas().filter(java => java.version === target);
    if (!candidates.length) throw new Error(`Java ${target} not installed`);
    return { path: join(candidates[0].path, 'bin/java.exe'), version: target };
}

function identifyLoader(versionJson) {
    const names = (versionJson.libraries ?? []).map(library => library.name);
    if (names.some(name => name.startsWith('net.neoforged:neoforge:')) || (versionJson.arguments?.game ?? []).includes('--fml.neoForgeVersion')) return 'neoforge';
    if (names.some(name => name.startsWith('net.fabricmc:fabric-loader:'))) return 'fabric';
    if (names.some(name => name.startsWith('net.minecraftforge:forge:') || name.startsWith('net.minecraftforge:fmlloader:')) || (versionJson.arguments?.game ?? []).includes('--fml.forgeVersion')) return 'forge';
    return 'unknown';
}

function validateVersionId(testCase, versionId) {
    if (!versionId || /[\\/]/.test(versionId) || versionId === '.' || versionId === '..') {
        throw new Error('Invalid manual version ID');
    }
    const raw = JSON.parse(readFileSync(join(launcher.versionsDir(), versionId, `${versionId}.json`), 'utf8'));
    if (raw.inheritsFrom !== testCase.version || identifyLoader(raw) !== testCase.loader) {
        throw new Error(`Version ${versionId} does not exactly inherit ${testCase.version} with loader ${testCase.loader}`);
    }
    return raw;
}

function exactVersionId(testCase) {
    return launcher.listInstalledVersions().find(versionId => {
        try {
            validateVersionId(testCase, versionId);
            return true;
        } catch {
            return false;
        }
    });
}

async function installExact(testCase) {
    const cached = exactVersionId(testCase);
    const metadata = launcher.getVersion(versionsData, testCase.version);
    const needsForgeRepair = testCase.loader === 'forge' && testCase.version.startsWith('1.16.')
        && !existsSync(join(launcher.librariesDir(), 'net/minecraftforge/forge', metadata.forge, `forge-${metadata.forge}-client.jar`));
    if (cached && !needsForgeRepair && !argumentsList.includes('--repair-install')) return cached;
    if (!existsSync(join(launcher.versionsDir(), testCase.version, `${testCase.version}.json`))) {
        const manifest = await launcher.fetchVersionManifest();
        const vanilla = manifest.versions.find(version => version.id === testCase.version);
        if (!vanilla) throw new Error('Vanilla release absent from Mojang manifest');
        await launcher.downloadVersion(await launcher.fetchVersionJson(vanilla.url), console.log);
    }
    const loaderVersion = testCase.loader === 'forge' ? metadata.forge : testCase.loader === 'neoforge'
        ? testCase.version === '1.20.4' ? '20.4.251' : metadata.neoforge : metadata.fabric_loader ?? '0.16.14';
    try {
        await loaderInstaller.downloadLoaderVersion(testCase.version, testCase.loader, loaderVersion, console.log);
    } catch (error) {
        if (!['forge', 'neoforge'].includes(testCase.loader) || !/unexpected end of file/i.test(error.message)) throw error;
        const installer = join(launcher.versionsDir(), '.tmp', `${testCase.loader}-${loaderVersion}-installer.jar`);
        const java = javaFor(testCase.version);
        console.log(`Launcher ZIP parser failed; using official ${testCase.loader} installer`);
        const official = trackSpawn(java.path, ['-jar', installer, '--installClient', launcher.mcDir()], { stdio: 'inherit' });
        await official.completion;
        if (official.error || !official.exited || official.code !== 0 || official.signal || official.intentionalTeardown) {
            throw new Error(`Official ${testCase.loader} installer failed: ${official.error ?? official.code ?? official.signal}`);
        }
    }
    const installed = exactVersionId(testCase);
    if (!installed) throw new Error(`Installer produced no exact ${testCase.loader} instance for ${testCase.version}`);
    return installed;
}

async function repairEmbeddedBootstrap(testCase, versionJson, command, directory) {
    if (testCase.loader !== 'forge') return;
    const metadata = launcher.getVersion(versionsData, testCase.version);
    const installer = join(launcher.versionsDir(), '.tmp', `forge-${metadata.forge}-installer.jar`);
    if (!existsSync(installer)) return;
    for (const library of versionJson.libraries ?? []) {
        const artifact = library.downloads?.artifact;
        if (!artifact || artifact.url || !artifact.sha1 || !library.name.startsWith('net.minecraftforge:forge:')) continue;
        const cached = join(launcher.librariesDir(), artifact.path);
        if (existsSync(cached) && createHash('sha1').update(readFileSync(cached)).digest('hex') === artifact.sha1) continue;
        const repaired = join(directory, 'launcher-repairs', artifact.path);
        const python = process.env.MEMORYSWEEP_PYTHON;
        if (!python) throw new Error('MEMORYSWEEP_PYTHON must point to the bundled Python for embedded Forge bootstrap repair');
        mkdirSync(dirname(repaired), { recursive: true });
        const extraction = trackSpawn(python, [join(workspace, 'scripts/extract-installer-artifact.py'), installer, `maven/${artifact.path}`, repaired, artifact.sha1], { stdio: 'inherit' });
        await extraction.completion;
        if (extraction.error || !extraction.exited || extraction.code !== 0 || extraction.signal || extraction.intentionalTeardown) {
            throw new Error('Could not extract verified Forge bootstrap');
        }
        command.args = command.args.map(value => value.split(cached).join(repaired));
        console.log(`Using verified embedded Forge bootstrap ${repaired}`);
    }
}

async function worker() {
    const testCase = { version: argument('version'), loader: argument('loader') };
    const attempt = readAttempt(testCase);
    activeAttempt = attempt;
    const directory = attempt.directory;
    writeFileSync(join(directory, `${mode}-start.json`), JSON.stringify(attemptRecord(attempt,
        { startedAt: new Date().toISOString() }), null, 2), { flag: 'wx' });
    if (mode === 'install') {
        try {
            assertArtifact(attempt);
            const manualVersionId = argument('version-id');
            const versionId = manualVersionId ? (validateVersionId(testCase, manualVersionId), manualVersionId) : await installExact(testCase);
            assertArtifact(attempt);
            writeFileSync(join(directory, 'installation.json'), JSON.stringify(attemptRecord(attempt,
                { versionId, subprocesses: [...children].map(processState), finishedAt: new Date().toISOString() }), null, 2), { flag: 'wx' });
        } catch (error) {
            writeFileSync(join(directory, 'installation.json'), JSON.stringify(attemptRecord(attempt,
                { error: error.message, finishedAt: new Date().toISOString() }), null, 2), { flag: 'wx' });
            process.exitCode = 1;
        }
        return;
    }
    let runtime;
    let stdout;
    let stderr;
    let result = { startedAt: new Date().toISOString(), status: 'harness-error' };
    const gameDirectory = join(directory, 'game');
    const gcFile = join(directory, 'gc.log');
    try {
        assertArtifact(attempt);
        mkdirSync(join(gameDirectory, 'mods'), { recursive: true });
        mkdirSync(join(gameDirectory, 'config'), { recursive: true });
        copyFileSync(jarPath, join(gameDirectory, 'mods/memorysweep-universal-3.0.0.jar'));
        if (createHash('sha256').update(readFileSync(join(gameDirectory, 'mods/memorysweep-universal-3.0.0.jar'))).digest('hex') !== attempt.sha256) {
            throw new Error('Copied artifact SHA-256 does not match attempt');
        }
        writeFileSync(join(gameDirectory, 'config/memorysweep.toml'), 'memory_sweep = true\nsweep_interval_seconds = 5\nsilent = true\n');
        writeFileSync(join(gameDirectory, 'options.txt'), 'guiScale:2\nfullscreen:false\nmaxFps:30\nrenderDistance:4\nonboardAccessibility:false\n');
        stdout = openSync(join(directory, 'stdout.log'), 'wx');
        stderr = openSync(join(directory, 'stderr.log'), 'wx');
        const versionId = argument('version-id') ?? exactVersionId(testCase);
        if (!versionId) throw new Error(`No exact ${testCase.loader} instance is installed for ${testCase.version}`);
        validateVersionId(testCase, versionId);
        const versionJson = launcher.loadVersionMerged(versionId);
        const actualLoader = identifyLoader(versionJson);
        if (actualLoader !== testCase.loader) throw new Error(`Loader mismatch: expected ${testCase.loader}, got ${actualLoader}`);
        const java = javaFor(testCase.version);
        const command = launcher.buildLaunchCommand({
            versionId, loader: testCase.loader, mcDir: gameDirectory,
            javaPath: java.path, maxMemoryMb: 2048, minMemoryMb: 512,
            playerName: 'MemorySweepTest', uuid: '00000000000000000000000000000001',
            accessToken: '0', userType: 'legacy', width: 854, height: 480
        }, versionJson, versionsData);
        if (testCase.loader === 'forge' && versionJson.mainClass.startsWith('net.minecraftforge.bootstrap.')) {
            const vanillaJar = join(launcher.versionsDir(), testCase.version, `${testCase.version}.jar`);
            const classpathIndex = command.args.findIndex(value => value === '-cp' || value === '-classpath');
            if (classpathIndex >= 0) command.args[classpathIndex + 1] = command.args[classpathIndex + 1]
                .split(';').filter(value => resolve(value) !== resolve(vanillaJar)).join(';');
        }
        await repairEmbeddedBootstrap(testCase, versionJson, command, directory);
        const gcArgs = java.version === 8
            ? ['-XX:+PrintGCDetails', '-XX:+PrintGCDateStamps', '-Xloggc:../gc.log']
            : ['-Xlog:gc:file=../gc.log:time,level,tags'];
        const probeSource = resolve(workspace, argument('probe') ?? 'test-results/ui-probe/ui-probe.jar');
        if (!existsSync(probeSource)) throw new Error('The compiled UI probe is required for every client launch');
        const probe = join(directory, 'ui-probe.jar');
        copyFileSync(probeSource, probe);
        const probeSha256 = createHash('sha256').update(readFileSync(probe)).digest('hex');
        const collectorArgs = argument('gc') === 'g1' ? ['-XX:+UseG1GC', '-XX:+ExplicitGCInvokesConcurrent', '-XX:-DisableExplicitGC'] : [];
        command.args.unshift(...collectorArgs, ...gcArgs, '-Dmixin.debug.verbose=true', `-DlibraryDirectory=${launcher.librariesDir()}`, `-javaagent:${probe}${argumentsList.includes('--ui') ? '' : '=observe'}`);
        if (java.version === 8) {
            const filteredArgs = [];
            for (let index = 0; index < command.args.length; index += 1) {
                const value = command.args[index];
                if (/^--add-(opens|exports|modules)$/.test(value)) {
                    index += 1;
                } else if (!/^--add-(opens|exports|modules)=/.test(value)) {
                    filteredArgs.push(value);
                }
            }
            command.args = filteredArgs;
        }
        assertArtifact(attempt);
        writeFileSync(join(directory, 'launch.json'), JSON.stringify(attemptRecord(attempt,
            { java: java.path, javaVersion: java.version, versionId, probeSha256, args: command.args }), null, 2), { flag: 'wx' });
        runtime = trackSpawn(java.path, command.args, { cwd: gameDirectory, stdio: ['ignore', stdout, stderr] });
        const duration = Number(argument('seconds') ?? 90);
        for (let elapsed = 0; elapsed < duration && !interrupted; elapsed += 2) {
            await delay(2000);
            const logs = gatherLogs(directory);
            const signals = classify(logs, existsSync(gcFile) ? readFileSync(gcFile, 'utf8') : '');
            const uiComplete = !argumentsList.includes('--ui') || /\[MemorySweepTest\] (?:gui-click-persistence-verified(?:\r?$)|gui-click-persistence-failed=|native-fabric-no-gui-verified|native-screen-not-supported|options-button-absent|toggle-persisted=|ui-harness-error|ui-timeout)/m.test(logs);
            if (runtime.exited || runtime.error || signals.loaderScreenError
                    || (signals.titleScreenReady && signals.cleanupCompleted && signals.explicitGc && uiComplete)) break;
        }
        assertArtifact(attempt);
        const logs = gatherLogs(directory);
        const signals = classify(logs, existsSync(gcFile) ? readFileSync(gcFile, 'utf8') : '');
        const alive = !interrupted && !runtime.exited && !runtime.error && !runtime.intentionalTeardown && Boolean(runtime.pid) && pidAlive(runtime.pid);
        result = {
            ...result, ...signals, versionId, actualLoader, java: java.version, probeSha256, collectorMode: argument('gc') ?? 'jvm-default', runtime: processState(runtime), alive,
            status: signals.loaderScreenError ? 'loader-screen-error'
                : signals.runtimeStarted && signals.configValuesVerified && signals.loadingOverlayGone && signals.titleScreenReady && signals.cleanupCompleted && signals.explicitGc && alive
                    ? 'client-core-verified'
                    : signals.runtimeStarted ? 'initialized-functionality-incomplete'
                        : /MixinApplyError|InvalidMixinException|InvalidInjectionException|Failed to create mod instance[^\r\n]*ModID: memorysweep|Could not execute entrypoint[^\r\n]*memorysweep/i.test(logs)
                            ? 'mod-error' : 'environment-error',
            ui: signals.loaderScreenError ? 'loader-screen-error' : !argumentsList.includes('--ui') ? 'not-tested'
                : /\[MemorySweepTest\] native-fabric-no-gui-verified\r?$/m.test(logs) ? 'no-config-interface-verified'
                    : /\[MemorySweepTest\] native-screen-not-supported/.test(logs) ? 'native-screen-not-supported'
                        : /\[MemorySweepTest\] gui-click-persistence-verified\r?$/m.test(logs) ? 'native-click-persistence-verified'
                    : /\[MemorySweepTest\] gui-click-persistence-failed=/.test(logs) ? 'native-click-persistence-failed'
                        : /\[MemorySweepTest\] toggle-persisted=true\r?$/m.test(logs) ? 'callback-persistence-verified'
                            : /\[MemorySweepTest\] toggle-persisted=false\r?$/m.test(logs) ? 'toggle-not-persisted'
                                : /options-button-absent/.test(logs) ? 'button-absent' : /ui-harness-error/.test(logs) ? 'harness-error' : 'incomplete',
            uiInteraction: {
                visible: /\[MemorySweepTest\] ui-visible=true\r?$/m.test(logs),
                active: /\[MemorySweepTest\] ui-active=true\r?$/m.test(logs),
                boundsValid: /\[MemorySweepTest\] ui-bounds-check=true\r?$/m.test(logs),
                noOverlap: /\[MemorySweepTest\] ui-overlap=false\r?$/m.test(logs),
                nativeClickAccepted: /\[MemorySweepTest\] ui-native-click-accepted=true\r?$/m.test(logs)
            },
            configuration: {
                nativeRegistered: /\[MemorySweep\] native-config-registered loader=/.test(logs),
                nativeLoaded: /\[MemorySweepTest\] native-config-loaded=true/.test(logs),
                factoryRegistered: /\[MemorySweepTest\] native-factory-registered=true/.test(logs),
                optionsButtonAbsent: /\[MemorySweepTest\] native-options-button-absent=true/.test(logs),
                saveVerified: /\[MemorySweepTest\] native-config-save-verified/.test(logs),
                cancelVerified: /\[MemorySweepTest\] native-config-cancel-verified/.test(logs),
                noInterfaceVerified: /\[MemorySweepTest\] native-fabric-no-gui-verified/.test(logs),
                nativeScreenUnavailable: /\[MemorySweep\] native-screen-unavailable/.test(logs),
                nativeRegistrationFailed: /\[MemorySweep\] native-(?:registration|screen-registration)-failed/.test(logs)
            },
            uiDiagnostics: logs.split(/\r?\n/).filter(line => line.includes('[MemorySweepTest]')),
            optionsScreenOpened: /\[MemorySweepTest\] options-screen-opened/.test(logs),
            hud: 'not-tested', singleplayer: 'not-tested', multiplayer: 'not-tested', dedicatedServer: 'not-tested',
            error: runtime.error,
            diagnostic: logs.split(/\r?\n/).filter(line => /MemorySweep|memorysweep|ERROR|Exception|Caused by|Cannot find launch|UnsupportedClassVersion|Error occurred|Could not find|Could not reserve/.test(line)).slice(-35)
        };
    } catch (error) {
        result = { ...result, status: 'harness-error', error: error.message };
        process.exitCode = 1;
    } finally {
        try {
            if (runtime) result.teardown = await terminateTree(runtime);
            assertArtifact(attempt);
        } catch (error) {
            result = { ...result, status: 'harness-error', error: error.message };
            process.exitCode = 1;
        }
        if (stdout !== undefined) closeSync(stdout);
        if (stderr !== undefined) closeSync(stderr);
        workerResult(attempt, { ...result, finishedAt: new Date().toISOString(), subprocesses: [...children].map(processState) });
        console.log(`${testCase.version} ${testCase.loader}: ${result.status}`);
    }
}

function gatherLogs(directory) {
    return ['stdout.log', 'stderr.log', 'game/logs/latest.log', 'game/logs/debug.log']
        .map(relative => join(directory, relative)).filter(existsSync).map(path => readFileSync(path, 'utf8')).join('\n');
}

function classify(logs, gc) {
    const runtimeStarted = /\[MemorySweep\] universal runtime started/.test(logs);
    return {
        modRecognized: runtimeStarted || logs.split(/\r?\n/).some(line => /^\s*-\s+memorysweep\s+[^/\\]+$/i.test(line)
            || /^\s*MemorySweep(?: Universal)?\s+[^/\\]+\(memorysweep\)\s*$/i.test(line)),
        runtimeStarted,
        configValuesVerified: /\[MemorySweepTest\] config-values=enabled:true,interval:5,silent:true/.test(logs),
        clientTickActive: /\[MemorySweep\] client tick active/.test(logs),
        cleanupCompleted: /\[MemorySweep\] client cleanup completed/.test(logs),
        explicitGc: /System\.gc\(\)/.test(gc),
        loadingOverlayGone: /\[MemorySweepTest\] loading-overlay-gone/.test(logs),
        titleScreenReady: /\[MemorySweepTest\] title-screen-ready/.test(logs),
        loaderScreenError: /\[MemorySweepTest\] loader-screen-error/.test(logs),
        resourcesReady: /Sound engine started|Created: .*atlas|OpenAL initialized/.test(logs),
        clientMixinApplied: /Mixing UniversalClientMixin/.test(logs),
        optionsMixinApplied: /Mixing UniversalOptionsMixin/.test(logs),
        configReadEvidence: logs.split(/\r?\n/).map(line => line.match(/\[MemorySweep\] universal runtime started at\s+(.+)$/)?.[1]).filter(Boolean)
    };
}

async function runChild(attempt, childMode, seconds, versionId) {
    assertArtifact(attempt);
    const output = openSync(join(attempt.directory, `${childMode}-harness.log`), 'wx');
    const flags = ['--ui', '--repair-install'].filter(flag => argumentsList.includes(flag));
    if (argument('gc')) flags.push(`--gc=${argument('gc')}`);
    if (argument('probe')) flags.push(`--probe=${argument('probe')}`);
    let child;
    let outcome;
    try {
        child = trackSpawn(process.execPath, [fileURLToPath(import.meta.url), `--mode=${childMode}`, `--version=${attempt.version}`,
            `--loader=${attempt.loader}`, `--seconds=${seconds}`, `--attempt-id=${attempt.attemptId}`,
            `--expected-sha256=${attempt.sha256}`, ...flags, ...(versionId ? [`--version-id=${versionId}`] : [])],
        { stdio: ['ignore', output, output] });
        let timer;
        const deadline = new Promise(complete => {
            timer = setTimeout(() => complete('timeout'), (childMode === 'install' ? 360 : seconds + 30) * 1000);
        });
        const completed = await Promise.race([child.completion, deadline]);
        clearTimeout(timer);
        if (completed === 'timeout') {
            const teardown = await terminateTree(child);
            outcome = { ...processState(child), timeout: true, teardown };
        } else {
            const teardown = await terminateTree(child);
            outcome = { ...processState(child), teardown };
        }
        assertArtifact(attempt);
        return { ...outcome, startedAt: child.startedAt, finishedAt: new Date().toISOString() };
    } catch (error) {
        outcome = { ...(child ? processState(child) : {}), error: error.message };
        if (child && !child.closed) {
            try { outcome.teardown = await terminateTree(child); } catch (cleanupError) { outcome.teardownError = cleanupError.message; }
        }
        return outcome;
    } finally {
        closeSync(output);
    }
}

function readOwnedRecord(attempt, file, startedAt) {
    const path = join(attempt.directory, file);
    const result = JSON.parse(readFileSync(path, 'utf8'));
    const started = Date.parse(startedAt);
    if (!Number.isFinite(started) || started < Date.parse(attempt.createdAt)
            || result.schemaVersion !== 4 || result.attemptId !== attempt.attemptId || result.sha256 !== attempt.sha256
            || result.version !== attempt.version || result.loader !== attempt.loader
            || resolve(result.attemptPath ?? '') !== attempt.directory
            || !Number.isFinite(Date.parse(result.finishedAt)) || Date.parse(result.finishedAt) < started
            || statSync(path).mtimeMs < started
            || (file === 'worker-result.json' && (!Number.isFinite(Date.parse(result.startedAt)) || Date.parse(result.startedAt) < started))) {
        throw new Error(`Missing fresh attempt-owned ${file}`);
    }
    return result;
}

function currentResult(testCase, expectedSha256) {
    if (!existsSync(resultPath(testCase))) return { ...testCase, status: 'not-tested' };
    try {
        const result = JSON.parse(readFileSync(resultPath(testCase), 'utf8'));
        if (result.sha256 !== expectedSha256 || result.schemaVersion !== 4
                || result.version !== testCase.version || result.loader !== testCase.loader) {
            return { ...testCase, status: 'stale-needs-retest' };
        }
        const directory = attemptDirectory(testCase, result.attemptId);
        const owned = JSON.parse(readFileSync(join(directory, 'result.json'), 'utf8'));
        if (resolve(result.attemptPath ?? '') !== directory || JSON.stringify(owned) !== JSON.stringify(result)) {
            return { ...testCase, status: 'stale-needs-retest' };
        }
        if (result.actualLoader && result.actualLoader !== testCase.loader) return { ...testCase, status: 'invalid-loader-result' };
        if (result.status === 'client-core-verified' && (!result.workerExit?.exited || result.workerExit.code !== 0
                || result.workerExit.signal || result.workerExit.intentionalTeardown || !result.runtimeStarted || !result.configValuesVerified || !result.loadingOverlayGone || !result.titleScreenReady
                || !result.cleanupCompleted || !result.explicitGc || !result.alive || result.loaderScreenError
                || result.runtime?.exited || result.runtime?.intentionalTeardown || result.runtime?.error || !result.teardown?.confirmed)) {
            return { ...testCase, status: 'stale-needs-retest' };
        }
        return result;
    } catch {
        return { ...testCase, status: 'stale-needs-retest' };
    }
}

function summary() {
    const currentSha256 = artifactSha256();
    const results = catalog.map(testCase => currentResult(testCase, currentSha256));
    writeFileSync(join(evidenceRoot, 'matrix.json'), JSON.stringify({ schemaVersion: 4, generatedAt: new Date().toISOString(), currentJarSha256: currentSha256, results }, null, 2));
    const lines = ['# Runtime Matrix', '', `JAR SHA-256: \`${currentSha256}\``, '',
        'Client-only title-screen, cleanup and explicit-GC checks while the process is alive. Resources and Mixin application are informational, not proof of injection callbacks. Optional callback persistence does not prove overall UI functionality. No row proves HUD, singleplayer, multiplayer or dedicated-server functionality.', '',
        '| Minecraft | Loader | Collector | Status | Entry | Title | Cleanup | Explicit GC | Alive | Client Mixin | Options Mixin | UI |',
        '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |'];
    for (const result of results) lines.push(`| ${result.version} | ${result.loader} | ${result.status === 'not-tested' || result.status === 'stale-needs-retest' ? '-' : result.collectorMode ?? 'jvm-default'} | ${result.status} | ${result.runtimeStarted ?? '-'} | ${result.titleScreenReady ?? '-'} | ${result.cleanupCompleted ?? '-'} | ${result.explicitGc ?? '-'} | ${result.alive ?? '-'} | ${result.clientMixinApplied ?? '-'} | ${result.optionsMixinApplied ?? '-'} | ${result.ui ?? '-'} |`);
    writeFileSync(join(evidenceRoot, 'MATRIX.md'), lines.join('\n') + '\n');
    return results;
}

if (mode === 'install' || mode === 'run') {
    try { await worker(); } catch (error) {
        console.error(`Worker protocol error: ${error.message}`);
        process.exitCode = 1;
    }
} else if (mode === 'catalog') {
    console.log(JSON.stringify(cases, null, 2));
    console.log(`${cases.length} selected / ${catalog.length} Minecraft/loader combinations`);
} else if (mode === 'summary') {
    const results = summary();
    console.log(JSON.stringify(results.reduce((counts, result) => ({ ...counts, [result.status]: (counts[result.status] ?? 0) + 1 }), {}), null, 2));
} else {
    for (const [index, testCase] of cases.entries()) {
        if (interrupted) break;
        const previous = currentResult(testCase, sha256);
        const needsRetry = argumentsList.includes('--retry-failures') && previous.status !== 'client-core-verified';
        if (!argumentsList.includes('--force') && previous.schemaVersion === 4 && !needsRetry) continue;
        const attempt = createAttempt(testCase);
        activeAttempt = attempt;
        let result;
        let installed;
        let outcome;
        try {
            installed = await runChild(attempt, 'install', 0, argument('version-id'));
            if (!installed.exited || installed.code !== 0 || installed.signal || installed.timeout || installed.error || installed.intentionalTeardown) {
                let detail;
                if (installed.exited && !installed.timeout && existsSync(join(attempt.directory, 'installation.json'))) {
                    detail = readOwnedRecord(attempt, 'installation.json', installed.startedAt).error;
                }
                throw new Error(`Install worker failed: ${detail ?? installed.error ?? (installed.timeout ? 'timeout' : `code=${installed.code} signal=${installed.signal}`)}`);
            }
            const installation = readOwnedRecord(attempt, 'installation.json', installed.startedAt);
            if (installation.error || !installation.versionId) throw new Error(installation.error ?? 'Installation result lacks versionId');
            validateVersionId(testCase, installation.versionId);
            outcome = await runChild(attempt, 'run', Number(argument('seconds') ?? 90), installation.versionId);
            appendFileSync(join(attempt.directory, 'parent-harness.log'), JSON.stringify({ install: installed, run: outcome }) + '\n');
            const candidate = readOwnedRecord(attempt, 'worker-result.json', outcome.startedAt);
            if (!outcome.exited || outcome.code !== 0 || outcome.signal || outcome.timeout || outcome.error || outcome.intentionalTeardown) {
                result = { ...candidate, status: 'harness-error', installationWorkerExit: installed, workerExit: outcome,
                    error: outcome.error ?? (outcome.timeout ? 'Runtime worker timed out' : `Runtime worker code=${outcome.code} signal=${outcome.signal}`) };
            } else {
                result = { ...candidate, installationWorkerExit: installed, workerExit: outcome };
            }
            assertArtifact(attempt);
        } catch (error) {
            result = { status: error.message.startsWith('Install worker failed:') ? 'installation-blocked' : 'harness-error', installationWorkerExit: installed, workerExit: outcome,
                error: error.message, finishedAt: new Date().toISOString() };
            appendFileSync(join(attempt.directory, 'parent-harness.log'), `${error.message}\n`);
        }
        persist(attempt, result);
        activeAttempt = undefined;
        summary();
        console.log(`[${index + 1}/${cases.length}] ${testCase.version} ${testCase.loader}: ${result.status} (${attempt.attemptId})`);
    }
    summary();
}
