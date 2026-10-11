"""Behavior tests: no real devices, targets, accounts, network, or UI calls."""
import copy
import datetime as dt
from pathlib import Path
import unittest

from damai.config import ConfigError, load_config
from damai.engine import FleetMonitor


FIXTURES = Path(__file__).parent / "fixtures"
BASE = dt.datetime(2030, 1, 1, 9, tzinfo=dt.timezone.utc)


def sample_config(confirmed=False, enabled=False):
    config = copy.deepcopy(load_config(FIXTURES / "safe-config.json"))
    config["orders_enabled"] = enabled
    if confirmed:
        config["constraints"] = {
            "verified": True, "max_unpaid_orders": 2, "allow_split_orders": False
        }
        for event in config["events"]:
            event["verified"] = True
            event["spectator_refs"] = ["fixture-person-a", "fixture-person-b"]
    return config


def observation(config, key="example-alpha", seconds=0, sequence=1):
    event = next(event for event in config["events"] if event["key"] == key)
    captured = (BASE + dt.timedelta(seconds=seconds)).isoformat()
    return {
        "schema_version": 1,
        "source": "fixture",
        "captured_at": captured,
        "heartbeat_at": captured,
        "sequence": sequence,
        "event_key": key,
        "evidence_ref": "fixture-screen-{}".format(sequence),
        "profile_id": "offline-v1",
        "device": {
            "serial": "fixture-vivo", "manufacturer": "vivo", "model": "V2547A",
            "transport": "usb", "state": "device", "screen_on": True, "locked": False
        },
        "app": {"package": "cn.damai", "version": "fixture-app-1", "login": "logged_in"},
        "page": {
            "kind": "tickets", "ready": True, "target": copy.deepcopy(event["target"]),
            "tiers": [{"price_minor": tier, "stock": "sold_out", "enabled": False,
                       "available_quantity": 0} for tier in event["tiers"]],
            "selection": None, "pending": None
        },
        "flags": [], "read_error": None
    }


def selected_page(config, key="example-alpha", kind="confirmation", seconds=0, sequence=1):
    snapshot = observation(config, key, seconds, sequence)
    event = next(event for event in config["events"] if event["key"] == key)
    snapshot["page"]["kind"] = kind
    snapshot["page"]["selection"] = {
        "tier_minor": event["tiers"][0], "quantity": event["quantity"],
        "spectator_refs": copy.deepcopy(event["spectator_refs"]),
        "total_minor": event["tiers"][0] * event["quantity"], "fees_minor": 0,
        "adjacent": True, "submit_enabled": True
    }
    return snapshot


