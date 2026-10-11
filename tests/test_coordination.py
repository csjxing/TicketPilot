"""Local coordination contract tests: fixtures only, no executor or service."""
import copy
import datetime as dt
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from damai.config import ConfigError, load_config
from damai.coordination import LocalCoordinator


BASE = dt.datetime(2030, 1, 1, 9, tzinfo=dt.timezone.utc)


def config_fixture():
    return copy.deepcopy(load_config(Path(__file__).parent / "fixtures" / "safe-config.json"))


def heartbeat(coordinator, seconds=0, sequence=1):
    return {"schema_version": 1, "mode": "dry_run", "source": "fixture",
            "config_revision": coordinator.config_revision, "executor_key": "fixture-mac",
            "sequence": sequence, "sent_at": (BASE + dt.timedelta(seconds=seconds)).isoformat(),
            "executor_state": "connected",
            "device": {"identity_verified": True, "connected": True, "locked": False, "screen_on": True},
            "capabilities": {"ui_observation": "available"}}


class LocalCoordinatorTests(unittest.TestCase):
    def setUp(self):
        self.config = config_fixture()
        self.coordinator = LocalCoordinator(self.config, "fixture-mac", BASE)

    def healthy(self, coordinator=None, seconds=0, sequence=1):
        coordinator = coordinator or self.coordinator
        packet = heartbeat(coordinator, seconds, sequence)
        outcome = coordinator.accept_heartbeat(packet, BASE + dt.timedelta(seconds=seconds))
        self.assertFalse(outcome["paused"])
        return packet

    def assert_paused(self, result, reason=None):
        self.assertTrue(result["paused"])
        self.assertTrue(result["stop"])
        self.assertEqual(result["jobs"], [])
        self.assertEqual(result["allowed_event_keys"], [])
        if reason:
            self.assertEqual(result["reason"], reason)

    def test_config_is_validated_and_non_json_values_are_rejected(self):
        for mutation in ("missing", "live", "nonfinite", "nonjson"):
            config = copy.deepcopy(self.config)
            if mutation == "missing":
                del config["events"]
            elif mutation == "live":
                config["mode"] = "live"
            else:
                config["profile"]["version"] = float("nan") if mutation == "nonfinite" else object()
            with self.subTest(mutation=mutation), self.assertRaises(ConfigError):
                LocalCoordinator(config, "fixture-mac", BASE)
        for executor in ("", "name with spaces", [], 1):
            with self.assertRaises(ConfigError):
                LocalCoordinator(self.config, executor, BASE)
        with self.assertRaises(ConfigError):
            LocalCoordinator(self.config, "fixture-mac", BASE.replace(tzinfo=None))

    def test_revision_is_canonical_and_value_changes_change_revision(self):
        reordered = dict(reversed(list(self.config.items())))
        self.assertEqual(LocalCoordinator(reordered, "fixture-mac", BASE).config_revision,
                         self.coordinator.config_revision)
        changed = copy.deepcopy(self.config)
        changed["events"][0]["budget_minor"] += 1
        self.assertNotEqual(LocalCoordinator(changed, "fixture-mac", BASE).config_revision,
                            self.coordinator.config_revision)
        self.assertEqual(len(self.coordinator.config_revision), 64)

    def test_config_and_envelope_copies_cannot_change_the_fixed_session(self):
        original = self.coordinator.execution_config()
        self.config["events"][0]["target"]["name"] = "changed externally"
        private_copy = self.coordinator.execution_config()
        private_copy["events"][0]["tiers"].append(1)
        envelope = self.coordinator.envelope()
        envelope["event_keys"].clear()
        self.assertEqual(self.coordinator.execution_config(), original)
        self.assertEqual(len(self.coordinator.envelope()["event_keys"]), 2)
        for attribute in ("config_revision", "executor_key", "started_at"):
            with self.assertRaises(AttributeError):
                setattr(self.coordinator, attribute, "changed")

    def test_public_outputs_omit_private_configuration_and_report_fields(self):
        config = copy.deepcopy(self.config)
        config["events"][0]["spectator_refs"] = ["secret-person-a", "secret-person-b"]
        config["events"][0]["target"]["name"] = "secret-target-name"
        coordinator = LocalCoordinator(config, "fixture-mac", BASE)
        self.healthy(coordinator)
        plan = coordinator.plan(BASE)
        result = coordinator.record_event_result("example-alpha", {
            "event_key": "example-alpha", "state": "sold_out", "stop": False,
            "scope": "wait", "next_delay_seconds": 5,
            "intent": {"spectator_refs": ["secret-person-a"], "serial": "secret-serial"}}, BASE)
        text = json.dumps([coordinator.envelope(), plan, result])
        self.assertNotIn("secret", text)
        self.assertNotIn("spectator_refs", text)
        self.assertNotIn("serial", text)

    def test_missing_executor_health_fails_closed(self):
        self.assert_paused(self.coordinator.plan(BASE), "heartbeat_missing")

    def test_strict_heartbeat_schema_executor_revision_and_source(self):
        for mutation in ("missing", "extra", "executor", "revision", "source", "boolean_sequence", "boolean_schema", "naive_time", "bad_state", "extra_device", "string_boolean", "bad_capability"):
            coordinator = LocalCoordinator(self.config, "fixture-mac", BASE)
            packet = heartbeat(coordinator)
            if mutation == "missing":
                del packet["sent_at"]
            elif mutation == "extra":
                packet["secret"] = "never echo"
            elif mutation == "executor":
                packet["executor_key"] = "another-mac"
            elif mutation == "revision":
                packet["config_revision"] = "other-config"
            elif mutation == "source":
                packet["source"] = "cua_repl"
            elif mutation == "boolean_sequence":
                packet["sequence"] = True
            elif mutation == "boolean_schema":
                packet["schema_version"] = True
            elif mutation == "naive_time":
                packet["sent_at"] = "2030-01-01T09:00:00"
            elif mutation == "bad_state":
                packet["executor_state"] = "maybe"
            elif mutation == "extra_device":
                packet["device"]["serial"] = "secret-device"
            elif mutation == "string_boolean":
                packet["device"]["identity_verified"] = "true"
            else:
                packet["capabilities"]["ui_observation"] = True
            with self.subTest(mutation=mutation):
                self.assert_paused(coordinator.accept_heartbeat(packet, BASE), "invalid_heartbeat")
                self.assert_paused(coordinator.plan(BASE))

    def test_connection_screen_identity_and_capability_each_gate_jobs(self):
        cases = (("executor", "executor_state", "disconnected", "executor_disconnected"),
                 ("executor", "executor_state", "unknown", "executor_unknown"),
                 ("device", "identity_verified", False, "wrong_device"),
                 ("device", "connected", False, "device_disconnected"),
                 ("device", "locked", True, "screen_blocked"),
                 ("device", "screen_on", False, "screen_blocked"),
                 ("device", "locked", None, "device_unknown"),
                 ("capabilities", "ui_observation", "unavailable", "capability_unavailable"),
                 ("capabilities", "ui_observation", "unknown", "capability_unavailable"))
        for location, field, value, reason in cases:
            coordinator = LocalCoordinator(self.config, "fixture-mac", BASE)
            packet = heartbeat(coordinator)
            (packet if location == "executor" else packet[location])[field] = value
            with self.subTest(field=field, value=value):
                self.assert_paused(coordinator.accept_heartbeat(packet, BASE), reason)
                self.assert_paused(coordinator.plan(BASE), reason)

    def test_heartbeat_ttl_boundary_and_future_timestamp(self):
        ttl = self.config["poll"]["heartbeat_timeout_seconds"]
        self.healthy()
        self.assertFalse(self.coordinator.plan(BASE + dt.timedelta(seconds=ttl))["paused"])
        self.assert_paused(self.coordinator.plan(BASE + dt.timedelta(seconds=ttl, microseconds=1)), "heartbeat_expired")
        coordinator = LocalCoordinator(self.config, "fixture-mac", BASE)
        self.assert_paused(coordinator.accept_heartbeat(heartbeat(coordinator, 1), BASE), "invalid_heartbeat")

    def test_same_heartbeat_is_idempotent_but_regression_and_mutation_pause(self):
        packet = self.healthy(sequence=2)
        self.assertFalse(self.coordinator.accept_heartbeat(copy.deepcopy(packet), BASE)["paused"])
        lower = copy.deepcopy(packet)
        lower["sequence"] = 1
        self.assert_paused(self.coordinator.accept_heartbeat(lower, BASE))
        # Rejecting the lower sequence must not erase the accepted high-water mark.
        self.assert_paused(self.coordinator.accept_heartbeat(lower, BASE))
        self.assertFalse(self.coordinator.accept_heartbeat(packet, BASE)["paused"])
        changed = copy.deepcopy(packet)
        changed["device"]["locked"] = True
        self.assert_paused(self.coordinator.accept_heartbeat(changed, BASE))
        later = heartbeat(self.coordinator, 1, 3)
        self.assertFalse(self.coordinator.accept_heartbeat(later, BASE + dt.timedelta(seconds=1))["paused"])

    def test_new_sequence_must_advance_the_heartbeat_clock(self):
        self.healthy()
        packet = heartbeat(self.coordinator, sequence=2)
        self.assert_paused(self.coordinator.accept_heartbeat(packet, BASE), "invalid_heartbeat")

    def test_heartbeat_input_mutation_cannot_change_cached_health(self):
        packet = self.healthy()
        packet["device"]["locked"] = True
        self.assertFalse(self.coordinator.plan(BASE)["paused"])

    def test_unknown_clock_and_clock_regression_pause_even_while_unhealthy(self):
        self.assert_paused(self.coordinator.plan(BASE.replace(tzinfo=None)), "invalid_clock")
        packet = heartbeat(self.coordinator)
        packet["device"]["locked"] = True
        self.assert_paused(self.coordinator.accept_heartbeat(packet, BASE), "screen_blocked")
        self.assert_paused(self.coordinator.plan(BASE - dt.timedelta(microseconds=1)), "clock_regressed")

    def test_aware_non_utc_clock_is_supported(self):
        self.healthy()
        local_now = BASE.astimezone(dt.timezone(dt.timedelta(hours=8)))
        self.assertEqual(len(self.coordinator.plan(local_now)["jobs"]), 2)

    def test_jobs_are_reserved_once_and_never_authorize_submission(self):
        self.config["orders_enabled"] = True
        coordinator = LocalCoordinator(self.config, "fixture-mac", BASE)
        self.healthy(coordinator)
        plan = coordinator.plan(BASE)
        self.assertEqual(set(plan["allowed_event_keys"]), {"example-alpha", "example-beta"})
        self.assertEqual(set(plan["due_event_keys"]), {"example-alpha", "example-beta"})
        self.assertEqual(len(plan["jobs"]), 2)
        self.assertEqual(len({job["job_key"] for job in plan["jobs"]}), 2)
        for job in plan["jobs"]:
            self.assertEqual(job["action"], "dry_run_check")
            self.assertIs(job["submit_enabled"], False)
            self.assertIs(job["service_request"], False)
            self.assertIs(job["parameters_verified"], False)
        plan["jobs"][0]["action"] = "submit"
        self.assertEqual(coordinator.plan(BASE)["jobs"], [])
        self.assertEqual(coordinator.plan(BASE + dt.timedelta(seconds=4))["jobs"], [])
        self.assertEqual(len(coordinator.plan(BASE + dt.timedelta(seconds=5))["jobs"]), 2)

    def test_subset_issuance_preserves_other_event_due_state(self):
        self.healthy()
        first = self.coordinator.plan(BASE, event_keys=["example-alpha"])
        self.assertEqual([job["event_key"] for job in first["jobs"]], ["example-alpha"])
        self.assertEqual(set(first["due_event_keys"]), {"example-alpha", "example-beta"})
        second = self.coordinator.plan(BASE, event_keys=["example-beta"])
        self.assertEqual([job["event_key"] for job in second["jobs"]], ["example-beta"])
        for keys in (["unknown"], ["example-alpha", "example-alpha"], "example-alpha", [True]):
            self.assert_paused(self.coordinator.plan(BASE, event_keys=keys), "unknown_event")

    def test_window_start_inclusive_end_exclusive_and_independent(self):
        config = copy.deepcopy(self.config)
        config["events"][0]["window"] = {"start": (BASE + dt.timedelta(seconds=5)).isoformat(),
                                          "end": (BASE + dt.timedelta(seconds=10)).isoformat()}
        coordinator = LocalCoordinator(config, "fixture-mac", BASE)
        self.healthy(coordinator)
        before = coordinator.plan(BASE + dt.timedelta(seconds=4, microseconds=999999))
        self.assertNotIn("example-alpha", before["allowed_event_keys"])
        at_start = coordinator.plan(BASE + dt.timedelta(seconds=5), event_keys=["example-alpha"])
        self.assertEqual([job["event_key"] for job in at_start["jobs"]], ["example-alpha"])
        at_end = coordinator.plan(BASE + dt.timedelta(seconds=10))
        self.assertNotIn("example-alpha", at_end["allowed_event_keys"])
        self.assertIn("example-beta", at_end["allowed_event_keys"])

    def test_event_report_adjusts_due_and_event_stop_cannot_resume(self):
        self.healthy()
        self.coordinator.plan(BASE, ["example-alpha"])
        self.coordinator.record_event_result("example-alpha", {
            "state": "awaiting_page", "stop": False, "scope": "wait", "next_delay_seconds": 2}, BASE)
        self.assertEqual(self.coordinator.plan(BASE + dt.timedelta(seconds=1), ["example-alpha"])["jobs"], [])
        self.assertEqual(len(self.coordinator.plan(BASE + dt.timedelta(seconds=2), ["example-alpha"])["jobs"]), 1)
        self.coordinator.record_event_result("example-alpha", {
            "state": "pending_payment", "stop": True, "scope": "event"}, BASE + dt.timedelta(seconds=2))
        self.coordinator.record_event_result("example-alpha", {
            "state": "sold_out", "stop": False, "scope": "wait", "next_delay_seconds": 1}, BASE + dt.timedelta(seconds=2))
        later = self.coordinator.plan(BASE + dt.timedelta(seconds=3))
        self.assertNotIn("example-alpha", later["allowed_event_keys"])
        self.assertEqual([job["event_key"] for job in later["jobs"]], ["example-beta"])

    def test_session_report_stop_and_bad_reports_are_sticky(self):
        reports = [("example-alpha", {"state": "captcha", "stop": True, "scope": "session"}, "executor_report_stopped"),
                   ("unknown", {"state": "sold_out", "stop": False, "scope": "wait"}, "invalid_event_result"),
                   ("example-alpha", {"state": "sold_out", "stop": False, "scope": "event"}, "invalid_event_result"),
                   ("example-alpha", {"state": "sold_out", "stop": False, "scope": "wait", "next_delay_seconds": True}, "invalid_event_result"),
                   ("example-alpha", {"state": "sold_out", "stop": False, "scope": "wait", "event_key": "example-beta"}, "invalid_event_result")]
        for key, result, reason in reports:
            coordinator = LocalCoordinator(self.config, "fixture-mac", BASE)
            self.healthy(coordinator)
            with self.subTest(reason=reason):
                self.assert_paused(coordinator.record_event_result(key, result, BASE), reason)
                self.assert_paused(coordinator.plan(BASE), reason)

    def test_poll_limits_finish_each_event_and_all_finished_session(self):
        config = copy.deepcopy(self.config)
        config["poll"]["max_polls"] = 1
        coordinator = LocalCoordinator(config, "fixture-mac", BASE)
        self.healthy(coordinator)
        self.assertEqual(len(coordinator.plan(BASE)["jobs"]), 2)
        result = coordinator.plan(BASE + dt.timedelta(seconds=1))
        self.assertEqual(result["state"], "finished")
        self.assertTrue(result["stop"])
        self.assertEqual(result["jobs"], [])

    def test_session_limit_is_fixed_across_health_pauses(self):
        config = copy.deepcopy(self.config)
        config["poll"]["max_session_seconds"] = 5
        coordinator = LocalCoordinator(config, "fixture-mac", BASE)
        bad = heartbeat(coordinator)
        bad["executor_state"] = "disconnected"
        self.assert_paused(coordinator.accept_heartbeat(bad, BASE))
        self.healthy(coordinator, seconds=4, sequence=2)
        self.assertFalse(coordinator.plan(BASE + dt.timedelta(seconds=4, microseconds=999999))["paused"])
        self.assert_paused(coordinator.plan(BASE + dt.timedelta(seconds=5)), "session_limit")
        self.assert_paused(coordinator.accept_heartbeat(heartbeat(coordinator, 6, 3), BASE + dt.timedelta(seconds=6)), "session_limit")

    def test_health_can_resume_without_changing_configuration_or_session_start(self):
        bad = heartbeat(self.coordinator)
        bad["capabilities"]["ui_observation"] = "unavailable"
        self.assert_paused(self.coordinator.accept_heartbeat(bad, BASE))
        revision = self.coordinator.config_revision
        self.healthy(seconds=1, sequence=2)
        self.assertEqual(len(self.coordinator.plan(BASE + dt.timedelta(seconds=1))["jobs"]), 2)
        self.assertEqual(self.coordinator.config_revision, revision)
        self.assertEqual(self.coordinator.started_at, BASE)

    def test_no_network_subprocess_file_or_sleep_operations_exist(self):
        with patch("socket.socket", side_effect=AssertionError("network forbidden")), \
             patch("subprocess.run", side_effect=AssertionError("process forbidden")), \
             patch("builtins.open", side_effect=AssertionError("file I/O forbidden")), \
             patch("time.sleep", side_effect=AssertionError("sleep forbidden")):
            coordinator = LocalCoordinator(self.config, "fixture-mac", BASE)
            coordinator.accept_heartbeat(heartbeat(coordinator), BASE)
            self.assertEqual(len(coordinator.plan(BASE)["jobs"]), 2)


if __name__ == "__main__":
    unittest.main()
