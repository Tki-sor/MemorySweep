# Native configuration and runtime evidence

## Interface policy

| Loader | Storage | Client interface |
| --- | --- | --- |
| Forge | Native `ForgeConfigSpec`, local `COMMON` TOML | **Mods -> MemorySweep -> Config**, using MemorySweep's page with vanilla widgets. Forge exposes a factory entrypoint, not NeoForge's automatic generic page. |
| NeoForge | Native `ModConfigSpec`, local `COMMON` TOML | The loader's own `ConfigurationScreen` where its API exists. |
| Fabric | TOML in Fabric Loader's config directory | **No configuration interface**, no Options button, no Mod Menu dependency. |

Inspected NeoForge 20.4.251 and 20.6 libraries have no built-in generated page. Those versions retain native TOML and log `native-screen-unavailable`, without a substitute custom NeoForge page. Metadata acceptance is not evidence of UI compatibility.

Each JVM uses `config/memorysweep.toml`:

```toml
memory_sweep = true
sweep_interval_seconds = 900
silent = false
```

The native interval range is `0..2147483647`; zero disables periodic cleanup. The runtime reads loaded Forge/NeoForge values instead of overwriting loader-managed files. `COMMON` is local per JVM and not synchronized from a remote server. A dedicated server has its own file and heap; an integrated server shares the client JVM. Fabric reads its file at startup.

Forge edits a draft: Done saves, Cancel/Escape discards, invalid intervals disable Done. NeoForge retains its built-in immediate-value/Done-save semantics, not Forge's discard-on-cancel behavior. English and Simplified Chinese translations are included. Forge's page requires at least 240 x 200 logical GUI pixels.

## Representative runtime results

The local final runtime-tested candidate SHA-256 was:

```text
0fe70e00aa52d9bca273b3411aa7c3bb038e8210837313afd36662c24470b57b
```

The isolated local release checkout reproduced that exact full-JAR SHA-256 with Java 25.0.2 as the actual Gradle daemon/compiler and Gradle 9.1.0. A local Java 21 launcher had been overridden by the user-level Gradle JVM setting. The hosted CI artifact will still be checked independently after its build.

| Check | Cases | Result |
| --- | --- | --- |
| Forge Config entry, all three fields, Cancel and invalid input | 1.16.5, 1.18.2, 1.20.1, 1.21.1, 26.3 | Five genuine mouse-click persistence checks passed. |
| NeoForge built-in page, all three fields and Done | 1.21.1, 26.3 | Two persistence checks passed. |
| Fabric absence of interface | 1.16.5, 1.18.2, 1.21.1, 26.3 | Four checks passed; not counted as persistence clicks. |
| Old NeoForge native config without page | 1.20.4 / 20.4.251 | Native configuration/core passed, page explicitly unavailable. |
| Independent dedicated-server JVM | Forge 1.18.2, Forge 26.3, NeoForge 26.3 | Three post-startup cleanup/GC and graceful-stop checks passed. |

All 12 representative client core cases required actual fixture values, the loading overlay gone, real title readiness, completed cleanup, explicit GC, live JVM and owned teardown. The two Java 8 / 1.16.5 cases used G1 and do not certify the default Parallel collector. Other selected cases used the JVM default collector. These are not all-catalog passes for the changed native-configuration artifact.

The [native UI probe](../scripts/java/NativeConfigUiProbe.java) selects MemorySweep in the actual Mods list and dispatches vanilla `mouseClicked` to Config and Done, never invoking button `onPress` or the backend save API to bypass UI. Save checks require matching TOML, native snapshot and actual runtime fields. The [client runner](../scripts/run-runtime-matrix.mjs) and [server runner](../scripts/run-server-smoke.mjs) preserve immutable UUID attempts and candidate/probe hashes. Detailed historical raw logs, screenshots and runtime artifacts remain in the maintainer's local test-results archive and are not included in the source-only release commit.

## Diagnostics and limits

- Forge 26.3 originally crashed with `Can only blur once per frame`: its native Screen wrapper already paints the background. Extra background rendering is now skipped only when that wrapper exists; the original Config workflow passed after the fix, with legacy rendering still passing.
- One Forge 1.20.1 attempt saved the toggles but retained interval five instead of seventeen. Its cause remains **unconfirmed**. Three subsequent valid diagnostic replays and two debug-free replays saved all fields; another replay failed to initialize within its deadline. This is not a guarantee against intermittent input/storage issues.
- A NeoForge 1.21.1 startup attempt and a Forge 26.3 installer fetch failed before their isolated retries passed. They were not converted into passes or treated as Mod failures.
- The hotbar memory bar is absent from the universal artifact. Singleplayer/multiplayer gameplay, notifications and long-session memory improvements are not certified.
- The CI archive gate validates packaging, not Minecraft runtime behavior. Compare the actual CI artifact SHA-256 with the tested candidate above. Rebuilt artifacts are not automatically runtime-certified simply because version/metadata match.

## Reproduction

The universal module needs JDK 25.0.2 and Gradle 9.1.0 for the tested build and has an isolated entrypoint with no legacy Loom initialization:

```text
gradle --project-dir universal clean build -Pmod_version=3.0.0
javac --release 8 -d test-results/native-config-probe/classes scripts/java/RuntimeUiProbe.java scripts/java/NativeConfigUiProbe.java
jar cfm test-results/native-config-probe/probe.jar scripts/java/native-config-probe.mf -C test-results/native-config-probe/classes .
node scripts/run-runtime-matrix.mjs --versions=1.18.2,1.21.1,26.3 --ui --probe=test-results/native-config-probe/probe.jar --seconds=150 --force
```

The runtime runners require the local minecraft-mod-mcp launcher catalog/cache and appropriate Java installations; they are not silently run by the hosted build-only CI. Server tests require explicit Minecraft EULA acceptance, isolated loopback configuration and a candidate-specific SHA-256. See [CI and publishing](CI-PUBLISH.md) for upload labels and checks.
