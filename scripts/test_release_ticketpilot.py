"""Offline release tests; no actual GitHub requests, signing, or device operations."""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("release_ticketpilot", Path(__file__).with_name("release_ticketpilot.py"))
r = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(r)


class FakeGitHub:
    def __init__(self):
        self.release = None
        self.assets = {}
        self.mutations = []
        self.fail = ""

    def json(self, method, path, value=None, allow_missing=False):
        if method == "GET" and "/tags/" in path:
            return copy.deepcopy(self.release)
        if method == "POST":
            self.mutations.append("create")
            if self.fail == "create_before":
                raise r.ReleaseError("unknown create outcome")
            self.release = dict(value, id=7)
            if self.fail == "create_after":
                self.fail = ""
                raise r.ReleaseError("unknown create outcome")
            return copy.deepcopy(self.release)
        if method == "GET" and "/assets?" in path:
            return [dict(asset, id=identifier) for identifier, (asset, content) in self.assets.items()]
        if method == "PATCH":
            self.mutations.append("publish")
            if self.fail == "publish_before":
                raise r.ReleaseError("unknown publication outcome")
            self.release.update(value)
            if self.fail == "publish_after":
                self.fail = ""
                raise r.ReleaseError("unknown publication outcome")
            return copy.deepcopy(self.release)
        if method == "GET":
            return copy.deepcopy(self.release)
        raise AssertionError((method, path))

    def upload(self, release_id, path, content_type):
        self.mutations.append("upload:" + path.name)
        if self.fail == "upload_before":
            raise r.ReleaseError("unknown upload outcome")
        content = path.read_bytes()
        identifier = len(self.assets) + 11
        asset = {"name": path.name, "size": len(content)}
        self.assets[identifier] = (asset, content)
        if self.fail == "upload_after":
            self.fail = ""
            raise r.ReleaseError("unknown upload outcome")
        return dict(asset, id=identifier)

    def asset_hash(self, identifier, size):
        asset, content = self.assets[identifier]
        if len(content) != size:
            raise r.ReleaseError("incomplete asset")
        return hashlib.sha256(content).hexdigest()


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.apk = self.root / "input.apk"
        self.apk.write_bytes(b"APK fixture, not a signed installation package")
        self.source = self.root / "source.zip"
        with zipfile.ZipFile(self.source, "w") as archive:
            archive.writestr("AGENTS.md", "Public source handoff")
            archive.writestr("app/build.gradle", "versionCode 20")
            archive.writestr("app/src/main/AndroidManifest.xml", "<manifest />")
        self.output = self.root / "prepared"
        self.args = argparse.Namespace(apk=self.apk, source_zip=self.source, aapt="fixture-aapt", apksigner="fixture-apksigner",
                    version="0.2.4-preview", code=20, tag=None, source_commit="a" * 40, notes=None, output=self.output, stable=False)
        self.prepared = False

    def tearDown(self):
        self.temp.cleanup()

    def run_tool(self, arguments):
        if arguments[:3] == ["git", "rev-parse", "HEAD"]:
            return "a" * 40
        if arguments[:3] == ["git", "status", "--porcelain"]:
            return ""
        if arguments[0] == "fixture-aapt":
            return "package: name='cn.local.tickethelper' versionCode='20' versionName='0.2.4-preview'\n"
        if arguments[0] == "fixture-apksigner":
            return "Signer #1 certificate SHA-256 digest: " + "b" * 64 + "\n"
        raise AssertionError(arguments)

    def prepare(self):
        result = r.prepare(self.args, self.run_tool)
        self.prepared = True
        return result

    def test_prepare_binds_apk_metadata_hash_and_public_source(self):
        result = self.prepare()
        manifest = json.loads((self.output / r.MANIFEST_NAME).read_text())
        self.assertEqual(20, manifest["versionCode"])
        self.assertEqual("b" * 64, manifest["apk"]["certificateSha256"])
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), manifest["apk"]["sha256"])
        self.assertEqual("https://github.com/csjxing/TicketPilot/releases/download/v0.2.4-preview/ticketpilot-0.2.4-preview.apk", manifest["apk"]["url"])
        self.assertEqual("a" * 40, manifest["sourceCommit"])
        self.assertFalse(result["published"])
        self.assertIn(result["manifestSha256"], (self.output / "release-notes.md").read_text())
        self.assertEqual(3, len(r.load_bundle(self.output)["assets"]))

    def test_output_is_never_overwritten_and_changed_files_block_publish(self):
        self.prepare()
        with self.assertRaises(r.ReleaseError):
            r.prepare(self.args, self.run_tool)
        apk = next(self.output.glob("*.apk"))
        apk.write_bytes(b"changed")
        client = FakeGitHub()
        with self.assertRaises(r.ReleaseError):
            r.publish(self.output, client, True)
        self.assertEqual([], client.mutations)

    def test_dirty_source_or_wrong_apk_version_is_not_prepared(self):
        def dirty(arguments):
            return " M app/src/main/App.java" if arguments[:3] == ["git", "status", "--porcelain"] else self.run_tool(arguments)
        with self.assertRaises(r.ReleaseError):
            r.prepare(self.args, dirty)
        self.assertFalse(self.output.exists())
        self.args.code = 21
        with self.assertRaises(r.ReleaseError):
            self.prepare()
        self.assertFalse((self.output / r.BUNDLE_NAME).exists())

    def test_signature_metadata_requires_one_verified_signer(self):
        def multiple(arguments):
            if arguments[0] == "fixture-apksigner":
                return "Signer #1 certificate SHA-256 digest: " + "b" * 64 + "\nSigner #2 certificate SHA-256 digest: " + "c" * 64
            return self.run_tool(arguments)
        with self.assertRaises(r.ReleaseError):
            r.inspect_apk(self.apk, "fixture-aapt", "fixture-apksigner", multiple)

    def test_archive_allowlist_rejects_keys_runtime_traversal_symlink_and_duplicate(self):
        for name in ["debug.keystore", "app/src/main/assets/signing.key", "handoff/.env.local", ".runtime/device.json", "../secret", "app/src/../../secret", "app/src/main/java/private.log"]:
            with self.subTest(name=name):
                bad = self.root / "unsafe.zip"
                with zipfile.ZipFile(bad, "w") as archive:
                    archive.writestr("AGENTS.md", "public")
                    archive.writestr("app/build.gradle", "public")
                    archive.writestr(name, "not uploaded")
                with self.assertRaises(r.ReleaseError):
                    r.validate_source(bad)
        bad = self.root / "link.zip"
        with zipfile.ZipFile(bad, "w") as archive:
            entry = zipfile.ZipInfo("app/src/link")
            entry.external_attr = 0o120777 << 16
            archive.writestr(entry, "/private/file")
        with self.assertRaises(r.ReleaseError):
            r.validate_source(bad)

    def test_draft_uploads_are_byte_verified_and_publish_is_explicit(self):
        self.prepare()
        client = FakeGitHub()
        draft = r.publish(self.output, client)
        self.assertFalse(draft["published"])
        self.assertEqual(3, len(draft["verifiedAssets"]))
        self.assertNotIn("publish", client.mutations)
        completed = r.publish(self.output, client, True)
        self.assertTrue(completed["published"])
        self.assertEqual(1, client.mutations.count("create"))
        self.assertEqual(1, client.mutations.count("publish"))
        self.assertEqual(3, sum(item.startswith("upload:") for item in client.mutations))
        r.publish(self.output, client, True)
        self.assertEqual(1, client.mutations.count("publish"))

    def test_unknown_create_that_completed_is_adopted_without_duplicate(self):
        self.prepare()
        client = FakeGitHub()
        client.fail = "create_after"
        with self.assertRaises(r.ReleaseError):
            r.publish(self.output, client)
        result = r.publish(self.output, client)
        self.assertFalse(result["published"])
        self.assertEqual(1, client.mutations.count("create"))

    def test_unknown_create_without_evidence_is_not_retried(self):
        self.prepare()
        client = FakeGitHub()
        client.fail = "create_before"
        with self.assertRaises(r.ReleaseError):
            r.publish(self.output, client)
        client.fail = ""
        with self.assertRaises(r.ReleaseError):
            r.publish(self.output, client)
        self.assertEqual(["create"], client.mutations)

    def test_unknown_upload_that_completed_is_verified_without_duplicate(self):
        self.prepare()
        client = FakeGitHub()
        client.fail = "upload_after"
        with self.assertRaises(r.ReleaseError):
            r.publish(self.output, client)
        r.publish(self.output, client)
        first = "upload:ticketpilot-0.2.4-preview.apk"
        self.assertEqual(1, client.mutations.count(first))
        self.assertEqual(3, len(client.assets))

    def test_unknown_upload_without_evidence_is_not_retried(self):
        self.prepare()
        client = FakeGitHub()
        client.fail = "upload_before"
        with self.assertRaises(r.ReleaseError):
            r.publish(self.output, client)
        client.fail = ""
        with self.assertRaises(r.ReleaseError):
            r.publish(self.output, client)
        self.assertEqual(1, client.mutations.count("upload:ticketpilot-0.2.4-preview.apk"))

    def test_unknown_publication_observed_public_does_not_publish_again(self):
        self.prepare()
        client = FakeGitHub()
        client.fail = "publish_after"
        with self.assertRaises(r.ReleaseError):
            r.publish(self.output, client, True)
        self.assertTrue(r.publish(self.output, client, True)["published"])
        self.assertEqual(1, client.mutations.count("publish"))

    def test_unknown_publication_still_draft_is_not_repeated(self):
        self.prepare()
        client = FakeGitHub()
        client.fail = "publish_before"
        with self.assertRaises(r.ReleaseError):
            r.publish(self.output, client, True)
        client.fail = ""
        with self.assertRaises(r.ReleaseError):
            r.publish(self.output, client, True)
        self.assertEqual(1, client.mutations.count("publish"))

    def test_existing_different_release_or_asset_is_never_replaced(self):
        self.prepare()
        client = FakeGitHub()
        r.publish(self.output, client)
        client.release["body"] = "different release"
        baseline = list(client.mutations)
        with self.assertRaises(r.ReleaseError):
            r.publish(self.output, client, True)
        self.assertEqual(baseline, client.mutations)
        marker = json.loads((self.output / r.JOURNAL_NAME).read_text())["manifestSha256"]
        client.release["body"] = "<!-- ticketpilot-release:" + marker + " -->"
        asset, content = client.assets[11]
        client.assets[11] = (asset, b"x" * len(content))
        with self.assertRaises(r.ReleaseError):
            r.publish(self.output, client, True)
        self.assertEqual(baseline, client.mutations)

    def test_reconcile_is_remote_read_only(self):
        self.prepare()
        client = FakeGitHub()
        self.assertFalse(r.publish(self.output, client, reconcile_only=True)["found"])
        self.assertEqual([], client.mutations)
        r.publish(self.output, client)
        baseline = list(client.mutations)
        self.assertFalse(r.publish(self.output, client, reconcile_only=True)["published"])
        self.assertEqual(baseline, client.mutations)

    def test_token_is_environment_only_and_validation_never_echoes_it(self):
        secret = "fixture secret not a real token"
        with self.assertRaises(r.ReleaseError) as failure:
            r.GitHub(secret)
        self.assertNotIn(secret, str(failure.exception))
        help_text = r.parser().format_help()
        self.assertNotIn("--token", help_text)


if __name__ == "__main__":
    unittest.main()
