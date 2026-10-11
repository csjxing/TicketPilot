"""Shared-device submissions reserve unpaid capacity before any fixture call."""

import datetime as dt
import unittest

from damai.engine import FleetMonitor
from test_engine import BASE, sample_config, selected_page


def configured_monitor(capacity=1):
    config = sample_config(confirmed=True, enabled=True)
    config["constraints"]["max_unpaid_orders"] = capacity
    return config, FleetMonitor(config)


def pending_result(event_key, confirmation):
    return {
        "event_key": event_key,
        "state": "pending_payment",
        "scope": "event",
        "simulated": True,
        "order_ref": "fixture-order-" + event_key,
        "amount_minor": confirmation["intent"]["total_minor"],
        "deadline": (BASE + dt.timedelta(minutes=10)).isoformat(),
    }


class InflightCapacityTests(unittest.TestCase):
    def ready(self, monitor, config, key="example-alpha", seconds=0, sequence=1):
        result = monitor.step(selected_page(config, key, seconds=seconds, sequence=sequence),
                              BASE + dt.timedelta(seconds=seconds))
        self.assertEqual(result["state"], "confirmation_ready")
        return result

    def assert_blocked(self, result):
        self.assertIsNotNone(result)
        self.assertTrue(result["stop"])
        self.assertIsNone(result["proposed_action"])

    def test_two_cached_confirmations_cannot_exceed_unpaid_capacity(self):
        config, monitor = configured_monitor()
        first = self.ready(monitor, config)
        self.ready(monitor, config, "example-beta", seconds=1, sequence=2)
        now = BASE + dt.timedelta(seconds=1)
        self.assertIsNone(monitor.reserve_submission("example-alpha", now))
        outcome = monitor.record_order_result("example-alpha", pending_result("example-alpha", first), now)
        self.assertEqual(outcome["state"], "pending_payment")
        blocked = monitor.reserve_submission("example-beta", BASE + dt.timedelta(seconds=6))
        self.assert_blocked(blocked)
        self.assertEqual(blocked["state"], "unpaid_constraint")
        self.assertEqual(monitor.unpaid_events, {"example-alpha"})
        self.assertEqual(monitor.submitting_events, set())

    def test_cached_second_confirmation_cannot_grant_while_first_is_inflight(self):
        config, monitor = configured_monitor()
        self.ready(monitor, config)
        self.ready(monitor, config, "example-beta", seconds=1, sequence=2)
        self.assertIsNone(monitor.reserve_submission("example-alpha", BASE + dt.timedelta(seconds=1)))
        self.assertEqual(monitor.submitting_events, {"example-alpha"})
        blocked = monitor.reserve_submission("example-beta", BASE + dt.timedelta(seconds=6))
        self.assert_blocked(blocked)
        self.assertEqual(blocked["state"], "unpaid_constraint")
        self.assertEqual(monitor.submitting_events, {"example-alpha"})
        self.assertEqual(monitor.unpaid_events, set())

    def test_new_second_confirmation_counts_first_inflight(self):
        config, monitor = configured_monitor()
        self.ready(monitor, config)
        self.assertIsNone(monitor.reserve_submission("example-alpha", BASE))
        result = monitor.step(selected_page(config, "example-beta", seconds=1, sequence=2),
                              BASE + dt.timedelta(seconds=1))
        self.assert_blocked(result)
        self.assertEqual(result["state"], "unpaid_constraint")
        self.assertEqual(monitor.submitting_events, {"example-alpha"})

    def test_pending_result_requires_a_submission_grant(self):
        config, monitor = configured_monitor()
        first = self.ready(monitor, config)
        result = monitor.record_order_result("example-alpha", pending_result("example-alpha", first), BASE)
        self.assert_blocked(result)
        self.assertEqual((result["state"], result["scope"]), ("order_uncertain", "session"))
        self.assertEqual(monitor.unpaid_events, set())
        self.assertEqual(monitor.submitting_events, set())

    def test_same_event_cannot_obtain_another_grant_after_request_floor(self):
        config, monitor = configured_monitor()
        self.ready(monitor, config)
        self.assertIsNone(monitor.reserve_submission("example-alpha", BASE))
        result = monitor.reserve_submission("example-alpha", BASE + dt.timedelta(seconds=5))
        self.assert_blocked(result)
        self.assertEqual((result["state"], result["scope"]), ("order_uncertain", "session"))
        self.assertEqual(monitor.submitting_events, {"example-alpha"})

    def test_success_moves_inflight_to_unpaid_without_double_counting(self):
        config, monitor = configured_monitor()
        first = self.ready(monitor, config)
        self.assertIsNone(monitor.reserve_submission("example-alpha", BASE))
        result = monitor.record_order_result("example-alpha", pending_result("example-alpha", first), BASE)
        self.assertEqual(result["state"], "pending_payment")
        self.assertEqual(monitor.submitting_events, set())
        self.assertEqual(monitor.unpaid_events, {"example-alpha"})

    def test_uncertain_submission_retains_its_capacity_and_stops_session(self):
        config, monitor = configured_monitor()
        self.ready(monitor, config)
        self.assertIsNone(monitor.reserve_submission("example-alpha", BASE))
        result = monitor.record_order_result("example-alpha", {"state": "uncertain"}, BASE)
        self.assert_blocked(result)
        self.assertEqual((result["state"], result["scope"]), ("order_uncertain", "session"))
        self.assertEqual(monitor.submitting_events, {"example-alpha"})

    def test_capacity_two_permits_two_distinct_inflight_events(self):
        config, monitor = configured_monitor(capacity=2)
        first = self.ready(monitor, config)
        second = self.ready(monitor, config, "example-beta", seconds=1, sequence=2)
        self.assertIsNone(monitor.reserve_submission("example-alpha", BASE + dt.timedelta(seconds=1)))
        self.assertIsNone(monitor.reserve_submission("example-beta", BASE + dt.timedelta(seconds=6)))
        self.assertEqual(monitor.submitting_events, {"example-alpha", "example-beta"})
        result = monitor.record_order_result("example-alpha", pending_result("example-alpha", first),
                                             BASE + dt.timedelta(seconds=6))
        self.assertEqual(result["state"], "pending_payment")
        result = monitor.record_order_result("example-beta", pending_result("example-beta", second),
                                             BASE + dt.timedelta(seconds=6))
        self.assertEqual(result["state"], "pending_payment")
        self.assertEqual(monitor.submitting_events, set())
        self.assertEqual(monitor.unpaid_events, {"example-alpha", "example-beta"})


if __name__ == "__main__":
    unittest.main()
