"""Offline contract/recovery tests; no production key or network is used."""
from contextlib import contextmanager
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import urllib.parse

import ticketpilot_relay as relay


class Response(io.BytesIO):
    def __init__(self, content, headers=None):
        super().__init__(content)
        self.headers = headers or {}
        self.status = 200


class Server:
    def __init__(self):
        self.requests = []
        self.item = None
        self.creates = {}
        self.files = {}
        self.serial = 0
        self.permission = "manage"
        self.enabled = True
        self.principal = "key-test"
        self.fail_after = None
        self.bad_digest_bytes = False
        self.after_put = None
        self.signed_headers = {"Content-Type": "application/octet-stream"}
        self.retain_pending = False
        self.max_response = False
        self.merged_pending = False

    def event(self, name):
        if self.fail_after == name:
            self.fail_after = None
            raise relay.RelayError("Network result is unknown; resume with the same journal to reconcile it.")

    def result(self, value):
        return json.dumps({"data": value}).encode(), {}

    def request(self, method, url, headers, body=None, maximum=relay.MAX_JSON):
        self.requests.append((method, url, dict(headers), body))
        parsed = urllib.parse.urlsplit(url)
        path = parsed.path
        if parsed.netloc == "storage.example":
            if method != "PUT":
                raise AssertionError("Unexpected request")
            file_id, number = path.strip("/").split("/")
            row = self.files[file_id]
            row["parts"][int(number)] = body
            if self.after_put:
                self.after_put(file_id, int(number))
            self.event("put")
            return b"", {"ETag": '"%s"' % hashlib.md5(body).hexdigest()}
        assert headers.get("Authorization") == "Bearer fake-private-token"
        if path == "/api/v1/me":
            return self.result({"kind": "api", "token": {"id": self.principal,
                "permission": self.permission, "relayEnabled": self.enabled}})
        if path == "/api/v1/relay" and method == "GET":
            return self.result({"items": [self.public_item()] if self.item else [], "total": 1 if self.item else 0,
                                "hasMore": False, "storage": {"ossConfigured": True}})
        value = json.loads(body) if body else None
        if path == "/api/v1/relay" and method == "POST":
            request_id = value["clientRequestId"]
            if request_id not in self.creates:
                self.item = dict(value, id="item-test", files=[])
                self.creates[request_id] = self.item
            self.event("create")
            return self.result(self.public_item())
        pieces = path[len("/api/v1/relay/"):].split("/")
        assert pieces[0] == "item-test"
        if len(pieces) == 1 and method == "GET":
            return self.result(self.public_item())
        if len(pieces) == 2 and pieces[1] == "files":
            for file_id, row in self.files.items():
                if row["clientRequestId"] == value["clientRequestId"]:
                    return self.result(self.session(file_id))
            self.serial += 1
            file_id = "file-%d" % self.serial
            self.files[file_id] = dict(value, id=file_id, status="pending", parts={})
            self.event("initiate")
            return self.result(self.session(file_id))
        file_id = pieces[2]
        row = self.files[file_id]
        if pieces[-1] == "parts" and method == "GET":
            if self.merged_pending and row["status"] == "pending":
                raise relay.RelayError("Multipart upload no longer exists after OSS merge.")
            session = self.session(file_id)
            session["parts"] = [{"partNumber": number, "etag": '"%s"' % hashlib.md5(data).hexdigest()}
                                for number, data in sorted(row["parts"].items())]
            return self.result(session)
        if pieces[-1] == "parts" and method == "POST":
            return self.result({"parts": [{"partNumber": number,
                "url": "https://storage.example/%s/%d?signature=private-url" % (file_id, number),
                "headers": self.signed_headers} for number in value["partNumbers"]]})
        if pieces[-1] == "complete":
            count = self.session(file_id)["partCount"]
            assert [part["partNumber"] for part in value["parts"]] == list(range(1, count + 1))
            assert sum(len(value) for value in row["parts"].values()) == row["size"]
            if self.fail_after == "complete_pending":
                self.merged_pending = True
                self.event("complete_pending")
            if not self.retain_pending:
                row["status"] = "ready"
            self.event("complete")
            return self.result(self.public_item())
        if pieces[-1] == "download":
            return self.result({"url": "https://download.example/%s?token=download-secret" % file_id,
                                "expiresAt": "2026-10-28T00:01:00Z"})
        raise AssertionError("Unexpected request path")

    def public_item(self):
        item = {key: value for key, value in self.item.items() if key != "clientRequestId"}
        item["files"] = [{key: value for key, value in row.items() if key not in ("parts", "clientRequestId")}
                         for row in self.files.values()]
        return item

    def session(self, file_id):
        row = self.files[file_id]
        return {"fileId": file_id, "partSize": relay.PART_SIZE,
                "partCount": (row["size"] + relay.PART_SIZE - 1) // relay.PART_SIZE,
                "status": row["status"], "uploadId": "upload-test"}

    @contextmanager
    def open(self, method, url, headers, body=None):
        self.requests.append((method, url, dict(headers), body))
        assert "Authorization" not in headers
        file_id = urllib.parse.urlsplit(url).path[1:]
        row = self.files[file_id]
        data = b"".join(row["parts"][number] for number in sorted(row["parts"]))
        if self.bad_digest_bytes:
            data = b"x" * len(data)
        yield Response(data, {"Content-Length": str(len(data))})


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.file = self.root / "ticketpilot.apk"
        self.file.write_bytes(b"APK-public-fixture")
        self.journal = relay.Journal(self.root / "upload.json")
        self.server = Server()
        self.client = relay.RelayClient("https://relay.example/api/v1", "fake-private-token", self.server)

    def tearDown(self):
        self.directory.cleanup()

    def run_upload(self, files=None, text="Public development notes"):
        with self.journal.locked():
            return relay.upload(self.client, self.journal, "TicketPilot development", text,
                                [self.file] if files is None else files)

    def test_nested_me_permissions(self):
        self.assertEqual(self.client.verify(True), {"id": "key-test", "permission": "manage", "relayEnabled": True})

    def test_query_key_reads_but_cannot_upload(self):
        self.server.permission = "query"
        self.client.verify()
        with self.assertRaises(relay.RelayError):
            self.run_upload()
        self.assertEqual(self.server.creates, {})

    def test_relay_disabled_cannot_read(self):
        self.server.enabled = False
        with self.assertRaises(relay.RelayError):
            self.client.verify()

    def test_upload_ready_and_storage_never_receives_bearer(self):
        result = self.run_upload()
        self.assertTrue(result["ready"])
        self.assertEqual(result["files"][0]["sha256"], relay.digest(self.file))
        puts = [request for request in self.server.requests if request[0] == "PUT"]
        self.assertEqual(len(puts), 1)
        self.assertNotIn("Authorization", puts[0][2])
        self.assertEqual(puts[0][3], self.file.read_bytes())

    def test_text_roundtrip_does_not_use_truncated_list_text(self):
        text = "Synthetic public text\n" * 300
        result = self.run_upload(files=[], text=text)
        self.assertEqual(self.client.get(result["id"])["text"], text)
        self.assertEqual(result["files"], [])

    def test_upload_group_multiple_assets(self):
        second = self.root / "source.zip"
        second.write_bytes(b"source")
        result = self.run_upload(files=[self.file, second])
        self.assertEqual(len(result["files"]), 2)
        self.assertEqual({row["status"] for row in self.server.files.values()}, {"ready"})

    def test_journal_contains_neither_key_signed_url_nor_raw_text(self):
        self.run_upload()
        saved = self.journal.path.read_text()
        for secret in ("fake-private-token", "private-url", "signature=", "Public development notes"):
            self.assertNotIn(secret, saved)
        if os.name != "nt":
            self.assertEqual(self.journal.path.stat().st_mode & 0o777, 0o600)

    def test_successful_resume_makes_no_mutating_requests(self):
        self.run_upload()
        self.server.requests.clear()
        self.run_upload()
        self.assertTrue(all(request[0] == "GET" for request in self.server.requests))
        self.assertEqual(len(self.server.creates), 1)

    def test_unknown_create_reconciles_with_same_request_id(self):
        self.server.fail_after = "create"
        with self.assertRaises(relay.RelayError):
            self.run_upload()
        created_id = self.journal.state["createId"]
        self.server.requests.clear()
        result = self.run_upload()
        self.assertTrue(result["ready"])
        self.assertEqual(len(self.server.creates), 1)
        self.assertEqual(self.journal.state["createId"], created_id)
        methods = [(method, urllib.parse.urlsplit(url).path) for method, url, _, _ in self.server.requests]
        self.assertLess(methods.index(("GET", "/api/v1/relay")), methods.index(("POST", "/api/v1/relay")))

    def test_unknown_initiation_recovers_one_file(self):
        self.server.fail_after = "initiate"
        with self.assertRaises(relay.RelayError):
            self.run_upload()
        result = self.run_upload()
        self.assertTrue(result["ready"])
        self.assertEqual(len(self.server.files), 1)

    def test_unknown_put_reconciles_authoritative_parts_without_reupload(self):
        self.server.fail_after = "put"
        with self.assertRaises(relay.RelayError):
            self.run_upload()
        self.server.requests.clear()
        self.run_upload()
        self.assertFalse(any(request[0] == "PUT" for request in self.server.requests))

    def test_unknown_complete_reads_ready_without_replaying_complete(self):
        self.server.fail_after = "complete"
        with self.assertRaises(relay.RelayError):
            self.run_upload()
        self.server.requests.clear()
        self.run_upload()
        self.assertTrue(all(request[0] == "GET" for request in self.server.requests))

    def test_merged_oss_with_pending_db_recovers_via_idempotent_complete(self):
        self.server.fail_after = "complete_pending"
        with self.assertRaises(relay.RelayError):
            self.run_upload()
        self.server.requests.clear()
        self.assertTrue(self.run_upload()["ready"])
        self.assertFalse(any(method == "PUT" for method, _, _, _ in self.server.requests))
        completions = [url for method, url, _, _ in self.server.requests if method == "POST" and url.endswith("/complete")]
        self.assertEqual(len(completions), 1)

    def test_changed_file_rejected_before_writing(self):
        self.run_upload()
        self.file.write_bytes(b"different bytes")
        self.server.requests.clear()
        with self.assertRaises(relay.RelayError):
            self.run_upload()
        self.assertFalse(any(request[0] != "GET" for request in self.server.requests))

    def test_changed_key_identity_rejected_before_writing(self):
        self.run_upload()
        self.server.principal = "another-key"
        self.server.requests.clear()
        with self.assertRaises(relay.RelayError):
            self.run_upload()
        self.assertFalse(any(request[0] != "GET" for request in self.server.requests))

    def test_changed_text_rejected_before_writing(self):
        self.run_upload()
        with self.assertRaises(relay.RelayError):
            self.run_upload(text="Changed content")
        self.assertEqual(len(self.server.creates), 1)

    def test_two_part_boundary_and_exact_bytes(self):
        payload = b"a" * relay.PART_SIZE + b"tail"
        self.file.write_bytes(payload)
        self.run_upload()
        puts = [request for request in self.server.requests if request[0] == "PUT"]
        self.assertEqual([len(row[3]) for row in puts], [relay.PART_SIZE, 4])
        self.assertEqual(b"".join(row[3] for row in puts), payload)

    def test_unsafe_signed_header_is_rejected_before_upload(self):
        self.server.signed_headers = {"Authorization": "Bearer wrong-destination"}
        with self.assertRaises(relay.RelayError):
            self.run_upload()
        self.assertFalse(any(request[0] == "PUT" for request in self.server.requests))

    def test_complete_not_ready_is_not_success(self):
        self.server.retain_pending = True
        with self.assertRaises(relay.RelayError):
            self.run_upload()
        self.assertFalse(self.journal.state.get("finished", False))

    def test_download_verified_and_no_bearer(self):
        result = self.run_upload()
        output = self.root / "verified.apk"
        answer = self.client.download(result["id"], result["files"][0]["fileId"], output, relay.digest(self.file))
        self.assertTrue(answer["verified"])
        self.assertEqual(output.read_bytes(), self.file.read_bytes())
        self.assertNotIn("Authorization", self.server.requests[-1][2])

    def test_bad_download_checksum_cleans_temporary_and_does_not_publish(self):
        result = self.run_upload()
        self.server.bad_digest_bytes = True
        output = self.root / "verified.apk"
        with self.assertRaises(relay.RelayError):
            self.client.download(result["id"], result["files"][0]["fileId"], output, relay.digest(self.file))
        self.assertFalse(output.exists())
        self.assertEqual(list(self.root.glob(".relay-download-*")), [])

    def test_download_does_not_overwrite_an_existing_file(self):
        result = self.run_upload()
        output = self.root / "existing.apk"
        output.write_bytes(b"keep")
        with self.assertRaises(relay.RelayError):
            self.client.download(result["id"], result["files"][0]["fileId"], output, relay.digest(self.file))
        self.assertEqual(output.read_bytes(), b"keep")

    def test_lock_prevents_concurrent_writers(self):
        with self.journal.locked():
            with self.assertRaises(relay.RelayError):
                with relay.Journal(self.journal.path).locked():
                    self.fail("Cannot acquire duplicate lock")

    def test_reject_symlink_input(self):
        alias = self.root / "link.apk"
        try:
            alias.symlink_to(self.file)
        except OSError:
            self.skipTest("Symlinks unavailable")
        with self.assertRaises(relay.RelayError):
            relay.local_files([alias])

    def test_private_credential_file_and_no_argument_token(self):
        file = self.root / "credentials.json"
        file.write_text(json.dumps({"apiBase": "https://relay.example", "accessToken": "private-token"}))
        if os.name != "nt":
            file.chmod(0o644)
            with self.assertRaises(relay.RelayError):
                relay.load_credentials(file, {})
        file.chmod(0o600)
        self.assertEqual(relay.load_credentials(file, {}), ("https://relay.example/api/v1", "private-token"))

    def test_reject_http_credentials_userinfo_and_query_bases(self):
        for url in ("http://relay.example", "https://u:p@relay.example", "https://relay.example?key=private",
                    "https://relay.example:8443", "https://relay.example/api/v1/../secret"):
            with self.subTest(url=url), self.assertRaises(relay.RelayError):
                relay.normalize_base(url)

    def test_redirect_handler_refuses_forwarding(self):
        with self.assertRaises(relay.RelayError):
            relay.NoRedirect().redirect_request(None, None, 302, "", {}, "https://elsewhere.example")

    def test_text_limit_uses_utf8_bytes(self):
        with self.assertRaises(relay.RelayError):
            relay.validate_text("TicketPilot", "字" * (relay.MAX_TEXT // 3 + 1), None)

    def test_changed_remote_metadata_stops_resume(self):
        self.run_upload()
        self.server.files["file-1"]["filename"] = "wrong.apk"
        self.server.requests.clear()
        with self.assertRaises(relay.RelayError):
            self.run_upload()
        self.assertTrue(all(request[0] == "GET" for request in self.server.requests))


if __name__ == "__main__":
    unittest.main()
