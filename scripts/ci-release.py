import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tomllib
import zipfile


ROOT = Path(__file__).resolve().parent.parent
VERSION_PATTERN = r"[0-9][0-9A-Za-z._+-]*"
TRUSTED_REPOSITORY = "Tki-sor/MemorySweep"
RELEASE_BRANCHES = {"refs/heads/1.20.1", "refs/heads/main", "refs/heads/master"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_version(version):
    require(re.fullmatch(VERSION_PATTERN, version), "Invalid mod version")
    return version


def release_config():
    config = json.loads((ROOT / "scripts/curseforge-release.json").read_text(encoding="utf-8"))
    require(config["project_id"] == "784959", "Unexpected CurseForge project ID")
    require(set(config["loaders"]) == {"forge", "neoforge", "fabric"}, "Expected all three loaders")
    require(len(config["loaders"]) == 3, "Duplicate loader tags")
    versions = config["game_versions"]
    require(isinstance(versions, list) and versions, "Minecraft version tags must not be empty")
    require(len(versions) == len(set(versions)), "Duplicate Minecraft version tags")
    require(all(isinstance(version, str) and re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,2}", version)
                for version in versions), "Use exact Minecraft versions, not compatibility ranges")
    return config


def git_output(*arguments):
    return subprocess.check_output(["git", *arguments], cwd=ROOT, text=True, encoding="utf-8").strip()


def resolve_metadata(event, ref, repository, title, manual_publish, manual_version, channel, base_version, short_sha):
    validate_version(base_version)
    require(re.fullmatch(r"[0-9a-f]{7,40}", short_sha), "Invalid build commit SHA")
    publish = False
    version = f"{base_version}-dev.{short_sha}"
    release_channel = "beta"
    trusted = repository.lower() == TRUSTED_REPOSITORY.lower() and ref in RELEASE_BRANCHES
    if event == "push":
        match = re.fullmatch(rf"update\s+(?:(alpha|beta)\s+)?({VERSION_PATTERN})", title.strip(), re.IGNORECASE)
        if match:
            release_channel = (match.group(1) or "release").lower()
            version = validate_version(match.group(2))
            publish = trusted
    elif event == "workflow_dispatch":
        require(channel in {"alpha", "beta", "release"}, "Invalid manual release channel")
        release_channel = channel
        if manual_publish:
            require(trusted, "Publish only from Tki-sor/MemorySweep 1.20.1, main or master")
            version = validate_version(manual_version.strip() or base_version)
            publish = True
        elif manual_version.strip():
            version = validate_version(manual_version.strip())
    return {"publish": str(publish).lower(), "version": version, "channel": release_channel}


def emit_outputs(values):
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as output:
            for name, value in values.items():
                require("\n" not in str(value) and "\r" not in str(value), "Unsafe workflow output")
                output.write(f"{name}={value}\n")
    print(json.dumps(values, indent=2))


