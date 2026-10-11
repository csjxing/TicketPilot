"""Offline CLI integration: real files/state/journals, forbidden external I/O."""
import contextlib
import copy
import datetime as dt
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from damai import cli
from damai.adapters import FixtureOrderAdapter


ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "tests" / "fixtures"


class CliTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)
        self.runtime = self.path / "runtime"

    def tearDown(self):
        self.temp.cleanup()

    def invoke(self, args):
        """Exercise main while making accidental phone/network I/O a failure."""
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr), \
                mock.patch("socket.create_connection", side_effect=AssertionError("external I/O forbidden")), \
                mock.patch("damai.adapters.ExistingAdbReader.devices", side_effect=AssertionError("device I/O forbidden")), \
                mock.patch("damai.adapters.LiveUIAdapter.observe", side_effect=AssertionError("live UI forbidden")), \
                mock.patch("damai.adapters.LiveUIAdapter.submit_pending", side_effect=AssertionError("live submission forbidden")):
            try:
                code = cli.main(args)
            except SystemExit as exc:
                code = exc.code
        output = stdout.getvalue()
        records = [json.loads(line) for line in output.splitlines() if line.strip()]
        return code, records, output, stderr.getvalue()

    def write_json(self, name, value):
        path = self.path / name
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        return path

    def demo_args(self, simulate=False):
        args = ["--demo", "--runtime-dir", str(self.runtime)]
        if simulate:
            args.append("--simulate-orders")
        return args

    @staticmethod
    def decisions(records):
        return [record for record in records if "state" in record]

    def test_default_demo_plans_without_any_submission_or_order_claim(self):
        with mock.patch.object(FixtureOrderAdapter, "submit_pending", side_effect=AssertionError("submission not enabled")) as submit:
            code, records, _, _ = self.invoke(self.demo_args())
        self.assertEqual(code, 0)
        submit.assert_not_called()
        states = self.decisions(records)
        ready = {row["event_key"] for row in states if row["state"] == "confirmation_ready"}
        self.assertEqual(ready, {"example-alpha", "example-beta"})
        self.assertFalse(any(row["state"] == "pending_payment" for row in states))
        self.assertFalse((self.runtime / "orders").exists())
        self.assertTrue(all(row["simulated"] for row in states))

    def test_healthy_executor_without_current_job_cannot_simulate_submission(self):
        with mock.patch("damai.cli.LocalCoordinator.plan", return_value={"paused": False, "jobs": []}), \
                mock.patch.object(FixtureOrderAdapter, "submit_pending", side_effect=AssertionError("job permission missing")) as submit:
            code, records, _, _ = self.invoke(self.demo_args(simulate=True))
        self.assertEqual(code, 0)
        submit.assert_not_called()
        final = self.decisions(records)[-1]
        self.assertEqual((final["state"], final["scope"]), ("job_unavailable", "session"))
        self.assertFalse((self.runtime / "orders").exists())

    def test_simulation_completes_two_independent_events_and_deduplicates_notice(self):
        original = FixtureOrderAdapter.submit_pending
        with mock.patch.object(FixtureOrderAdapter, "submit_pending", autospec=True,
                               side_effect=lambda adapter, intent: original(adapter, intent)) as submit:
            code, records, _, _ = self.invoke(self.demo_args(simulate=True))
        self.assertEqual(code, 0)
        self.assertEqual(submit.call_count, 2)
        states = self.decisions(records)
        pending = [row for row in states if row["state"] == "pending_payment"]
        by_event = {row["event_key"]: row for row in pending}
        self.assertEqual(set(by_event), {"example-alpha", "example-beta"})
        self.assertEqual(by_event["example-alpha"]["amount_minor"], 132000)
        self.assertEqual(by_event["example-beta"]["amount_minor"], 100000)
        for row in pending:
            self.assertEqual(row["scope"], "event")
            self.assertTrue(row["stop"])
            self.assertTrue(row["simulated"])
            self.assertIsNone(row["proposed_action"])
            self.assertIn("deadline", row)
        first_alpha = next(index for index, row in enumerate(states)
                           if row["event_key"] == "example-alpha" and row["state"] == "pending_payment")
        first_beta = next(index for index, row in enumerate(states)
                          if row["event_key"] == "example-beta" and row["state"] == "pending_payment")
        self.assertLess(first_alpha, first_beta)
        notifications = [row["notification"] for row in records if "notification" in row]
        self.assertEqual(sum(row["emitted"] is True for row in notifications), 2)
        self.assertEqual(sum(row["emitted"] is False for row in notifications), 1)
        self.assertTrue(all(row["mode"] == "simulated" for row in notifications))

    def test_persistent_replay_is_blocked_before_adapter_and_stops_entire_session(self):
        first_code, _, _, _ = self.invoke(self.demo_args(simulate=True))
        self.assertEqual(first_code, 0)
        before = {path.name: path.read_bytes() for path in (self.runtime / "orders").glob("*.json")}
        self.assertEqual(len(before), 4)
        with mock.patch.object(FixtureOrderAdapter, "submit_pending", side_effect=AssertionError("duplicate submission forbidden")) as submit:
            code, records, _, _ = self.invoke(self.demo_args(simulate=True))
        self.assertEqual(code, 0)
        submit.assert_not_called()
        states = self.decisions(records)
        final = states[-1]
        self.assertEqual(final["state"], "order_uncertain")
        self.assertEqual(final["event_key"], "example-alpha")
        self.assertEqual(final["scope"], "session")
        self.assertTrue(final["stop"])
        self.assertIsNone(final["proposed_action"])
        self.assertFalse(any(row["state"] == "pending_payment" for row in states))
        self.assertFalse(any(row["event_key"] == "example-beta" and row["state"] == "confirmation_ready"
                             for row in states))
        after = {path.name: path.read_bytes() for path in (self.runtime / "orders").glob("*.json")}
        self.assertEqual(after, before)

    def test_required_null_example_is_rejected_without_submission_or_journals(self):
        with mock.patch("damai.cli.OrderGuard.attempt", side_effect=AssertionError("invalid configuration cannot submit")) as attempt:
            code, records, _, _ = self.invoke([
                "--check-config", "--config", str(ROOT / "config.example.json"),
                "--runtime-dir", str(self.runtime)
            ])
        self.assertEqual(code, 2)
        self.assertEqual(records[0]["state"], "input_or_adapter_blocked")
        self.assertEqual(records[0]["scope"], "session")
        attempt.assert_not_called()
        self.assertFalse(self.runtime.exists())

    def test_observation_cannot_enable_order_simulation_even_before_input_reads(self):
        with mock.patch("damai.cli.load_config", side_effect=AssertionError("argument rejection should precede file reads")) as load:
            code, records, _, error = self.invoke([
                "--observation", str(self.path / "not-read.json"),
                "--config", str(FIXTURES / "demo-config.json"), "--simulate-orders"
            ])
        self.assertEqual(code, 2)
        self.assertEqual(records, [])
        self.assertIn("--simulate-orders is only available", error)
        load.assert_not_called()
        self.assertFalse(self.runtime.exists())

    def test_live_mode_configuration_is_rejected_without_adapter_call(self):
        config = json.loads((FIXTURES / "demo-config.json").read_text(encoding="utf-8"))
        config["mode"] = "live"
        path = self.write_json("unsupported-live-config.json", config)
        with mock.patch("damai.cli.OrderGuard.attempt", side_effect=AssertionError("live order forbidden")) as attempt:
            code, records, _, _ = self.invoke([
                "--fixture", str(FIXTURES / "demo-observations.json"), "--config", str(path),
                "--simulate-orders", "--runtime-dir", str(self.runtime)
            ])
        self.assertEqual(code, 2)
        self.assertEqual(records[0]["state"], "input_or_adapter_blocked")
        attempt.assert_not_called()
        self.assertFalse(self.runtime.exists())

    def test_real_source_cannot_be_smuggled_into_fixture_replay(self):
        data = json.loads((FIXTURES / "demo-observations.json").read_text(encoding="utf-8"))
        data["observations"][0]["source"] = "cua_repl"
        path = self.write_json("wrong-source.json", data)
        with mock.patch("damai.cli.OrderGuard.attempt", side_effect=AssertionError("real source cannot submit")) as attempt:
            code, records, _, _ = self.invoke([
                "--fixture", str(path), "--config", str(FIXTURES / "demo-config.json"),
                "--simulate-orders", "--runtime-dir", str(self.runtime)
            ])
        self.assertEqual(code, 2)
        self.assertEqual(records[-1]["state"], "input_or_adapter_blocked")
        attempt.assert_not_called()
        self.assertFalse((self.runtime / "orders").exists())

    def test_live_observation_only_classifies_and_output_omits_private_fields(self):
        config = json.loads((FIXTURES / "demo-config.json").read_text(encoding="utf-8"))
        data = json.loads((FIXTURES / "demo-observations.json").read_text(encoding="utf-8"))
        snapshot = copy.deepcopy(next(row for row in data["observations"]
                                      if row["event_key"] == "example-alpha" and row["page"]["kind"] == "confirmation"))
        now = dt.datetime.now(dt.timezone.utc)
        config["profile"].update(verified=True, version="fixture-app-1", evidence_ref="fixture-mapping")
        config["poll"].update(service_frequency_verified=True, service_minimum_seconds=5)
        event = config["events"][0]
        event["target"]["starts_at"] = (now + dt.timedelta(days=1)).isoformat()
        event["window"] = {"start": (now - dt.timedelta(hours=1)).isoformat(),
                           "end": (now + dt.timedelta(hours=1)).isoformat()}
        snapshot.update(source="cua_repl", captured_at=now.isoformat(), heartbeat_at=now.isoformat())
        snapshot["device"]["serial"] = "fixture-live-vivo"
        snapshot["page"]["target"] = copy.deepcopy(event["target"])
        config_path = self.write_json("live-classification-config.json", config)
        snapshot_path = self.write_json("live-classification-observation.json", snapshot)
        with mock.patch.dict("os.environ", {"DAMAI_DEVICE_SERIAL": "fixture-live-vivo"}), \
                mock.patch("damai.cli.OrderGuard.attempt", side_effect=AssertionError("observation never submits")) as attempt:
            code, records, output, _ = self.invoke([
                "--observation", str(snapshot_path), "--config", str(config_path),
                "--runtime-dir", str(self.runtime)
            ])
        self.assertEqual(code, 0)
        self.assertEqual(records[0]["state"], "confirmation_ready")
        self.assertTrue(records[0]["simulated"])
        attempt.assert_not_called()
        self.assertFalse(self.runtime.exists())
        for private_field in ("fixture-live-vivo", "fixture-person-a", "fixture-person-b",
                              "fixture-item-alpha", "fixture-mapping", "spectator_refs", "intent", "order_ref"):
            self.assertNotIn(private_field, output)

    def test_bad_heartbeat_disconnection_or_lock_stops_before_any_adapter_call(self):
        for condition, expected in (("heartbeat_future", "heartbeat_stale"),
                                    ("heartbeat_expired", "heartbeat_stale"),
                                    ("disconnected", "device_offline"),
                                    ("locked", "screen_blocked")):
            with self.subTest(condition=condition):
                data = json.loads((FIXTURES / "demo-observations.json").read_text(encoding="utf-8"))
                first = data["observations"][0]
                captured = dt.datetime.fromisoformat(first["captured_at"])
                if condition.startswith("heartbeat_"):
                    offset = 1 if condition == "heartbeat_future" else -31
                    first["heartbeat_at"] = (captured + dt.timedelta(seconds=offset)).isoformat()
                elif condition == "disconnected":
                    first["device"]["state"] = "offline"
                else:
                    first["device"]["locked"] = True
                path = self.write_json(condition + ".json", data)
                runtime = self.path / ("runtime-" + condition)
                with mock.patch.object(FixtureOrderAdapter, "submit_pending", side_effect=AssertionError("unsafe executor cannot submit")) as submit:
                    code, records, _, _ = self.invoke([
                        "--fixture", str(path), "--config", str(FIXTURES / "demo-config.json"),
                        "--simulate-orders", "--runtime-dir", str(runtime)
                    ])
                self.assertEqual(code, 0)
                states = self.decisions(records)
                self.assertEqual(len(states), 1)
                self.assertEqual(states[0]["state"], expected)
                self.assertEqual(states[0]["scope"], "session")
                self.assertTrue(states[0]["stop"])
                self.assertIsNone(states[0]["proposed_action"])
                submit.assert_not_called()
                self.assertFalse((runtime / "orders").exists())

    def test_uncertain_or_captcha_order_blocks_second_event_and_retains_attempt_lock(self):
        original = FixtureOrderAdapter.submit_pending
        for outcome, expected in (("uncertain", "order_uncertain"), ("captcha", "captcha")):
            with self.subTest(outcome=outcome):
                data = json.loads((FIXTURES / "demo-observations.json").read_text(encoding="utf-8"))
                data["order_results"]["example-alpha"] = {"status": outcome}
                path = self.write_json("order-" + outcome + ".json", data)
                runtime = self.path / ("runtime-order-" + outcome)
                args = ["--fixture", str(path), "--config", str(FIXTURES / "demo-config.json"),
                        "--simulate-orders", "--runtime-dir", str(runtime)]
                with mock.patch.object(FixtureOrderAdapter, "submit_pending", autospec=True,
                                       side_effect=lambda adapter, intent: original(adapter, intent)) as submit:
                    code, records, _, _ = self.invoke(args)
                self.assertEqual(code, 0)
                self.assertEqual(submit.call_count, 1)
                states = self.decisions(records)
                self.assertEqual(states[-1]["state"], expected)
                self.assertEqual(states[-1]["event_key"], "example-alpha")
                self.assertEqual(states[-1]["scope"], "session")
                self.assertTrue(states[-1]["stop"])
                self.assertFalse(any(row["state"] in ("pending_payment", "confirmation_ready")
                                     and row["event_key"] == "example-beta" for row in states))
                before = {journal.name: journal.read_bytes() for journal in (runtime / "orders").glob("*.json")}
                self.assertEqual(len(before), 2)
                self.assertTrue(all(json.loads(content)["state"] == outcome for content in before.values()))
                with mock.patch.object(FixtureOrderAdapter, "submit_pending", side_effect=AssertionError("retry after uncertainty or captcha forbidden")) as retry:
                    second_code, second_records, _, _ = self.invoke(args)
                self.assertEqual(second_code, 0)
                retry.assert_not_called()
                terminal = self.decisions(second_records)[-1]
                self.assertEqual(terminal["state"], "order_uncertain")
                self.assertEqual(terminal["scope"], "session")
                self.assertTrue(terminal["stop"])
                after = {journal.name: journal.read_bytes() for journal in (runtime / "orders").glob("*.json")}
                self.assertEqual(after, before)

    def test_non_mapping_device_input_is_classified_without_crash_or_io(self):
        for index, device in enumerate((None, [], "invalid-device", 7)):
            with self.subTest(device=device):
                data = json.loads((FIXTURES / "demo-observations.json").read_text(encoding="utf-8"))
                data["observations"][0]["device"] = device
                path = self.write_json("invalid-device-{}.json".format(index), data)
                runtime = self.path / ("runtime-invalid-device-{}".format(index))
                with mock.patch.object(FixtureOrderAdapter, "submit_pending", side_effect=AssertionError("malformed device cannot submit")) as submit:
                    code, records, _, _ = self.invoke([
                        "--fixture", str(path), "--config", str(FIXTURES / "demo-config.json"),
                        "--simulate-orders", "--runtime-dir", str(runtime)
                    ])
                self.assertEqual(code, 0)
                states = self.decisions(records)
                self.assertEqual(len(states), 1)
                self.assertEqual(states[0]["state"], "invalid_observation")
                self.assertEqual(states[0]["scope"], "session")
                self.assertTrue(states[0]["stop"])
                self.assertIsNone(states[0]["proposed_action"])
                submit.assert_not_called()
                self.assertFalse((runtime / "orders").exists())


if __name__ == "__main__":
    unittest.main()