class EngineTests(unittest.TestCase):
    def setUp(self):
        self.config = sample_config()
        self.monitor = FleetMonitor(self.config)

    def run_step(self, snapshot, monitor=None):
        monitor = monitor or self.monitor
        now = dt.datetime.fromisoformat(snapshot["captured_at"])
        return monitor.step(snapshot, now)

    def assert_session_stop(self, result, expected):
        self.assertEqual(result["state"], expected)
        self.assertTrue(result["stop"])
        self.assertEqual(result["scope"], "session")
        self.assertIsNone(result["proposed_action"])

    def test_device_failures_pause_all_events_and_never_reconnect(self):
        cases = (
            ("serial", "emulator-5554", "wrong_device"),
            ("serial", "fixture-another-phone", "wrong_device"),
            ("model", "fixture-other-model", "wrong_device"),
            ("manufacturer", "other", "wrong_device"),
            ("transport", "tcp", "wrong_device"),
            ("state", "offline", "device_offline"),
            ("state", "unauthorized", "device_offline"),
            ("locked", True, "screen_blocked"),
            ("screen_on", False, "screen_blocked"),
        )
        for field, value, expected in cases:
            with self.subTest(field=field, value=value):
                monitor = FleetMonitor(sample_config())
                snapshot = observation(self.config)
                snapshot["device"][field] = value
                result = self.run_step(snapshot, monitor)
                self.assert_session_stop(result, expected)
                other = self.run_step(observation(self.config, "example-beta", 5, 2), monitor)
                self.assertEqual(other, result)

    def test_locked_or_offline_observation_does_not_consume_a_poll(self):
        snapshot = observation(self.config)
        snapshot["device"]["locked"] = True
        self.run_step(snapshot)
        self.assertEqual(self.monitor.runtime["example-alpha"]["count"], 0)

    def test_login_unknown_or_missing_stops_without_account_actions(self):
        for login in ("logged_out", "unknown", "login_failed"):
            with self.subTest(login=login):
                snapshot = observation(self.config)
                snapshot["app"]["login"] = login
                self.assert_session_stop(self.run_step(snapshot, FleetMonitor(self.config)), "login_required")

    def test_risk_captcha_rejection_and_limits_stop_the_session(self):
        for flag in ("captcha", "risk", "permission_denied", "login_failed", "refused",
                     "purchase_limit", "unpaid_constraint"):
            with self.subTest(flag=flag):
                snapshot = observation(self.config)
                snapshot["flags"] = [flag]
                self.assert_session_stop(self.run_step(snapshot, FleetMonitor(self.config)), flag)

    def test_unknown_page_wrong_app_and_unknown_flag_fail_closed(self):
        snapshot = observation(self.config)
        snapshot["page"]["kind"] = "unknown"
        self.assert_session_stop(self.run_step(snapshot), "unknown_page")
        snapshot = observation(self.config)
        snapshot["app"]["package"] = "fixture.other"
        self.assert_session_stop(self.run_step(snapshot, FleetMonitor(self.config)), "wrong_app")
        snapshot = observation(self.config)
        snapshot["flags"] = ["unmapped_popup"]
        self.assert_session_stop(self.run_step(snapshot, FleetMonitor(self.config)), "invalid_observation")

    def test_offline_profile_is_not_accepted_as_live_ui_calibration(self):
        snapshot = observation(self.config)
        snapshot["source"] = "cua_repl"
        snapshot["device"]["serial"] = "fixture-live-vivo"
        monitor = FleetMonitor(self.config, expected_serial="fixture-live-vivo")
        self.assert_session_stop(self.run_step(snapshot, monitor), "profile_unverified")

    def test_live_service_frequency_must_be_verified_even_after_page_calibration(self):
        config = sample_config()
        config["profile"].update(verified=True, version="fixture-app-1", evidence_ref="fixture-mapping")
        snapshot = observation(config)
        snapshot["source"] = "cua_repl"
        snapshot["device"]["serial"] = "fixture-live-vivo"
        monitor = FleetMonitor(config, expected_serial="fixture-live-vivo")
        self.assert_session_stop(self.run_step(snapshot, monitor), "service_limits_unverified")

    def test_stale_heartbeat_and_future_or_stale_capture_stop(self):
        for field, seconds, expected in (("heartbeat_at", -31, "heartbeat_stale"),
                                          ("heartbeat_at", 1, "heartbeat_stale"),
                                          ("captured_at", -11, "stale_observation"),
                                          ("captured_at", 1, "stale_observation")):
            with self.subTest(field=field, seconds=seconds):
                snapshot = observation(self.config)
                snapshot[field] = (BASE + dt.timedelta(seconds=seconds)).isoformat()
                result = FleetMonitor(self.config).step(snapshot, BASE)
                self.assert_session_stop(result, expected)

    def test_duplicate_sequence_or_old_capture_cannot_be_repolled(self):
        for duplicate_sequence in (True, False):
            with self.subTest(duplicate_sequence=duplicate_sequence):
                monitor = FleetMonitor(self.config)
                first = observation(self.config)
                self.run_step(first, monitor)
                second = observation(self.config, seconds=5, sequence=2)
                if duplicate_sequence:
                    second["sequence"] = 1
                else:
                    second["captured_at"] = first["captured_at"]
                self.assert_session_stop(monitor.step(second, BASE + dt.timedelta(seconds=5)), "replayed_observation")

    def test_invalid_fields_and_naive_clock_are_rejected(self):
        for mutation in ("missing", "boolean_sequence", "boolean_lock", "unknown_event"):
            with self.subTest(mutation=mutation):
                snapshot = observation(self.config)
                expected = "invalid_observation"
                if mutation == "missing":
                    del snapshot["evidence_ref"]
                elif mutation == "boolean_sequence":
                    snapshot["sequence"] = True
                elif mutation == "boolean_lock":
                    snapshot["device"]["locked"] = "false"
                else:
                    snapshot["event_key"] = "fixture-not-configured"
                    expected = "unknown_event"
                self.assert_session_stop(self.run_step(snapshot, FleetMonitor(self.config)), expected)
        with self.assertRaises(ConfigError):
            self.monitor.step(observation(self.config), BASE.replace(tzinfo=None))

    def test_dynamic_readiness_wait_has_no_refresh_or_click(self):
        snapshot = observation(self.config)
        snapshot["page"]["ready"] = False
        snapshot["page"]["kind"] = "loading"
        first = self.run_step(snapshot)
        self.assertEqual(first["state"], "awaiting_page")
        self.assertFalse(first["stop"])
        self.assertIsNone(first["proposed_action"])
        self.assertEqual(first["next_delay_seconds"], 2)
        early = self.run_step(observation(self.config, seconds=1, sequence=2))
        self.assertEqual(early["state"], "rate_limited")
        self.assertIsNone(early["proposed_action"])
        ready = self.run_step(observation(self.config, seconds=2, sequence=3))
        self.assertEqual(ready["state"], "sold_out")
        self.assertEqual(ready["next_delay_seconds"], 5)

    def test_stable_pages_slow_down_and_changed_pages_use_configured_floor(self):
        results = []
        for seq, seconds in enumerate((0, 5, 25, 65), 1):
            results.append(self.run_step(observation(self.config, seconds=seconds, sequence=seq)))
        self.assertEqual([r["next_delay_seconds"] for r in results], [5, 20, 40, 40])
        snapshot = observation(self.config, seconds=105, sequence=5)
        snapshot["page"]["tiers"][0].update(stock="available", enabled=True, available_quantity=2)
        result = self.run_step(snapshot)
        self.assertEqual(result["state"], "inventory_signal")
        self.assertEqual(result["next_delay_seconds"], 5)
        self.assertFalse(result["inventory_verified"])

    def test_service_floor_is_applied_to_normal_waits_and_backoff(self):
        config = sample_config()
        config["poll"]["service_minimum_seconds"] = 30
        monitor = FleetMonitor(config)
        self.assertEqual(self.run_step(observation(config), monitor)["next_delay_seconds"], 30)
        snapshot = observation(config, seconds=30, sequence=2)
        snapshot["read_error"] = "temporary_network"
        self.assertEqual(self.run_step(snapshot, monitor)["next_delay_seconds"], 60)

    def test_read_errors_back_off_then_stop_only_the_failed_event(self):
        for seq, seconds, expected, delay in ((1, 0, "read_backoff", 10),
                                             (2, 10, "read_backoff", 20),
                                             (3, 30, "read_error_limit", None)):
            snapshot = observation(self.config, seconds=seconds, sequence=seq)
            snapshot["read_error"] = "temporary_capture"
            result = self.run_step(snapshot)
            self.assertEqual(result["state"], expected)
            if delay is not None:
                self.assertEqual(result["next_delay_seconds"], delay)
                self.assertIsNone(result["proposed_action"])
        other = self.run_step(observation(self.config, "example-beta", 31, 4))
        self.assertEqual(other["state"], "sold_out")
        self.assertFalse(other["stop"])

    def test_read_success_resets_consecutive_error_budget(self):
        bad = observation(self.config)
        bad["read_error"] = "temporary_network"
        self.assertEqual(self.run_step(bad)["state"], "read_backoff")
        self.assertEqual(self.run_step(observation(self.config, seconds=10, sequence=2))["state"], "sold_out")
        bad = observation(self.config, seconds=15, sequence=3)
        bad["read_error"] = "temporary_network"
        self.assertEqual(self.run_step(bad)["next_delay_seconds"], 10)

    def test_loading_timeout_becomes_backoff_not_a_blind_action(self):
        for seq, seconds in ((1, 0), (2, 2), (3, 15)):
            snapshot = observation(self.config, seconds=seconds, sequence=seq)
            snapshot["page"].update(kind="loading", ready=False)
            result = self.run_step(snapshot)
        self.assertEqual(result["state"], "read_backoff")
        self.assertIsNone(result["proposed_action"])

    def test_session_and_poll_limits_bound_the_run(self):
        config = sample_config()
        config["poll"]["max_polls"] = 1
        monitor = FleetMonitor(config)
        self.run_step(observation(config), monitor)
        result = self.run_step(observation(config, seconds=5, sequence=2), monitor)
        self.assertEqual(result["state"], "poll_limit")
        self.assertEqual(result["scope"], "event")
        config = sample_config()
        config["poll"]["max_session_seconds"] = 5
        monitor = FleetMonitor(config)
        self.run_step(observation(config), monitor)
        self.assert_session_stop(self.run_step(observation(config, "example-beta", 5, 2), monitor), "session_limit")

    def test_independent_event_windows(self):
        config = sample_config()
        config["events"][0]["window"]["start"] = (BASE + dt.timedelta(seconds=10)).isoformat()
        monitor = FleetMonitor(config)
        first = self.run_step(observation(config), monitor)
        self.assertEqual(first["state"], "before_window")
        self.assertEqual(first["next_delay_seconds"], 10)
        self.assertIsNone(first["proposed_action"])
        other = self.run_step(observation(config, "example-beta", 1, 2), monitor)
        self.assertEqual(other["state"], "sold_out")
        config["events"][0]["window"]["start"] = (BASE - dt.timedelta(seconds=10)).isoformat()
        config["events"][0]["window"]["end"] = BASE.isoformat()
        monitor = FleetMonitor(config)
        self.assertEqual(self.run_step(observation(config), monitor)["state"], "window_ended")
        self.assertEqual(self.run_step(observation(config, "example-beta", 1, 2), monitor)["state"], "sold_out")

    def test_exact_target_includes_city_item_name_and_start_time(self):
        for field, value in (("item_id", "fixture-another-item"), ("name", "示例另一演出"),
                             ("city", "示例另一城市"), ("starts_at", "2030-01-02T13:00:00+00:00")):
            with self.subTest(field=field):
                snapshot = observation(self.config)
                snapshot["page"]["target"][field] = value
                result = self.run_step(snapshot, FleetMonitor(self.config))
                self.assertEqual(result["state"], "wrong_target")
                self.assertTrue(result["stop"])
                self.assertIsNone(result["proposed_action"])

    def test_ticket_strategy_uses_configured_priority_or_price_or_page_order(self):
        for strategy, expected in (("priority", 88000), ("lowest", 66000), ("any", 108000)):
            with self.subTest(strategy=strategy):
                config = sample_config()
                config["events"][0].update(tiers=[88000, 66000, 108000], strategy=strategy)
                snapshot = observation(config)
                snapshot["page"]["tiers"] = [
                    {"price_minor": price, "stock": "available", "enabled": True, "available_quantity": 2}
                    for price in (108000, 66000, 88000)
                ]
                result = self.run_step(snapshot, FleetMonitor(config))
                self.assertEqual(result["state"], "inventory_signal")
                self.assertEqual(result["price_minor"], expected)
                self.assertFalse(result["inventory_verified"])
                self.assertEqual(result["proposed_action"], "review_selection")

    def test_available_label_needs_quantity_and_eligible_tier(self):
        for quantity, expected in ((None, "quantity_unverified"), (1, "no_eligible_tier")):
            with self.subTest(quantity=quantity):
                snapshot = observation(self.config)
                snapshot["page"]["tiers"][0].update(stock="available", enabled=True, available_quantity=quantity)
                result = self.run_step(snapshot, FleetMonitor(self.config))
                self.assertEqual(result["state"], expected)
                self.assertIsNone(result["proposed_action"])

    def test_missing_stock_or_conflicting_controls_do_not_guess(self):
        for modification, expected in (("missing", "inventory_unknown"),
                                        ("unknown", "inventory_unknown"),
                                        ("conflict", "invalid_page")):
            with self.subTest(modification=modification):
                snapshot = observation(self.config)
                if modification == "missing":
                    snapshot["page"]["tiers"].pop()
                elif modification == "unknown":
                    snapshot["page"]["tiers"][0]["stock"] = "unknown"
                else:
                    snapshot["page"]["tiers"][0]["enabled"] = True
                result = self.run_step(snapshot, FleetMonitor(self.config))
                self.assertEqual(result["state"], expected)
                self.assertTrue(result["stop"])
                self.assertIsNone(result["proposed_action"])

    def test_confirmation_respects_inclusive_fees_budget_and_seating(self):
        config = sample_config(confirmed=True)
        for issue, expected in (("budget", "over_budget"), ("seating", "adjacent_unverified"),
                                ("quantity", "selection_mismatch"), ("total", "selection_mismatch")):
            with self.subTest(issue=issue):
                snapshot = selected_page(config)
                selection = snapshot["page"]["selection"]
                if issue == "budget":
                    selection.update(tier_minor=108000, fees_minor=5000, total_minor=221000)
                elif issue == "seating":
                    selection["adjacent"] = None
                elif issue == "quantity":
                    selection.update(quantity=1, total_minor=66000)
                else:
                    selection["total_minor"] += 1
                result = self.run_step(snapshot, FleetMonitor(config))
                self.assertEqual(result["state"], expected)
                self.assertIsNone(result["proposed_action"])
        snapshot = selected_page(config, "example-beta")
        snapshot["page"]["selection"]["adjacent"] = False
        self.assertEqual(self.run_step(snapshot, FleetMonitor(config))["state"], "orders_disabled")

    def test_submission_gates_require_parameters_spectators_constraints_and_explicit_enable(self):
        cases = (
            (sample_config(), "parameters_unverified"),
            (sample_config(confirmed=True), "orders_disabled"),
            (sample_config(confirmed=True, enabled=True), "confirmation_ready"),
        )
        for config, expected in cases:
            with self.subTest(expected=expected):
                result = self.run_step(selected_page(config), FleetMonitor(config))
                self.assertEqual(result["state"], expected)
                self.assertTrue(result["simulated"])
                self.assertEqual(result["proposed_action"], "simulate_pending_order" if expected == "confirmation_ready" else None)
        config = sample_config(confirmed=True, enabled=True)
        snapshot = selected_page(config)
        snapshot["page"]["selection"]["spectator_refs"] = ["fixture-person-a", "fixture-person-c"]
        self.assertEqual(self.run_step(snapshot, FleetMonitor(config))["state"], "spectators_mismatch")
        snapshot = selected_page(config)
        snapshot["page"]["selection"]["submit_enabled"] = False
        self.assertEqual(self.run_step(snapshot, FleetMonitor(config))["state"], "submit_disabled")
        config["constraints"]["verified"] = False
        self.assertEqual(self.run_step(selected_page(config), FleetMonitor(config))["state"], "constraints_unverified")

    def test_pending_payment_stops_only_its_event_and_reports_total_and_deadline(self):
        config = sample_config(confirmed=True)
        monitor = FleetMonitor(config)
        snapshot = selected_page(config, kind="pending_payment")
        deadline = (BASE + dt.timedelta(minutes=10)).isoformat()
        snapshot["page"]["pending"] = {"order_ref": "fixture-order-a", "amount_minor": 132000, "deadline": deadline}
        result = self.run_step(snapshot, monitor)
        self.assertEqual(result["state"], "pending_payment")
        self.assertEqual(result["scope"], "event")
        self.assertEqual(result["amount_minor"], 132000)
        self.assertEqual(result["deadline"], deadline)
        self.assertIsNone(result["proposed_action"])
        same = self.run_step(observation(config, seconds=5, sequence=2), monitor)
        self.assertEqual(same, result)
        other = self.run_step(observation(config, "example-beta", 6, 3), monitor)
        self.assertEqual(other["state"], "sold_out")
        self.assertFalse(other["stop"])

    def test_unpaid_capacity_blocks_other_event_submission(self):
        config = sample_config(confirmed=True, enabled=True)
        config["constraints"]["max_unpaid_orders"] = 1
        monitor = FleetMonitor(config)
        self.run_step(selected_page(config), monitor)
        self.assertIsNone(monitor.reserve_submission("example-alpha", BASE))
        result = monitor.record_order_result("example-alpha", {
            "event_key": "example-alpha", "scope": "event", "order_ref": "fixture-order-a",
            "state": "pending_payment", "simulated": True, "amount_minor": 132000,
            "deadline": (BASE + dt.timedelta(minutes=10)).isoformat()
        }, BASE)
        self.assertEqual(result["state"], "pending_payment")
        other = self.run_step(selected_page(config, "example-beta", seconds=1, sequence=2), monitor)
        self.assertEqual(other["state"], "unpaid_constraint")
        self.assertIsNone(other["proposed_action"])

    def test_uncertain_pending_amount_or_deadline_stops_the_entire_session(self):
        config = sample_config(confirmed=True)
        for field, value in (("amount_minor", 132001), ("deadline", BASE.isoformat())):
            with self.subTest(field=field):
                monitor = FleetMonitor(config)
                snapshot = selected_page(config, kind="pending_payment")
                snapshot["page"]["pending"] = {
                    "order_ref": "fixture-order-a", "amount_minor": 132000,
                    "deadline": (BASE + dt.timedelta(minutes=10)).isoformat()
                }
                snapshot["page"]["pending"][field] = value
                result = self.run_step(snapshot, monitor)
                self.assert_session_stop(result, "order_uncertain")
                other = self.run_step(observation(config, "example-beta", 1, 2), monitor)
                self.assertEqual(other, result)

    def test_order_adapter_unknown_duplicate_or_bad_result_never_retries(self):
        for result in ({"state": "uncertain"}, {"state": "duplicate_order"}, {}, None,
                       {"state": "pending_payment", "simulated": False, "amount_minor": 132000}):
            with self.subTest(result=result):
                monitor = FleetMonitor(self.config)
                decision = monitor.record_order_result("example-alpha", result, BASE)
                self.assert_session_stop(decision, "order_uncertain")
                self.assertEqual(self.run_step(observation(self.config, "example-beta"), monitor), decision)


if __name__ == "__main__":
    unittest.main()
