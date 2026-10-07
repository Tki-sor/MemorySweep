# MemorySweep Universal

One Java 8 bytecode JAR with Forge, NeoForge and Fabric entrypoints. The universal distribution has no Fabric API, Architectury API, Cloth Config or Mod Menu dependency. Install only the universal JAR, not a legacy loader-specific file alongside it.

## Configuration

- Forge: native ConfigSpec and TOML; open **Mods -> MemorySweep -> Config**.
- NeoForge: native config spec and the loader's built-in generated page where available. Old 20.4/20.6 APIs lack that page and retain TOML only.
- Fabric: TOML only, no configuration interface or Options button.

Each JVM uses local `config/memorysweep.toml` with `memory_sweep`, `sweep_interval_seconds` and `silent`. Zero interval disables periodic cleanup. Dedicated servers use their own configuration and heap. Singleplayer client and integrated server share a JVM. See [native configuration and runtime limits](docs/NATIVE-CONFIG.md).

The universal HUD memory bar is not implemented. Gameplay, notifications and long-session memory benefits are not certified. Metadata acceptance does not guarantee every Minecraft/loader combination. The existing legacy 1.20.1 source and its dependencies are preserved separately and are not the universal distribution.

## Build

Use **JDK 25.0.2 and Gradle 9.1.0**:

```text
gradle --project-dir universal clean build -Pmod_version=3.0.0
```

The standalone module avoids configuring the legacy Loom projects and previously tracked build caches. The file is produced under `universal/build/libs/`. The root Gradle settings also register `universal`, but CI uses the isolated entrypoint.

## CI and publishing

The [CI workflow](.github/workflows/ci-build.yml) builds and validates one universal JAR, then can publish it to [CurseForge project 784959](https://www.curseforge.com/minecraft/mc-mods/memorysweep). PRs and ordinary pushes do not publish. An authorized release commit such as `update beta 3.0.0`, or an explicit manual publish trigger, enables the upload. The repository must contain the Actions secret `CURSEFORGE_TOKEN`.

The default branch is `1.20.1`; `main` and `master` are also allowed release branches. See [setup, triggers, checks and release labels](docs/CI-PUBLISH.md). A successful upload can still await CurseForge moderation.
