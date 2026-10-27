#!/usr/bin/env python3
"""TicketPilot's explicit, resumable Foresight file-relay handoff (Python 3.9+).

Credentials and signed URLs are never printed or written to the upload journal.
This utility performs only the command requested; it does not run phone commands.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid

MAX_FILE = 1024 * 1024 * 1024
MAX_TEXT = 1024 * 1024
MAX_JSON = 2 * 1024 * 1024
PART_SIZE = 8 * 1024 * 1024
ID = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")
HEX64 = re.compile(r"[a-f0-9]{64}\Z")


class RelayError(Exception):
    """A fixed, safe operator message, with no raw HTTP or transport exception."""


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def fingerprint(value) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":")).encode("utf-8")).hexdigest()


def resource(value) -> str:
    if not isinstance(value, str) or not ID.fullmatch(value):
        raise RelayError("The service returned an invalid resource identifier.")
    return value


def https_url(value: str) -> str:
    if not isinstance(value, str) or len(value) > 16384 or any(ord(c) < 33 for c in value):
        raise RelayError("An invalid HTTPS address was rejected.")
    try:
        parsed = urllib.parse.urlsplit(value)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
                or parsed.password is not None or parsed.fragment or parsed.port not in (None, 443)):
            raise ValueError()
    except ValueError:
        raise RelayError("An invalid HTTPS address was rejected.") from None
    return value


def normalize_base(value: str) -> str:
    parsed = urllib.parse.urlsplit(https_url(value.rstrip("/")))
    if parsed.query or parsed.path not in ("", "/api/v1"):
        raise RelayError("Use the Foresight HTTPS origin or its /api/v1 endpoint.")
    return urllib.parse.urlunsplit(("https", parsed.netloc.lower(), "/api/v1", "", ""))


def load_credentials(path: Path | None = None, environ=None) -> tuple[str, str]:
    env = os.environ if environ is None else environ
    values = {}
    if path:
        try:
            if path.is_symlink() or not path.is_file() or path.stat().st_size > 16384:
                raise RelayError("Credential file is invalid.")
            if os.name != "nt" and path.stat().st_mode & (stat.S_IRWXG | stat.S_IRWXO):
                raise RelayError("Credential file must be private (chmod 600).")
            values = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(values, dict) or set(values) - {"apiBase", "accessToken"}:
                raise RelayError("Credential file has unexpected fields.")
        except (OSError, ValueError, UnicodeError):
            raise RelayError("Could not read the private credential file.") from None
    base = env.get("FORESIGHT_API_BASE") or values.get("apiBase")
    token = env.get("FORESIGHT_ACCESS_TOKEN") or values.get("accessToken")
    if (not isinstance(base, str) or not isinstance(token, str) or not 8 <= len(token) <= 4096
            or any(ord(c) < 33 or ord(c) > 126 for c in token)):
        raise RelayError("Configure FORESIGHT_API_BASE and FORESIGHT_ACCESS_TOKEN privately.")
    return normalize_base(base), token


def atomic_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".relay-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write((json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        if os.name != "nt":
            os.chmod(path, 0o600)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RelayError("A redirect was rejected; no credentials or file bytes were forwarded.")


class HttpTransport:
    def __init__(self):
        self.opener = urllib.request.build_opener(NoRedirect())

    @contextmanager
    def open(self, method: str, url: str, headers: dict, body: bytes | None = None):
        request = urllib.request.Request(https_url(url), data=body, headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=90 if method == "PUT" else 45)
        except urllib.error.HTTPError as error:
            code = error.code
            error.close()
            raise RelayError("HTTP %d; inspect service permissions or network before resuming the same journal." % code) from None
        except RelayError:
            raise
        except (OSError, urllib.error.URLError, ValueError):
            raise RelayError("Network result is unknown; resume with the same journal to reconcile it.") from None
        try:
            if not 200 <= response.status < 300:
                raise RelayError("The service did not return a successful HTTP status.")
            yield response
        except (OSError, urllib.error.URLError):
            raise RelayError("Network result is unknown; resume with the same journal to reconcile it.") from None
        finally:
            response.close()

    def request(self, method, url, headers, body=None, maximum=MAX_JSON):
        with self.open(method, url, headers, body) as response:
            data = response.read(maximum + 1)
            if len(data) > maximum:
                raise RelayError("The remote response exceeded its size limit.")
            return data, dict(response.headers)


class RelayClient:
    def __init__(self, base: str, token: str, transport=None):
        self.base = normalize_base(base)
        self.token = token
        self.transport = transport or HttpTransport()

    def api(self, method: str, path: str, value=None):
        if not path.startswith("/") or path.startswith("//") or "#" in path:
            raise RelayError("Invalid API path.")
        headers = {"Authorization": "Bearer " + self.token, "Accept": "application/json",
                   "User-Agent": "TicketPilot-Relay/1", "Cache-Control": "no-store"}
        body = None
        if value is not None:
            body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            if len(body) > 1200 * 1024:
                raise RelayError("Request body exceeds the relay limit.")
            headers["Content-Type"] = "application/json"
        raw, _ = self.transport.request(method, self.base + path, headers, body)
        try:
            envelope = json.loads(raw.decode("utf-8"))
            if not isinstance(envelope, dict) or "data" not in envelope:
                raise ValueError()
            return envelope["data"]
        except (ValueError, UnicodeError):
            raise RelayError("The service returned an invalid JSON envelope.") from None

    def verify(self, manage=False) -> dict:
        data = self.api("GET", "/me")
        token = data.get("token") if isinstance(data, dict) else None
        if (not isinstance(token, dict) or token.get("relayEnabled") is not True
                or token.get("permission") not in ("query", "manage")
                or (manage and token.get("permission") != "manage")):
            raise RelayError("The configured key lacks the required relay permission.")
        return {"id": resource(token.get("id")), "permission": token["permission"], "relayEnabled": True}

    def items(self, query="TicketPilot", limit=30, offset=0):
        if not isinstance(query, str) or len(query) > 200 or not 1 <= limit <= 50 or not 0 <= offset <= 100000:
            raise RelayError("Invalid list query.")
        data = self.api("GET", "/relay?" + urllib.parse.urlencode(
            {"query": query, "limit": limit, "offset": offset, "type": "all"}))
        if not isinstance(data, dict) or not isinstance(data.get("items"), list):
            raise RelayError("The service returned an invalid relay list.")
        return data

    def get(self, item_id: str):
        data = self.api("GET", "/relay/" + resource(item_id))
        if not isinstance(data, dict) or data.get("id") != item_id or not isinstance(data.get("files"), list):
            raise RelayError("The service returned an invalid relay item.")
        return data

    def put_part(self, signed: dict, data: bytes) -> str:
        url = https_url(signed.get("url"))
        supplied = signed.get("headers", {})
        if not isinstance(supplied, dict) or len(supplied) > 20:
            raise RelayError("The storage service returned invalid headers.")
        headers = {"Content-Length": str(len(data))}
        for name, value in supplied.items():
            if (not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9-]{1,80}", name)
                    or not isinstance(value, str) or len(value) > 8192
                    or any(ord(c) < 32 or ord(c) > 126 for c in value)
                    or name.lower() not in {"content-type", "content-md5"}):
                raise RelayError("Unexpected storage headers were rejected.")
            headers[name] = value
        # This request has its own header set; never inherit the Foresight Bearer.
        _, response_headers = self.transport.request("PUT", url, headers, data, maximum=4096)
        etag = next((value for name, value in response_headers.items() if name.lower() == "etag"), None)
        validate_etag(etag)
        return etag

    def download(self, item_id: str, file_id: str, output: Path, expected_sha256: str):
        if not HEX64.fullmatch(expected_sha256):
            raise RelayError("An independently known SHA-256 is required for download verification.")
        item = self.get(item_id)
        file = find_file(item, resource(file_id))
        if file.get("status") != "ready":
            raise RelayError("The requested attachment is not ready.")
        size = file.get("size")
        if type(size) is not int or not 0 < size <= MAX_FILE:
            raise RelayError("The attachment size is invalid.")
        if output.exists():
            if output.is_file() and output.stat().st_size == size and digest(output) == expected_sha256:
                return {"id": item_id, "fileId": file_id, "size": size, "sha256": expected_sha256, "verified": True}
            raise RelayError("Download destination already exists with different bytes; it was not overwritten.")
        capability = self.api("GET", "/relay/%s/files/%s/download" % (resource(item_id), file_id))
        if not isinstance(capability, dict):
            raise RelayError("The service returned an invalid download capability.")
        url = https_url(capability.get("url"))
        output.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(prefix=".relay-download-", dir=output.parent)
        try:
            got = 0
            checksum = hashlib.sha256()
            with os.fdopen(descriptor, "wb") as destination:
                # Even a same-origin short-time /content URL does not receive the Bearer.
                with self.transport.open("GET", url, {"Accept": "application/octet-stream"}) as response:
                    content_length = response.headers.get("Content-Length")
                    if content_length is not None and content_length != str(size):
                        raise RelayError("Download size disagrees with the ready attachment metadata.")
                    while True:
                        block = response.read(min(1024 * 1024, size + 1 - got))
                        if not block:
                            break
                        got += len(block)
                        if got > size:
                            raise RelayError("Downloaded bytes exceed the expected size.")
                        checksum.update(block)
                        destination.write(block)
                destination.flush()
                os.fsync(destination.fileno())
            if got != size or checksum.hexdigest() != expected_sha256:
                raise RelayError("Downloaded bytes failed the size/SHA-256 verification.")
            # Do not replace a file another process created while the download ran.
            try:
                os.link(temporary, output)
            except FileExistsError:
                raise RelayError("Download destination appeared during transfer; it was not overwritten.") from None
            return {"id": item_id, "fileId": file_id, "size": got, "sha256": expected_sha256, "verified": True}
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


def validate_etag(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 160 or any(ord(c) < 33 or ord(c) > 126 for c in value):
        raise RelayError("The storage service returned an invalid ETag.")


def find_file(item: dict, file_id: str) -> dict:
    matches = [row for row in item.get("files", []) if isinstance(row, dict) and row.get("id") == file_id]
    if len(matches) != 1:
        raise RelayError("The expected attachment could not be identified uniquely.")
    return matches[0]


class Journal:
    def __init__(self, path: Path):
        self.path = path
        self.state = {}

    @contextmanager
    def locked(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        lock = self.path.with_name(self.path.name + ".lock")
        if self.path.is_symlink():
            raise RelayError("A symlink journal was rejected.")
        try:
            descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError:
            raise RelayError("Another operator or interrupted process owns this journal lock; inspect it before resuming.") from None
        try:
            os.close(descriptor)
            if self.path.exists():
                try:
                    if self.path.stat().st_size > MAX_JSON:
                        raise ValueError()
                    self.state = json.loads(self.path.read_text(encoding="utf-8"))
                    if not isinstance(self.state, dict) or self.state.get("schema") != 1:
                        raise ValueError()
                except (OSError, ValueError, UnicodeError):
                    raise RelayError("The existing upload journal is invalid; do not delete it to retry.") from None
            yield self
        finally:
            lock.unlink()

    def save(self):
        atomic_json(self.path, self.state)


def validate_text(title, text, retention):
    if (not isinstance(title, str) or not 1 <= len(title) <= 160
            or any(ord(c) < 32 for c in title) or not isinstance(text, str)
            or len(text.encode("utf-8")) > MAX_TEXT or retention not in (None, 1, 7, 30)):
        raise RelayError("Relay title, text or retention is invalid.")


def local_files(paths: list[Path]) -> list[dict]:
    if len(paths) > 20:
        raise RelayError("A relay item accepts at most 20 files.")
    results = []
    for path in paths:
        if (path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_FILE
                or not 1 <= len(path.name) <= 240 or any(ord(c) < 32 for c in path.name)):
            raise RelayError("An input file is invalid or exceeds the relay file limit.")
        content_type = ("application/vnd.android.package-archive" if path.suffix.lower() == ".apk"
                        else mimetypes.guess_type(path.name)[0] or "application/octet-stream")
        results.append({"filename": path.name, "contentType": content_type,
                        "size": path.stat().st_size, "sha256": digest(path)})
    if len({row["filename"] for row in results}) != len(results):
        raise RelayError("Duplicate file names in one handoff are not allowed.")
    return results


def verify_file_metadata(actual, expected):
    if any(actual.get(key) != expected[key] for key in ("filename", "contentType", "size")):
        raise RelayError("Remote file metadata disagrees with the frozen local input.")


def upload(client: RelayClient, journal: Journal, title: str, text: str,
           files: list[Path], retention=None) -> dict:
    validate_text(title, text, retention)
    identities = local_files(files)
    if not identities and not text:
        raise RelayError("Provide text or at least one file.")
    identity = client.verify(manage=True)
    request = {"title": title, "text": text, "retentionDays": retention}
    frozen = fingerprint({"request": request, "files": identities})
    state = journal.state
    if state:
        if (state.get("base") != client.base or state.get("principalId") != identity["id"]
                or state.get("fingerprint") != frozen):
            raise RelayError("The journal belongs to different inputs, endpoint or key identity; no write was attempted.")
        saved_files = state.get("files")
        if (not isinstance(saved_files, list) or len(saved_files) != len(identities)
                or any(not isinstance(saved, dict) or any(saved.get(key) != expected[key] for key in expected)
                       for saved, expected in zip(saved_files, identities))):
            raise RelayError("The saved file identities are inconsistent; no write was attempted.")
    else:
        state.update({"schema": 1, "base": client.base, "principalId": identity["id"],
                      "fingerprint": frozen, "createId": "ticketpilot-" + str(uuid.uuid4()),
                      "files": [dict(row, requestId="ticketpilot-file-" + str(uuid.uuid4())) for row in identities]})
        journal.save()
    if not state.get("itemId"):
        if state.get("intent") == "create":
            # Reconcile readable state first. The API does not expose clientRequestId;
            # only an identical request with the persisted id can resolve its outcome.
            client.items(query=title, limit=50)
        state["intent"] = "create"
        journal.save()
        created = client.api("POST", "/relay", dict(request, clientRequestId=state["createId"]))
        if not isinstance(created, dict):
            raise RelayError("The service returned an invalid create result; retain this journal.")
        state["itemId"] = resource(created.get("id"))
        state["intent"] = None
        journal.save()
    item_id = resource(state["itemId"])
    current = client.get(item_id)
    if current.get("title") != title or current.get("text") != text:
        raise RelayError("The relay record changed; no additional bytes were uploaded.")
    for index, path in enumerate(files):
        row = state["files"][index]
        if row.get("fileId"):
            actual = find_file(current, resource(row["fileId"]))
            verify_file_metadata(actual, row)
            if actual.get("status") == "ready":
                row["ready"] = True
                journal.save()
                continue
        if not row.get("fileId"):
            # Unknown initiation is reconciled by fetching the item first, then using
            # the exact same documented idempotency key and immutable metadata.
            if state.get("intent") == "initiate":
                current = client.get(item_id)
            state["intent"] = "initiate"
            journal.save()
            session = client.api("POST", "/relay/%s/files" % item_id,
                                 {key: row[key] for key in ("filename", "contentType", "size")} |
                                 {"clientRequestId": row["requestId"]})
            if not isinstance(session, dict):
                raise RelayError("The service returned an invalid upload session; retain this journal.")
            row["fileId"] = resource(session.get("fileId"))
            state["intent"] = None
            journal.save()
        file_id = resource(row["fileId"])
        base_path = "/relay/%s/files/%s" % (item_id, file_id)
        if state.get("intent") == "complete" and state.get("intentFileId") == file_id:
            count = (row["size"] + PART_SIZE - 1) // PART_SIZE
            saved_parts = row.get("parts")
            if (not isinstance(saved_parts, list) or len(saved_parts) != count
                    or any(not isinstance(part, dict) or part.get("partNumber") != index + 1
                           for index, part in enumerate(saved_parts))):
                raise RelayError("The saved completion record is invalid; retain the journal.")
            for part in saved_parts:
                validate_etag(part.get("etag"))
            # The item was read above. Replaying this documented idempotent complete
            # is needed if OSS merged but its DB update/response was lost: listParts
            # would already fail because that multipart upload no longer exists.
            client.api("POST", base_path + "/complete", {"parts": saved_parts})
            current = client.get(item_id)
            actual = find_file(current, file_id)
            verify_file_metadata(actual, row)
            if actual.get("status") != "ready":
                raise RelayError("Completion is still unresolved; retain the journal.")
            row["ready"] = True
            state["intent"] = None
            journal.save()
            continue
        session = client.api("GET", base_path + "/parts")
        if not isinstance(session, dict) or session.get("fileId") != file_id:
            raise RelayError("The service returned an invalid resumable upload session.")
        if session.get("status") == "ready":
            current = client.get(item_id)
            actual = find_file(current, file_id)
            verify_file_metadata(actual, row)
            if actual.get("status") != "ready":
                raise RelayError("Upload session and attachment readiness disagree.")
            row["ready"] = True
            state["intent"] = None
            journal.save()
            continue
        count = (row["size"] + PART_SIZE - 1) // PART_SIZE
        if session.get("partSize") != PART_SIZE or session.get("partCount") != count:
            raise RelayError("Unexpected multipart dimensions were rejected.")
        parts = {}
        if not isinstance(session.get("parts"), list):
            raise RelayError("The storage service returned invalid parts.")
        for part in session["parts"]:
            number = part.get("partNumber") if isinstance(part, dict) else None
            if type(number) is not int or not 1 <= number <= count or number in parts:
                raise RelayError("The storage service returned invalid part numbers.")
            validate_etag(part.get("etag"))
            parts[number] = part["etag"]
        row["parts"] = [{"partNumber": number, "etag": tag} for number, tag in sorted(parts.items())]
        journal.save()
        # Sequential parts limit memory to 8 MiB, and simplify unknown PUT recovery.
        with path.open("rb") as source:
            read_checksum = hashlib.sha256()
            for number in range(1, count + 1):
                source.seek((number - 1) * PART_SIZE)
                expected = min(PART_SIZE, row["size"] - (number - 1) * PART_SIZE)
                block = source.read(expected)
                if len(block) != expected:
                    raise RelayError("The local file changed during transfer.")
                read_checksum.update(block)
                if number in parts:
                    continue
                signed = client.api("POST", base_path + "/parts", {"partNumbers": [number]})
                candidates = signed.get("parts") if isinstance(signed, dict) else None
                if (not isinstance(candidates, list) or len(candidates) != 1
                        or not isinstance(candidates[0], dict) or candidates[0].get("partNumber") != number):
                    raise RelayError("The storage service returned an invalid signed part.")
                state["intent"] = "put_part"
                state["intentFileId"] = file_id
                journal.save()
                parts[number] = client.put_part(candidates[0], block)
                row["parts"] = [{"partNumber": n, "etag": tag} for n, tag in sorted(parts.items())]
                state["intent"] = None
                journal.save()
        if (read_checksum.hexdigest() != row["sha256"] or path.stat().st_size != row["size"]
                or digest(path) != row["sha256"]):
            raise RelayError("The local file changed during transfer; completion was not requested.")
        state["intent"] = "complete"
        state["intentFileId"] = file_id
        journal.save()
        client.api("POST", base_path + "/complete", {"parts": row["parts"]})
        current = client.get(item_id)
        actual = find_file(current, file_id)
        verify_file_metadata(actual, row)
        if actual.get("status") != "ready":
            raise RelayError("The file has not reached verified ready status; retain the journal.")
        row["ready"] = True
        state["intent"] = None
        journal.save()
    current = client.get(item_id)
    for row in state["files"]:
        actual = find_file(current, resource(row["fileId"]))
        verify_file_metadata(actual, row)
        if actual.get("status") != "ready":
            raise RelayError("A handoff attachment is not ready.")
    state["finished"] = True
    journal.save()
    return {"id": item_id, "url": client.base[:-7] + "/relay", "ready": True,
            "files": [{key: row[key] for key in ("fileId", "filename", "size", "sha256")} for row in state["files"]]}


def safe_summary(item):
    return {key: item.get(key) for key in ("id", "title", "createdAt", "expiresAt", "textTruncated")} | {
        "files": [{key: file.get(key) for key in ("id", "filename", "size", "status")} for file in item.get("files", [])]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--credentials", type=Path, help="Private JSON file; never pass a key in a command argument")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check")
    listing = sub.add_parser("list")
    listing.add_argument("--query", default="TicketPilot")
    listing.add_argument("--limit", type=int, default=30)
    listing.add_argument("--offset", type=int, default=0)
    get = sub.add_parser("get")
    get.add_argument("--id", required=True)
    get.add_argument("--output", type=Path, required=True, help="Private full-text JSON output")
    send = sub.add_parser("send-text")
    upload_parser = sub.add_parser("upload")
    for command in (send, upload_parser):
        command.add_argument("--title", required=True)
        command.add_argument("--text-file", type=Path, required=command is send)
        command.add_argument("--journal", type=Path, required=True)
        command.add_argument("--retention-days", type=int, choices=(1, 7, 30))
    upload_parser.add_argument("--file", type=Path, action="append", required=True)
    download = sub.add_parser("download")
    download.add_argument("--id", required=True)
    download.add_argument("--file-id", required=True)
    download.add_argument("--output", type=Path, required=True)
    download.add_argument("--sha256", required=True)
    args = parser.parse_args(argv)
    try:
        base, token = load_credentials(args.credentials)
        client = RelayClient(base, token)
        if args.command == "check":
            result = client.verify(manage=True)
        elif args.command == "list":
            client.verify()
            listed = client.items(args.query, args.limit, args.offset)
            result = {key: listed.get(key) for key in ("total", "hasMore", "storage")} | {
                "items": [safe_summary(item) for item in listed["items"]]}
        elif args.command == "get":
            client.verify()
            item = client.get(args.id)
            if args.output.exists():
                raise RelayError("The full-text output already exists; choose a new private path.")
            atomic_json(args.output, item)
            result = safe_summary(item)
        elif args.command == "download":
            client.verify()
            result = client.download(args.id, args.file_id, args.output, args.sha256)
        else:
            text = ""
            if args.text_file:
                if not args.text_file.is_file() or args.text_file.stat().st_size > MAX_TEXT:
                    raise RelayError("Text input is missing or exceeds the relay text limit.")
                text = args.text_file.read_text(encoding="utf-8")
            journal = Journal(args.journal)
            with journal.locked():
                result = upload(client, journal, args.title, text,
                                args.file if args.command == "upload" else [], args.retention_days)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except RelayError as error:
        print(json.dumps({"error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1
    except (OSError, ValueError, UnicodeError, KeyError, TypeError):
        print(json.dumps({"error": "Local input or remote format is invalid; retain the journal and inspect safely."}), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print(json.dumps({"error": "Stopped; resume with the same immutable inputs and journal."}), file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
