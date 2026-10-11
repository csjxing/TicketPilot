"""Regressions found by independent review; fixture evidence only."""
import copy
import datetime as dt
import unittest

from damai.config import ConfigError, validate_config
from damai.engine import FleetMonitor
from test_engine import BASE, observation, sample_config, selected_page


def pending_result():
    return {"event_key": "example-alpha", "state": "pending_payment",
            "scope": "event", "simulated": True, "order_ref": "fixture-order-a",
            "amount_minor": 132000,
            "deadline": (BASE + dt.timedelta(minutes=10)).isoformat()}


class SafetyRegressionTests(unittest.TestCase):
    def test_other_package_is_rejected_even_when_profile_and_page_agree(self):
        config = sample_config()
        config["profile"]["package"] = "example.other"
        with self.assertRaises(ConfigError):
            validate_config(config)

    def test_external_config_mutation_does_not_change_running_confirmation(self):
        config = sample_config(confirmed=True, enabled=False)
        monitor = FleetMonitor(config)
        config["orders_enabled"] = True
        self.assertEqual(monitor.step(selected_page(config), BASE)["state"], "orders_disabled")

    def test_pending_spectator_or_selection_mismatch_pauses_other_event(self):
        for field, value in (("spectator_refs", ["fixture-other", "fixture-other"]),
                             ("quantity", 1), ("adjacent", False)):
            with self.subTest(field=field):
                config = sample_config(confirmed=True)
                monitor = FleetMonitor(config)
                snapshot = selected_page(config, kind="pending_payment")
                snapshot["page"]["pending"] = {"order_ref": "fixture-order-a",
                    "amount_minor": 132000, "deadline": pending_result()["deadline"]}
                snapshot["page"]["selection"][field] = value
                result = monitor.step(snapshot, BASE)
                self.assertEqual((result["state"], result["scope"]), ("order_uncertain", "session"))
                self.assertEqual(monitor.step(observation(config, "example-beta", 1, 2), BASE + dt.timedelta(seconds=1)), result)

    def test_unconfirmed_pending_page_cannot_be_recognized_as_success(self):
        config = sample_config()
        snapshot = selected_page(config, kind="pending_payment")
        snapshot["page"]["pending"] = {"order_ref": "fixture-order-a", "amount_minor": 132000,
                                       "deadline": pending_result()["deadline"]}
        result = FleetMonitor(config).step(snapshot, BASE)
        self.assertEqual((result["state"], result["scope"]), ("order_uncertain", "session"))

    def test_pending_result_requires_existing_confirmation(self):
        result = FleetMonitor(sample_config(confirmed=True, enabled=True)).record_order_result("example-alpha", pending_result(), BASE)
        self.assertEqual((result["state"], result["scope"]), ("order_uncertain", "session"))

    def test_pending_result_must_match_event_amount_reference_and_deadline(self):
        edits = (("event_key", "example-beta"), ("scope", "session"),
                 ("amount_minor", 131000), ("order_ref", ""),
                 ("deadline", BASE.isoformat()), ("deadline", "2030-01-03T12:00:00+00:00"))
        for field, value in edits:
            with self.subTest(field=field, value=value):
                config = sample_config(confirmed=True, enabled=True)
                monitor = FleetMonitor(config)
                self.assertEqual(monitor.step(selected_page(config), BASE)["state"], "confirmation_ready")
                self.assertIsNone(monitor.reserve_submission("example-alpha", BASE))
                outcome = pending_result(); outcome[field] = value
                result = monitor.record_order_result("example-alpha", outcome, BASE)
                self.assertEqual((result["state"], result["scope"]), ("order_uncertain", "session"))

    def test_pending_result_with_expired_confirmation_stops(self):
        config = sample_config(confirmed=True, enabled=True)
        monitor = FleetMonitor(config)
        monitor.step(selected_page(config), BASE)
        self.assertIsNone(monitor.reserve_submission("example-alpha", BASE))
        result = monitor.record_order_result("example-alpha", pending_result(), BASE + dt.timedelta(seconds=21))
        self.assertEqual(result["state"], "order_uncertain")

    def test_returned_intent_and_original_snapshot_cannot_change_cached_confirmation(self):
        config = sample_config(confirmed=True, enabled=True)
        monitor = FleetMonitor(config)
        snapshot = selected_page(config)
        returned = monitor.step(snapshot, BASE)
        returned["intent"]["total_minor"] = 131000
        snapshot["page"]["selection"]["spectator_refs"][0] = "fixture-other"
        self.assertEqual(monitor.runtime["example-alpha"]["final"]["intent"]["total_minor"], 132000)
        self.assertEqual(monitor.runtime["example-alpha"]["final"]["intent"]["spectator_refs"], config["events"][0]["spectator_refs"])
        self.assertIsNone(monitor.reserve_submission("example-alpha", BASE))
        outcome = pending_result(); outcome["amount_minor"] = 131000
        self.assertEqual(monitor.record_order_result("example-alpha", outcome, BASE)["state"], "order_uncertain")

    def test_second_event_waits_for_shared_slot_then_requires_fresh_confirmation(self):
        config = sample_config(confirmed=True, enabled=True)
        monitor = FleetMonitor(config)
        monitor.step(selected_page(config), BASE)
        self.assertIsNone(monitor.reserve_submission("example-alpha", BASE))
        now = BASE + dt.timedelta(seconds=1)
        monitor.step(selected_page(config, "example-beta", seconds=1, sequence=2), now)
        result = monitor.reserve_submission("example-beta", now)
        self.assertEqual((result["state"], result["next_delay_seconds"], result["proposed_action"]), ("shared_rate_limited", 4, None))
        self.assertIsNone(monitor.runtime["example-beta"]["final"])
        now = BASE + dt.timedelta(seconds=5)
        self.assertEqual(monitor.step(selected_page(config, "example-beta", seconds=5, sequence=3), now)["state"], "confirmation_ready")
        self.assertIsNone(monitor.reserve_submission("example-beta", now))

    def test_captcha_or_rejection_from_submit_stops_both_events(self):
        for state in ("captcha", "rejected"):
            config = sample_config(confirmed=True, enabled=True)
            monitor = FleetMonitor(config)
            result = monitor.record_order_result("example-alpha", {"state": state}, BASE)
            self.assertEqual(result["scope"], "session")
            self.assertEqual(monitor.step(observation(config, "example-beta"), BASE), result)


if __name__ == "__main__":
    unittest.main()
