import argparse
import json
import os
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener


ROOT = Path(__file__).resolve().parent.parent
API = "https://minecraft.curseforge.com/api"
OFFICIAL_HOSTS = {"minecraft.curseforge.com", "www.curseforge.com", "authors.curseforge.com"}


class OfficialRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        target = urlparse(new_url)
        if target.scheme != "https" or target.hostname not in OFFICIAL_HOSTS:
            raise ValueError("Refused a non-CurseForge authentication redirect")
        return super().redirect_request(request, response, code, message, headers, new_url)


def normalize_loader(name):
    return "".join(character for character in name.lower() if character.isalnum())


def resolve_tags(versions, types, config):
    groups = {item["id"]: item["slug"] for item in types}
    minecraft = {item["name"]: item["id"] for item in versions
                 if groups.get(item["gameVersionTypeID"], "").startswith("minecraft")}
    loaders = {normalize_loader(item["name"]): item["id"] for item in versions
               if groups.get(item["gameVersionTypeID"], "").startswith("modloader")}
    missing_versions = [name for name in config["game_versions"] if name not in minecraft]
    missing_loaders = [name for name in config["loaders"] if normalize_loader(name) not in loaders]
    if missing_versions or missing_loaders:
        print(json.dumps({"missing_game_versions": missing_versions, "missing_loaders": missing_loaders}), file=sys.stderr)
        raise ValueError(f"CurseForge does not recognize the requested tags: Minecraft={missing_versions}, loaders={missing_loaders}")
    return {
        "project_id": config["project_id"],
        "game_versions": {name: minecraft[name] for name in config["game_versions"]},
        "loaders": {name: loaders[normalize_loader(name)] for name in config["loaders"]},
    }


def main():
    parser = argparse.ArgumentParser(description="Check exact CurseForge upload tags without uploading or displaying credentials")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/curseforge-preflight.json")
    arguments = parser.parse_args()
    token = os.environ.get("CF_TOKEN", "")
    if not token:
        raise ValueError("CURSEFORGE_TOKEN is not configured")
    config = json.loads((ROOT / "scripts/curseforge-release.json").read_text(encoding="utf-8"))
    if config["project_id"] != "784959":
        raise ValueError("Unexpected CurseForge project ID")
    opener = build_opener(OfficialRedirects())
    headers = {"X-Api-Token": token, "Accept": "application/json", "User-Agent": "MemorySweep-CI/3.0.0"}
    catalogs = []
    for resource in ("versions", "version-types"):
        request = Request(f"{API}/game/{resource}?cache=true", headers=headers)
        with opener.open(request, timeout=30) as response:
            catalogs.append(json.load(response))
    evidence = resolve_tags(catalogs[0], catalogs[1], config)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, indent=2))
    print("CurseForge tag preflight passed; no upload request was sent")


if __name__ == "__main__":
    try:
        main()
    except HTTPError as exception:
        print(f"CurseForge preflight failed with HTTP {exception.code}; no upload was attempted", file=sys.stderr)
        sys.exit(1)
    except URLError:
        print("CurseForge preflight network request failed; no upload was attempted", file=sys.stderr)
        sys.exit(1)
    except (ValueError, KeyError, OSError):
        print("CurseForge preflight failed; check the configured tags and author upload-token permissions", file=sys.stderr)
        sys.exit(1)