def metadata():
    properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    match = re.search(r"^mod_version\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
    require(match, "Missing mod_version in gradle.properties")
    values = resolve_metadata(
        os.environ.get("CI_EVENT", ""), os.environ.get("CI_REF", ""),
        os.environ.get("CI_REPOSITORY", ""), git_output("log", "-1", "--format=%s"),
        os.environ.get("CI_PUBLISH", "false").lower() == "true",
        os.environ.get("CI_VERSION", ""), os.environ.get("CI_CHANNEL", "beta"),
        match.group(1), git_output("rev-parse", "--short=12", "HEAD"),
    )
    config = release_config()
    values.update(loaders=";".join(config["loaders"]), game_versions=";".join(config["game_versions"]))
    emit_outputs(values)


def validate_jar(jar_path, version):
    validate_version(version)
    with zipfile.ZipFile(jar_path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate JAR entries")
        required = {
            "fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml",
            "META-INF/MANIFEST.MF", "memorysweep-universal.mixins.json", "pack.mcmeta",
            "assets/memorysweep/lang/en_us.json", "assets/memorysweep/lang/zh_cn.json",
            "com/tkisor/memorysweep/universal/NativeConfiguration.class",
            "com/tkisor/memorysweep/universal/NativeForgeScreen.class",
            "com/tkisor/memorysweep/universal/fabric/UniversalFabricEntrypoint.class",
            "com/tkisor/memorysweep/universal/forge/UniversalForgeEntrypoint.class",
            "com/tkisor/memorysweep/universal/neoforge/UniversalNeoForgeEntrypoint.class",
        }
        require(required.issubset(names), f"Missing required JAR entries: {sorted(required - set(names))}")
        forbidden = {
            "net/fabricmc/api/ModInitializer.class", "net/minecraftforge/fml/common/Mod.class",
            "net/neoforged/fml/common/Mod.class",
            "com/tkisor/memorysweep/universal/mixin/UniversalOptionsMixin.class",
        }
        require(not forbidden.intersection(names), "Compile stubs or the obsolete Options Mixin are packaged")
        classes = [name for name in names if name.endswith(".class")]
        for name in classes:
            data = archive.read(name)
            require(data[:4] == b"\xca\xfe\xba\xbe" and struct.unpack(">H", data[6:8])[0] == 52,
                    f"Class is not Java 8 bytecode: {name}")
        fabric = json.loads(archive.read("fabric.mod.json"))
        require(fabric["id"] == "memorysweep" and fabric["version"] == version, "Fabric metadata version mismatch")
        require(set(fabric.get("depends", {})) <= {"fabricloader", "minecraft", "java"}, "Unexpected gameplay dependency")
        for descriptor in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
            mods = tomllib.loads(archive.read(descriptor).decode("utf-8"))["mods"]
            require(len(mods) == 1 and mods[0]["modId"] == "memorysweep" and mods[0]["version"] == version,
                    f"Version mismatch in {descriptor}")
        mixins = json.loads(archive.read("memorysweep-universal.mixins.json"))
        require(mixins["client"] == ["UniversalClientMixin"] and mixins["mixins"] == ["UniversalServerMixin"],
                "Unexpected Mixin registrations")
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n ", "")
        require(f"Implementation-Version: {version}" in manifest, "JAR manifest version mismatch")
        require("MixinConfigs: memorysweep-universal.mixins.json" in manifest, "Missing Mixin manifest registration")
    return {"sha256": hashlib.sha256(jar_path.read_bytes()).hexdigest(), "class_count": len(classes)}


def changelog(version):
    config = release_config()
    source_ref = git_output("rev-parse", "HEAD") if (ROOT / ".git").exists() else "1.20.1"
    notes = [
        f"# MemorySweep {version} (Universal)", "",
        "One JAR for Forge, NeoForge and Fabric. No external gameplay Mod is required.", "",
        "## Configuration", "",
        "- Forge: native ConfigSpec/TOML and Mods -> MemorySweep -> Config.",
        "- NeoForge: native configuration and the loader's built-in page where available.",
        "- Fabric: TOML only; no configuration interface or Options button.", "",
        "## Compatibility and limits", "",
        f"Selected Minecraft tags: {', '.join(config['game_versions'])}.",
        "Tags are representative tested Minecraft versions, not every Minecraft/loader pairing. "
        "NeoForge does not exist for the oldest tagged versions. Old NeoForge 20.4/20.6 lacks the built-in page.",
        "Java 8 / Minecraft 1.16.5 core evidence used G1 rather than the default Parallel collector.",
        "CI validates the build and archive, not gameplay. Changed CI JARs are not automatically runtime-certified.",
        "The hotbar memory bar is not yet included in the universal artifact. Singleplayer/multiplayer gameplay, "
        "notifications and long-session memory benefits are not certified.",
        "An intermittent Forge 1.20.1 interval-save failure was observed; later replays passed but its cause remains unconfirmed.",
        f"Runtime evidence and limitations: https://github.com/Tki-sor/MemorySweep/blob/{source_ref}/docs/NATIVE-CONFIG.md", "",
    ]
    if (ROOT / ".git").exists():
        notes.extend(["## Build source", "", f"Commit: `{git_output('rev-parse', 'HEAD')}`", "",
                      git_output("log", "-1", "--format=%s"), ""])
    return "\n".join(notes)


def prepare(version, directory):
    validate_version(version)
    jar_name = f"memorysweep-universal-{version}.jar"
    source = ROOT / "universal/build/libs" / jar_name
    require(source.is_file(), f"Expected universal JAR was not built: {source}")
    evidence = validate_jar(source, version)
    directory.mkdir(parents=True, exist_ok=True)
    require(not any(directory.iterdir()), "Artifact directory must be empty; refuse stale build files")
    shutil.copyfile(source, directory / jar_name)
    manifest = {"jar_name": jar_name, "version": version, **release_config(), **evidence}
    (directory / "release.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (directory / "SHA256SUMS").write_text(f"{evidence['sha256']}  {jar_name}\n", encoding="utf-8")
    (directory / "CHANGELOG.md").write_text(changelog(version), encoding="utf-8")
    emit_outputs({"jar_name": jar_name, "sha256": evidence["sha256"]})
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as output:
            output.write(f"## Universal build\n\n`{jar_name}`\n\nSHA-256: `{evidence['sha256']}`\n\n"
                         "Archive checks passed. This is not a Minecraft runtime test.\n")


def verify(version, directory, expected_sha256):
    validate_version(version)
    require(re.fullmatch(r"[0-9a-f]{64}", expected_sha256), "Require the build job's SHA-256 before publishing")
    manifest = json.loads((directory / "release.json").read_text(encoding="utf-8"))
    jar_name = f"memorysweep-universal-{version}.jar"
    require(manifest["version"] == version and manifest["jar_name"] == jar_name, "Downloaded artifact version mismatch")
    require(sorted(path.name for path in directory.glob("*.jar")) == [jar_name], "Expected exactly one upload JAR")
    require({key: manifest[key] for key in release_config()} == release_config(), "Release tags differ from the build")
    evidence = validate_jar(directory / jar_name, version)
    require(evidence["sha256"] == manifest["sha256"] == expected_sha256, "Downloaded artifact SHA-256 mismatch")
    require((directory / "SHA256SUMS").read_text(encoding="utf-8") == f"{expected_sha256}  {jar_name}\n",
            "Checksum sidecar mismatch")
    require((directory / "CHANGELOG.md").is_file(), "Missing release notes")
    print(f"Verified exactly one universal upload JAR: {jar_name} ({expected_sha256})")


def main():
    parser = argparse.ArgumentParser(description="Prepare and verify MemorySweep's single CurseForge artifact")
    parser.add_argument("command", choices=["metadata", "prepare", "verify"])
    parser.add_argument("--version")
    parser.add_argument("--directory", type=Path, default=ROOT / "artifacts")
    parser.add_argument("--sha256", default="")
    arguments = parser.parse_args()
    if arguments.command == "metadata":
        metadata()
    else:
        require(arguments.version, "--version is required")
        if arguments.command == "prepare":
            prepare(arguments.version, arguments.directory)
        else:
            verify(arguments.version, arguments.directory, arguments.sha256)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as exception:
        print(f"CI release validation failed: {exception}", file=sys.stderr)
        sys.exit(1)
