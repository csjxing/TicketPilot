#!/usr/bin/env python3
"""Prepare and optionally publish a same-signature TicketPilot GitHub preview release.

Only release assets go to GitHub Releases. No keys, device state, logs, or screenshots.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

REPO = "csjxing/TicketPilot"
PACKAGE = "cn.local.tickethelper"
DEFAULT_VERSION = "0.2.4-preview"
DEFAULT_CODE = 20
MAX_APK = 200 * 1024 * 1024
MAX_SOURCE = 200 * 1024 * 1024
MANIFEST_NAME = "cloud-release.json"
BUNDLE_NAME = "release-bundle.json"
JOURNAL_NAME = ".publish-state.json"
HEX64 = re.compile(r"[a-f0-9]{64}\Z")
TAG = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}\Z")


class ReleaseError(Exception):
    """A safe operator message; never wrap raw subprocess/HTTP output."""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def atomic_json(path: Path, value: dict) -> None:
    """Persist intention before the network mutation. Fail closed on disk errors."""
    encoded = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    temporary = None
    try:
        descriptor, name = tempfile.mkstemp(prefix=".release-state-", dir=path.parent)
        temporary = Path(name)
        with os.fdopen(descriptor, "wb") as output:
            output.write(encoded)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def command(arguments: list[str]) -> str:
    try:
        result = subprocess.run(arguments, capture_output=True, timeout=90, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ReleaseError("Required local tool could not run or timed out.") from None
    if result.returncode:
        raise ReleaseError("Local APK/signature verification failed; no output was published.")
    try:
        return result.stdout.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        raise ReleaseError("Local tool returned an invalid response.") from None


def inspect_apk(path: Path, aapt: str, apksigner: str, run=command) -> dict:
    badging = run([aapt, "dump", "badging", str(path)])
    first = next((line for line in badging.splitlines() if line.startswith("package: ")), "")
    package = re.search(r"\bname='([^']+)'", first)
    code = re.search(r"\bversionCode='([0-9]+)'", first)
    version = re.search(r"\bversionName='([^']+)'", first)
    if not package or not code or not version or package.group(1) != PACKAGE:
        raise ReleaseError("APK package or version metadata is invalid.")
    certificates = run([apksigner, "verify", "--print-certs", str(path)])
    signers = re.findall(r"^Signer #([0-9]+) certificate SHA-256 digest: ([0-9a-fA-F]{64})$", certificates, re.MULTILINE)
    if len(signers) != 1 or signers[0][0] != "1":
        raise ReleaseError("Exactly one verified APK signer is required.")
    return {"packageName": PACKAGE, "versionCode": int(code.group(1)), "versionName": version.group(1),
            "certificateSha256": signers[0][1].lower()}


def validate_source(path: Path) -> None:
    top_files = {"README.md", "VALIDATION.md", "AGENTS.md", "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat", "app/build.gradle"}
    denied_suffix = {".jks", ".keystore", ".key", ".pem", ".p12", ".pfx", ".apk", ".aab", ".hprof", ".log", ".pyc"}
    denied_parts = {".runtime", "evidence", "exports", "no_backup", "shared_prefs", "local.properties", ".git", ".env", "__pycache__"}
    try:
        with zipfile.ZipFile(path) as source:
            entries = source.infolist()
            if len(entries) > 10000 or sum(entry.file_size for entry in entries) > MAX_SOURCE:
                raise ReleaseError("Source archive exceeds release limits.")
            seen = set()
            for entry in entries:
                name = entry.filename
                parts = PurePosixPath(name).parts
                if (not name or name.startswith("/") or "\\" in name or ".." in parts or name in seen
                        or entry.flag_bits & 1 or stat.S_ISLNK(entry.external_attr >> 16)):
                    raise ReleaseError("Source archive contains an unsafe entry.")
                seen.add(name)
                if any(part in denied_parts or part.startswith(".env.") for part in parts) or Path(name).suffix.lower() in denied_suffix:
                    raise ReleaseError("Source archive includes private or generated files.")
                if entry.is_dir():
                    continue
                permitted = name in top_files or name.startswith(("handoff/", "gradle/wrapper/", "app/src/"))
                if not permitted or (name.endswith(".jar") and name != "gradle/wrapper/gradle-wrapper.jar"):
                    raise ReleaseError("Source archive contains files outside the source allowlist.")
            if "AGENTS.md" not in seen or "app/build.gradle" not in seen:
                raise ReleaseError("Source archive is missing the handoff entry points.")
            if source.testzip() is not None:
                raise ReleaseError("Source archive failed integrity checks.")
    except (OSError, zipfile.BadZipFile, RuntimeError):
        raise ReleaseError("Cannot read a valid source archive.") from None


def prepare(args: argparse.Namespace, run=command) -> dict:
    tag = args.tag or "v" + args.version
    if not TAG.fullmatch(tag) or ".." in tag or not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._-]{0,79}", args.version):
        raise ReleaseError("Release tag or version name is invalid.")
    if not 0 < args.code <= 2147483647:
        raise ReleaseError("Version code is invalid.")
    commit = args.source_commit or run(["git", "rev-parse", "HEAD"]).strip()
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ReleaseError("A full 40-character source commit is required.")
    if run(["git", "rev-parse", "HEAD"]).strip() != commit or run(["git", "status", "--porcelain"]).strip():
        raise ReleaseError("Commit the exact source and public documentation before preparing a release.")
    if (not args.apk.is_file() or not 0 < args.apk.stat().st_size <= MAX_APK
            or not args.source_zip.is_file() or not 0 < args.source_zip.stat().st_size <= MAX_SOURCE):
        raise ReleaseError("APK/source archive is missing or exceeds the allowed size.")
    notes = args.notes.read_text(encoding="utf-8") if args.notes else "TicketPilot preview update. See PROJECT_STATUS.md for verified scope and remaining work."
    if len(notes) > 6000 or any(ord(char) < 32 and char not in "\n\t" for char in notes):
        raise ReleaseError("Public release notes are invalid or too long.")
    output = args.output or Path("exports") / ("release-" + tag)
    try:
        output.mkdir(parents=True, exist_ok=False)
    except FileExistsError:
        raise ReleaseError("Output directory already exists. It will not be replaced.") from None
    apk_name = "ticketpilot-" + args.version + ".apk"
    source_name = "ticketpilot-" + args.version + "-source.zip"
    apk = output / apk_name
    source = output / source_name
    shutil.copyfile(args.apk, apk)
    shutil.copyfile(args.source_zip, source)
    validate_source(source)
    identity = inspect_apk(apk, args.aapt, args.apksigner, run)
    if identity["versionCode"] != args.code or identity["versionName"] != args.version:
        raise ReleaseError("APK version does not match the requested release.")
    manifest = {"schema": 1, "versionCode": args.code, "versionName": args.version,
                "sourceCommit": commit, "notes": notes,
                "apk": {"url": f"https://github.com/{REPO}/releases/download/{tag}/{apk_name}",
                        "size": apk.stat().st_size, "sha256": sha256(apk), "certificateSha256": identity["certificateSha256"]}}
    atomic_json(output / MANIFEST_NAME, manifest)
    assets = []
    for path, content_type in [(apk, "application/vnd.android.package-archive"),
                               (source, "application/zip"), (output / MANIFEST_NAME, "application/json")]:
        assets.append({"name": path.name, "size": path.stat().st_size, "sha256": sha256(path), "contentType": content_type})
    bundle = {"schema": 1, "repository": REPO, "tag": tag, "sourceCommit": commit,
              "versionCode": args.code, "versionName": args.version,
              "preview": not args.stable, "assets": assets, "notes": notes}
    atomic_json(output / BUNDLE_NAME, bundle)
    (output / "release-notes.md").write_text(notes + "\n\nSource commit: `" + commit
            + "`\n\n<!-- ticketpilot-release:" + sha256(output / MANIFEST_NAME) + " -->\n", encoding="utf-8")
    return {"prepared": True, "directory": str(output), "tag": tag, "versionCode": args.code,
            "manifestSha256": sha256(output / MANIFEST_NAME), "published": False}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GitHub:
    """No automatic redirect can carry Authorization to an asset CDN."""
    def __init__(self, token: str):
        if not token or len(token) > 4096 or any(ord(char) <= 32 or ord(char) >= 127 for char in token):
            raise ReleaseError("Set GH_TOKEN or GITHUB_TOKEN securely in the environment.")
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect)

    def _open(self, method, url, data=None, headers=None, allowed=()):
        parsed = urllib.parse.urlparse(url)
        trusted = parsed.scheme == "https" and parsed.hostname in {"api.github.com", "uploads.github.com"}
        if parsed.username or parsed.password or parsed.fragment or parsed.port not in (None, 443) or not trusted:
            raise ReleaseError("Refused an unexpected GitHub API address.")
        request_headers = {"Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
                           "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "TicketPilot-release/1"}
        request_headers.update(headers or {})
        request = urllib.request.Request(url, data=data, headers=request_headers, method=method)
        try:
            return self.opener.open(request, timeout=60)
        except urllib.error.HTTPError as error:
            if error.code in allowed:
                return error
            raise ReleaseError(f"GitHub returned HTTP {error.code}; inspect repository/token permissions and release state.") from None
        except (OSError, ValueError):
            raise ReleaseError("GitHub connection failed; mutation outcome may be unknown. Reconcile before retrying.") from None

    def json(self, method: str, path: str, value=None, allow_missing=False):
        data = None if value is None else json.dumps(value).encode("utf-8")
        headers = {} if data is None else {"Content-Type": "application/json", "Content-Length": str(len(data))}
        with self._open(method, "https://api.github.com" + path, data, headers, (404,) if allow_missing else ()) as response:
            if response.code == 404:
                return None
            raw = response.read(2 * 1024 * 1024 + 1)
            if len(raw) > 2 * 1024 * 1024:
                raise ReleaseError("GitHub returned an oversized response.")
            try:
                return json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                raise ReleaseError("GitHub returned an invalid response.") from None

    def upload(self, release_id: int, path: Path, content_type: str):
        url = f"https://uploads.github.com/repos/{REPO}/releases/{release_id}/assets?name=" + urllib.parse.quote(path.name, safe="")
        with path.open("rb") as source:
            headers = {"Content-Type": content_type, "Content-Length": str(path.stat().st_size)}
            with self._open("POST", url, source, headers) as response:
                raw = response.read(2 * 1024 * 1024 + 1)
                if len(raw) > 2 * 1024 * 1024:
                    raise ReleaseError("GitHub returned an oversized upload receipt.")
                try:
                    return json.loads(raw)
                except (ValueError, UnicodeDecodeError):
                    raise ReleaseError("GitHub upload receipt was invalid; reconcile the release.") from None

    def asset_hash(self, asset_id: int, expected_size: int) -> str:
        url = f"https://api.github.com/repos/{REPO}/releases/assets/{asset_id}"
        response = self._open("GET", url, headers={"Accept": "application/octet-stream"}, allowed=(301, 302, 303, 307, 308))
        for redirect in range(5):
            if response.code not in (301, 302, 303, 307, 308):
                break
            location = response.headers.get("Location", "")
            response.close()
            parsed = urllib.parse.urlparse(location)
            if (redirect == 4 or parsed.scheme != "https" or parsed.hostname not in {"release-assets.githubusercontent.com", "objects.githubusercontent.com"}
                    or parsed.username or parsed.password or parsed.fragment or parsed.port not in (None, 443)):
                raise ReleaseError("Refused an unexpected asset download redirect.")
            request = urllib.request.Request(location, headers={"User-Agent": "TicketPilot-release/1", "Accept-Encoding": "identity"})
            try:
                response = self.opener.open(request, timeout=60)
            except urllib.error.HTTPError as error:
                if error.code not in (301, 302, 303, 307, 308):
                    raise ReleaseError("Cannot verify uploaded release bytes.") from None
                response = error
            except OSError:
                raise ReleaseError("Uploaded asset verification failed; reconcile before retrying.") from None
        digest = hashlib.sha256()
        count = 0
        with response:
            if response.code != 200:
                raise ReleaseError("Uploaded asset could not be verified.")
            for block in iter(lambda: response.read(1024 * 1024), b""):
                count += len(block)
                if count > expected_size or count > MAX_APK:
                    raise ReleaseError("Uploaded asset size does not match the prepared release.")
                digest.update(block)
        if count != expected_size:
            raise ReleaseError("Uploaded asset was incomplete.")
        return digest.hexdigest()


def load_bundle(directory: Path) -> dict:
    try:
        bundle = json.loads((directory / BUNDLE_NAME).read_text(encoding="utf-8"))
        if (type(bundle["schema"]) is not int or bundle["schema"] != 1 or bundle["repository"] != REPO or not TAG.fullmatch(bundle["tag"])
                or ".." in bundle["tag"] or not re.fullmatch(r"[a-f0-9]{40}", bundle["sourceCommit"]) or len(bundle["assets"]) != 3
                or type(bundle["preview"]) is not bool or type(bundle["versionCode"]) is not int
                or not 0 < bundle["versionCode"] <= 2147483647 or not isinstance(bundle["versionName"], str)
                or not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._-]{0,79}", bundle["versionName"])
                or not isinstance(bundle["notes"], str) or len(bundle["notes"]) > 6000):
            raise ReleaseError("Release bundle identity is invalid.")
        names = set()
        for asset in bundle["assets"]:
            name = asset["name"]
            if (not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,119}", name) or ".." in name or name in names
                    or not HEX64.fullmatch(asset["sha256"]) or type(asset["size"]) is not int or not 0 < asset["size"] <= MAX_APK
                    or asset["contentType"] not in {"application/vnd.android.package-archive", "application/zip", "application/json"}):
                raise ReleaseError("Release asset metadata is invalid.")
            names.add(name)
            path = directory / name
            if not path.is_file() or path.stat().st_size != asset["size"] or sha256(path) != asset["sha256"]:
                raise ReleaseError("Prepared release files changed; refuse publication.")
        if MANIFEST_NAME not in names or sum(name.endswith(".apk") for name in names) != 1 or sum(name.endswith("-source.zip") for name in names) != 1:
            raise ReleaseError("Release bundle asset set is invalid.")
        manifest = json.loads((directory / MANIFEST_NAME).read_text(encoding="utf-8"))
        apk = next(asset for asset in bundle["assets"] if asset["name"].endswith(".apk"))
        if (type(manifest["schema"]) is not int or manifest["schema"] != 1 or type(manifest["versionCode"]) is not int
                or type(manifest["apk"]["size"]) is not int or manifest["versionCode"] != bundle["versionCode"] or manifest["versionName"] != bundle["versionName"]
                or manifest["sourceCommit"] != bundle["sourceCommit"] or manifest["apk"]["size"] != apk["size"] or manifest["apk"]["sha256"] != apk["sha256"]
                or not HEX64.fullmatch(manifest["apk"]["certificateSha256"])
                or manifest["apk"]["url"] != f"https://github.com/{REPO}/releases/download/{bundle['tag']}/{apk['name']}"):
            raise ReleaseError("Release manifest does not match the prepared files.")
        validate_source(directory / next(name for name in names if name.endswith("-source.zip")))
        bundle["manifestSha256"] = sha256(directory / MANIFEST_NAME)
        return bundle
    except (OSError, ValueError, KeyError, TypeError, StopIteration):
        raise ReleaseError("Cannot read a complete, verified release bundle.") from None


def _id(value) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise ReleaseError("GitHub returned an invalid identifier.")
    return value


def publish(directory: Path, github, make_public=False, reconcile_only=False) -> dict:
    bundle = load_bundle(directory)
    marker = "<!-- ticketpilot-release:" + bundle["manifestSha256"] + " -->"
    journal_path = directory / JOURNAL_NAME
    state = {"schema": 1, "repository": REPO, "tag": bundle["tag"], "manifestSha256": bundle["manifestSha256"], "intent": None}
    if journal_path.exists():
        try:
            saved = json.loads(journal_path.read_text(encoding="utf-8"))
            if any(saved.get(key) != state[key] for key in ("schema", "repository", "tag", "manifestSha256")):
                raise ReleaseError("Publication journal belongs to another release.")
            intent = saved.get("intent")
            if intent is not None and (not isinstance(intent, dict) or intent.get("operation") not in {"create", "upload", "publish"}):
                raise ReleaseError("Publication journal intention is invalid.")
            state = saved
        except (OSError, ValueError):
            raise ReleaseError("Publication journal cannot be read safely.") from None
    path = f"/repos/{REPO}/releases"
    release = github.json("GET", path + "/tags/" + urllib.parse.quote(bundle["tag"], safe=""), allow_missing=True)
    pending = state.get("intent")
    if release is None:
        if pending:
            raise ReleaseError("Previous release mutation has no confirmed outcome. No duplicate request was sent; inspect GitHub and reconcile later.")
        if reconcile_only:
            return {"found": False, "tag": bundle["tag"], "published": False}
        body = bundle["notes"] + "\n\nSource commit: `" + bundle["sourceCommit"] + "`\n\n" + marker
        state["intent"] = {"operation": "create"}
        atomic_json(journal_path, state)
        release = github.json("POST", path, {"tag_name": bundle["tag"], "target_commitish": bundle["sourceCommit"],
                              "name": "TicketPilot " + bundle["versionName"], "body": body,
                              "draft": True, "prerelease": bundle["preview"]})
        pending = state["intent"]
    if (not isinstance(release, dict) or release.get("tag_name") != bundle["tag"]
            or not isinstance(release.get("body"), str) or marker not in release["body"]
            or type(release.get("prerelease")) is not bool or release["prerelease"] != bundle["preview"]
            or not isinstance(release.get("draft"), bool)):
        raise ReleaseError("Existing release identity differs. No assets were changed or replaced.")
    release_id = _id(release.get("id"))
    if pending and pending.get("operation") == "create":
        state["intent"] = None
        state["releaseId"] = release_id
        atomic_json(journal_path, state)
        pending = None
    remote_assets = github.json("GET", path + f"/{release_id}/assets?per_page=100")
    if not isinstance(remote_assets, list) or len(remote_assets) >= 100:
        raise ReleaseError("Release asset list is incomplete or invalid.")
    by_name = {}
    for asset in remote_assets:
        if not isinstance(asset, dict) or not isinstance(asset.get("name"), str) or asset["name"] in by_name:
            raise ReleaseError("Release contains ambiguous asset names.")
        by_name[asset["name"]] = asset
    verified = []
    for asset in bundle["assets"]:
        name = asset["name"]
        remote = by_name.get(name)
        if remote is None:
            if pending:
                raise ReleaseError("Previous mutation outcome is unknown. Missing asset was not uploaded again; reconcile later.")
            if reconcile_only or not release["draft"]:
                raise ReleaseError("Expected release asset is missing. Published releases are never modified automatically.")
            state["intent"] = {"operation": "upload", "asset": name, "sha256": asset["sha256"]}
            atomic_json(journal_path, state)
            remote = github.upload(release_id, directory / name, asset["contentType"])
        if (not isinstance(remote, dict) or remote.get("name") != name or remote.get("size") != asset["size"]
                or github.asset_hash(_id(remote.get("id")), asset["size"]) != asset["sha256"]):
            raise ReleaseError("Existing/uploaded asset bytes differ. Nothing was replaced or deleted.")
        verified.append(name)
        intent = state.get("intent")
        if intent and intent.get("operation") == "upload" and intent.get("asset") == name:
            state["intent"] = None
            atomic_json(journal_path, state)
            pending = None
    if pending and pending.get("operation") == "publish":
        if release["draft"]:
            raise ReleaseError("Public publication outcome is not confirmed. No second publish request was sent.")
        state["intent"] = None
        atomic_json(journal_path, state)
    if make_public and release["draft"] and not reconcile_only:
        state["intent"] = {"operation": "publish"}
        atomic_json(journal_path, state)
        github.json("PATCH", path + f"/{release_id}", {"draft": False})
        release = github.json("GET", path + f"/{release_id}")
        if (not isinstance(release, dict) or release.get("draft") is not False or release.get("tag_name") != bundle["tag"]
                or marker not in release.get("body", "")):
            raise ReleaseError("Publication was not confirmed; reconcile before any retry.")
        state["intent"] = None
        atomic_json(journal_path, state)
    return {"found": True, "tag": bundle["tag"], "published": not release["draft"], "verifiedAssets": verified,
            "url": f"https://github.com/{REPO}/releases/tag/{bundle['tag']}", "manifestSha256": bundle["manifestSha256"]}


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="action", required=True)
    local = commands.add_parser("prepare", help="Verify local files and prepare a release bundle; no upload.")
    local.add_argument("--apk", type=Path, required=True)
    local.add_argument("--source-zip", type=Path, required=True)
    local.add_argument("--aapt", default="aapt")
    local.add_argument("--apksigner", default="apksigner")
    local.add_argument("--version", default=DEFAULT_VERSION)
    local.add_argument("--code", type=int, default=DEFAULT_CODE)
    local.add_argument("--tag")
    local.add_argument("--source-commit")
    local.add_argument("--notes", type=Path, help="UTF-8 public notes, never logs/private state.")
    local.add_argument("--output", type=Path)
    local.add_argument("--stable", action="store_true", help="Publish as stable instead of preview.")
    remote = commands.add_parser("publish", help="Create/upload a draft; --publish is required to make it public.")
    remote.add_argument("--bundle", type=Path, required=True)
    remote.add_argument("--publish", action="store_true", help="After verifying every uploaded asset, publish the draft.")
    reconcile = commands.add_parser("reconcile", help="Read remote state and verify bytes, without remote mutations.")
    reconcile.add_argument("--bundle", type=Path, required=True)
    return root


def main(argv=None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.action == "prepare":
            result = prepare(args)
        else:
            github = GitHub(os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or "")
            result = publish(args.bundle, github, getattr(args, "publish", False), args.action == "reconcile")
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (ReleaseError, OSError, UnicodeError) as error:
        # OSError text can include private paths; emit only our explicitly safe messages.
        print("Release error: " + (str(error) if isinstance(error, ReleaseError) else "Local files could not be read or persisted."), file=sys.stderr)
        return 2
    except Exception:
        # Unexpected parser/network library failures must not dump raw responses, paths, or credentials.
        print("Release error: verification could not complete. Keep the journal and inspect release state before retrying.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
