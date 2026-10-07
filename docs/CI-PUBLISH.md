# CI build and CurseForge publishing

The [GitHub Actions workflow](../.github/workflows/ci-build.yml) follows NekoJS-mult's release-commit convention and publishes **one universal JAR** to [MemorySweep / CurseForge project 784959](https://www.curseforge.com/minecraft/mc-mods/memorysweep). It does not create GitHub releases, upload source JARs or publish the legacy Fabric/Forge modules.

## Repository setup

1. Commit the workflow, [CI helper](../scripts/ci-release.py) and [release tags](../scripts/curseforge-release.json), along with all universal sources and the Gradle build files, to `Tki-sor/MemorySweep`.
2. In **Settings → Secrets and variables → Actions → New repository secret**, create `CURSEFORGE_TOKEN` using a CurseForge author API token belonging to an account authorized to upload files to project **784959**. Obtain it from the CurseForge author console's API-token section. Do not use a public API key, a Minecraft login credential or a token copied from another repository, and do not put the token in a file or chat.
3. Enable GitHub Actions. The workflow accepts publication only on this repository's `1.20.1`, `main` or `master`, never PRs, forks or arbitrary branches. Ordinary builds do not need a token.

The original local workspace has no `.git` directory. An isolated clone of the remote `1.20.1` branch is used to review and submit the explicitly authorized release changes without replacing unrelated files. The user has configured `CURSEFORGE_TOKEN`; only its presence was checked, never its value. The CI/upload status will be recorded after the remote run.

## Triggers

| Event | Build | CurseForge upload |
| --- | --- | --- |
| PR to 1.20.1/main/master | Development JAR | Never |
| Normal push to 1.20.1/main/master | Development JAR | Never |
| Push with final commit title `update alpha 3.0.1` | Version 3.0.1 | Alpha |
| Push with final commit title `update beta 3.0.1` | Version 3.0.1 | Beta |
| Push with final commit title `update 3.0.1` | Version 3.0.1 | Release |
| Actions → MemorySweep CI Build → Run workflow, publish unchecked | Development JAR, or supplied version | Never |
| Run workflow on 1.20.1/main/master, publish checked | Supplied version, or `mod_version` if empty | Chosen alpha/beta/release channel; beta by default |

Use **beta** for the current candidate: the universal HUD and full gameplay certification are not complete. The release title must match the entire first line; `update beta` or a general sentence containing `update` does not publish. For multiple-commit pushes only the checked-out final commit title is used. If squash merging, use the final squash commit title. Put prerelease suffixes explicitly in the version when desired: `update beta 3.0.1-beta.1`.

The release version is passed as `-Pmod_version=...` to Gradle and validated in all three loader descriptors plus the manifest; it is not only a renamed file or CurseForge display title. Non-release builds use `<mod_version>-dev.<commit SHA>` unless a manual version was supplied.

## Artifacts and checks

CI fixes **Java 21 + Gradle 9.1.0** through the Gradle setup action rather than the existing 8.12.1 wrapper. The latter hit a Java 25 dependency-bytecode error in this workspace. The workflow still validates the wrapper JAR, but its build command uses the pinned Gradle executable.

It runs `gradle --project-dir universal clean build`, not the root multi-loader `build`. The standalone module reads its version/group/archive defaults from the parent properties file and does not configure the legacy Minecraft/Loom modules or their tracked caches. The archive gate checks:

- All three loader descriptors and actual entrypoint classes, expanded versions and native configuration helpers.
- Java 8 class files, Mixin manifest registration, both translations and no Options-button Mixin.
- No packaged compile-only loader stubs or added Fabric gameplay dependencies.
- Exactly one release JAR, SHA-256 and matching publication metadata after downloading it in the publishing job.

The downloadable `MemorySweep-Universal-<version>` artifact includes the JAR, `SHA256SUMS`, `release.json` and generated release notes. The publishing job downloads that same JAR rather than rebuilding. Its API token is passed only to the token guard, read-only platform-tag preflight and upload action, not the build/PR jobs. All jobs have read-only GitHub contents permission. Publication is serialized and not cancelled mid-upload. The upload action performs one attempt; do not blindly rerun a failed publishing job after an ambiguous response—first inspect CurseForge for a file that may already have been received.

These are build/package checks, **not Minecraft runtime tests**. A changed source or version-stamped archive has a different SHA-256 and is not automatically covered by the historical runtime matrices. See [native configuration tests and limitations](NATIVE-CONFIG.md).

## CurseForge tags

The checked-in [release configuration](../scripts/curseforge-release.json) uses Forge, NeoForge and Fabric, with exact Minecraft tags `1.16.5`, `1.18.2`, `1.20.1`, `1.20.4`, `1.21.1`, `26.3`. It deliberately does not claim the entire `1.16–26.3` range or `1.7.10` compatibility.

CurseForge stores file-level sets of loaders and game versions, not a loader-by-version test matrix. The tags therefore cannot assert that every tagged pair was tested; NeoForge is not available for the oldest versions. Generated public release notes explicitly retain this qualification, old NeoForge's missing UI, Java 8 G1-conditioned evidence, the missing universal HUD and the observed unconfirmed Forge 1.20.1 interval-save issue. Expand tags only after new-artifact tests. A [read-only preflight](../scripts/curseforge-preflight.py) authenticates to the official CurseForge catalog and requires all six exact Minecraft labels and all three loader labels before invoking the upload action. This gate is necessary because the uploader can otherwise filter unknown labels silently. It records only public tag IDs, never the token. Missing tags fail before any upload rather than attaching a different version.

Successful workflow upload does not mean CurseForge has approved or exposed the file. The last step records the returned CurseForge file ID and URL; review the author's project file/status page afterward.

## Local verification result

- Java 21 + Gradle 9.1.0 built `3.0.0-ci.abcdef123456` successfully; all three expanded descriptor versions and the manifest matched. The isolated `--project-dir universal` build also produced `3.0.0-standalonecheck` successfully, with all 15 class files and non-version resources byte-identical to the runtime-tested candidate.
- Final `prepare` and `verify` passed with exactly one JAR and matching SHA-256. The previously runtime-tested `3.0.0` JAR retained its original `0fe70e00...` fingerprint.
- `actionlint` 1.7.7 passed workflow syntax and expression checks; its official archive SHA-256 was checked before execution. Optional ShellCheck and Pyflakes integrations were disabled because those tools were not installed.
- 32 release-trigger, branch, unsafe-version, invalid-JAR and checksum cases passed, plus four simulated CurseForge catalog/tag-map cases. The metadata command's GitHub output integration also passed with a mocked Git process, explicitly because this workspace has no Git history.
- The initial local validation did not run GitHub-hosted CI, a cold Linux build, authenticated CurseForge upload or CurseForge approval. The secret has since been configured; remote release results are tracked separately.

## Local dry run

With Python 3.11+, Java 21 and Gradle 9.1.0:

```text
gradle --project-dir universal build --no-daemon -Pmod_version=3.0.1-beta.1
python scripts/ci-release.py prepare --version 3.0.1-beta.1 --directory artifacts
python scripts/ci-release.py verify --version 3.0.1-beta.1 --directory artifacts --sha256 <SHA-256 from prepare>
```

Use an empty artifact directory for each prepare run; the helper refuses stale files. No helper subcommand sends an API request or reads `CURSEFORGE_TOKEN`. The GitHub upload is implemented through [mc-publish v3.3](https://github.com/Kir-Antipov/mc-publish/tree/v3.3), as in the reference project.
