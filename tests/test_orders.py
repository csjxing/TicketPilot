import copy
import json
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path

from damai.notifications import DINGTALK_WEBHOOK_ENV, SimulatedOutbox, simulated_notification
from damai.orders import OrderGuard, UnsupportedLive


NOW = datetime(2030, 1, 1, 12, 0, tzinfo=timezone.utc)


def event_fixture(key="event-a", item="fixture-item-a"):
    return {
        "key": key,
        "target": {"item_id": item, "name": "Fixture concert", "city": "Fixture city",
                   "starts_at": "2030-01-02T12:00:00+00:00"},
        "quantity": 2,
        "tiers": [98000, 138000],
        "strategy": "priority",
        "seating": "required",
        "budget_minor": 280000,
        "window": {"start": "2030-01-01T11:00:00+00:00", "end": "2030-01-01T13:00:00+00:00"},
        "spectator_refs": ["fixture-person-a", "fixture-person-b"],
        "verified": True,
    }


def intent_fixture(event=None):
    event = event or event_fixture()
    return dict(event["target"], tier_minor=98000, quantity=2,
                spectator_refs=list(event["spectator_refs"]), total_minor=198000,
                fees_minor=2000, adjacent=True, submit_enabled=True,
                observed_at=NOW.isoformat())


class FixtureAdapter:
    mode = "fixture"

    def __init__(self, result=None, exception=False):
        self.result = result if result is not None else {
            "status": "pending_payment", "order_ref": "fixture-order-a",
            "amount_minor": 198000, "deadline": "2030-01-01T12:15:00+00:00"}
        self.exception = exception
        self.calls = 0
        self.lock = threading.Lock()

    def submit_pending(self, intent):
        with self.lock:
            self.calls += 1
        if self.exception:
            raise RuntimeError("fixture failure with secret text")
        return copy.deepcopy(self.result)


class OrderGuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "journal"
        self.guard = OrderGuard(self.path)
        self.event = event_fixture()
        self.intent = intent_fixture(self.event)
        self.adapter = FixtureAdapter()

    def tearDown(self):
        self.temp.cleanup()

    def attempt(self, **kwargs):
        return self.guard.attempt(kwargs.get("event", self.event),
                                  kwargs.get("intent", self.intent),
                                  kwargs.get("adapter", self.adapter),
                                  kwargs.get("now", NOW),
                                  enabled=kwargs.get("enabled", True))

    def test_default_and_non_boolean_switches_never_call_adapter(self):
        self.assertEqual(self.guard.attempt(self.event, self.intent, self.adapter, NOW)["state"], "disabled")
        for value in (False, 1, "true", None):
            with self.subTest(value=value):
                self.assertEqual(self.attempt(enabled=value)["state"], "disabled")
        for value in (False, 1, "true", None):
            self.intent["submit_enabled"] = value
            self.assertEqual(self.attempt()["state"], "disabled")
        self.assertEqual(self.adapter.calls, 0)
        self.assertFalse(self.path.exists())

    def test_live_is_explicitly_unsupported(self):
        self.adapter.mode = "live"
        with self.assertRaises(UnsupportedLive):
            self.attempt()
        self.assertEqual(self.adapter.calls, 0)
        self.assertFalse(self.path.exists())

    def test_unverified_and_mismatched_targets_block(self):
        for value in (False, 1, "true", None):
            event = copy.deepcopy(self.event)
            event["verified"] = value
            self.assertEqual(self.attempt(event=event)["reason"], "parameters_not_verified")
        for field in ("item_id", "name", "city", "starts_at"):
            intent = copy.deepcopy(self.intent)
            intent[field] = "different"
            self.assertEqual(self.attempt(intent=intent)["reason"], "target_mismatch")
        self.assertEqual(self.adapter.calls, 0)

    def test_stale_future_and_timezone_free_observations_block(self):
        for seconds in (-31, 1):
            intent = copy.deepcopy(self.intent)
            intent["observed_at"] = (NOW + timedelta(seconds=seconds)).isoformat()
            self.assertEqual(self.attempt(intent=intent)["reason"], "stale_observation")
        self.intent["observed_at"] = "2030-01-01T12:00:00"
        self.assertEqual(self.attempt()["reason"], "invalid_timestamp")
        self.assertEqual(self.attempt(now=NOW.replace(tzinfo=None))["reason"], "invalid_now")
        self.assertEqual(self.adapter.calls, 0)

    def test_boundary_observation_age_is_accepted(self):
        self.intent["observed_at"] = (NOW - timedelta(seconds=30)).isoformat()
        self.assertEqual(self.attempt()["state"], "pending_payment")

    def test_time_window_is_enforced(self):
        for end in ("2029-12-31T12:00:00+00:00", "2030-01-01T12:00:00+00:00"):
            event = copy.deepcopy(self.event)
            event["window"]["end"] = end
            self.assertIn(self.attempt(event=event)["reason"], ("invalid_time_window", "outside_time_window"))
        self.event["window"]["start"] = "2030-01-01T12:00:01+00:00"
        self.assertEqual(self.attempt()["reason"], "outside_time_window")
        self.assertEqual(self.adapter.calls, 0)

    def test_quantity_and_spectators_must_match(self):
        for quantity in (True, 1, 3, "2"):
            intent = copy.deepcopy(self.intent)
            intent["quantity"] = quantity
            self.assertEqual(self.attempt(intent=intent)["reason"], "quantity_mismatch")
        for refs in ([], ["different-a", "different-b"], list(reversed(self.event["spectator_refs"]))):
            intent = copy.deepcopy(self.intent)
            intent["spectator_refs"] = refs
            self.assertEqual(self.attempt(intent=intent)["reason"], "spectator_mismatch")
        for refs in (["duplicate_order", "duplicate_order"], ["a", ""], ["a", {}]):
            event = copy.deepcopy(self.event)
            event["spectator_refs"] = refs
            self.assertEqual(self.attempt(event=event)["reason"], "invalid_spectator_refs")
        self.assertEqual(self.adapter.calls, 0)

    def test_tier_amount_fees_and_inclusive_budget(self):
        cases = [("tier_minor", 1, "tier_mismatch"),
                 ("tier_minor", True, "tier_mismatch"),
                 ("fees_minor", -1, "invalid_amount"),
                 ("fees_minor", True, "invalid_amount"),
                 ("total_minor", 198001, "amount_mismatch")]
        for field, value, reason in cases:
            intent = copy.deepcopy(self.intent)
            intent[field] = value
            self.assertEqual(self.attempt(intent=intent)["reason"], reason)
        event = copy.deepcopy(self.event)
        event["budget_minor"] = 197999
        self.assertEqual(self.attempt(event=event)["reason"], "over_budget")
        event["budget_minor"] = 198000
        self.assertEqual(self.attempt(event=event)["state"], "pending_payment")

    def test_adjacency_is_a_real_boolean_and_required_confirmation(self):
        for value in (False, None):
            intent = copy.deepcopy(self.intent)
            intent["adjacent"] = value
            self.assertEqual(self.attempt(intent=intent)["reason"], "adjacent_not_confirmed")
        for value in (1, "true"):
            intent = copy.deepcopy(self.intent)
            intent["adjacent"] = value
            self.assertEqual(self.attempt(intent=intent)["reason"], "invalid_adjacent")
        self.event["seating"] = "preferred"
        self.intent["adjacent"] = None
        self.assertEqual(self.attempt()["state"], "pending_payment")

    def test_success_is_durable_and_prevents_repeat(self):
        first = self.attempt()
        self.assertEqual(first["state"], "pending_payment")
        self.assertEqual(first["amount_minor"], 198000)
        self.assertEqual(first["deadline"], "2030-01-01T12:15:00+00:00")
        fresh_guard = OrderGuard(self.path)
        repeated = fresh_guard.attempt(self.event, self.intent, self.adapter, NOW, enabled=True)
        self.assertEqual(repeated["state"], "duplicate_order")
        self.assertEqual(repeated["previous_state"], "pending_payment")
        self.assertEqual(self.adapter.calls, 1)
        files = list(self.path.glob("*.json"))
        self.assertEqual(len(files), 2)
        for path in files:
            text = path.read_text()
            self.assertNotIn("fixture-person", text)
            self.assertNotIn("Fixture concert", text)
            self.assertEqual(json.loads(text)["state"], "pending_payment")

    def test_event_configuration_change_cannot_bypass_lock(self):
        self.attempt()
        changed = event_fixture(item="different-item")
        result = self.attempt(event=changed, intent=intent_fixture(changed))
        self.assertEqual(result["reason"], "event_already_attempted")
        self.assertEqual(self.adapter.calls, 1)

    def test_alias_and_equivalent_timezone_cannot_bypass_target_lock(self):
        self.attempt()
        alias = event_fixture(key="event-alias")
        alias["target"]["name"] = "Different display name"
        alias["target"]["starts_at"] = "2030-01-02T20:00:00+08:00"
        result = self.attempt(event=alias, intent=intent_fixture(alias))
        self.assertEqual(result["reason"], "target_already_attempted")
        self.assertEqual(self.adapter.calls, 1)

    def test_independent_events_can_each_create_one_order(self):
        self.assertEqual(self.attempt()["state"], "pending_payment")
        other = event_fixture(key="event-b", item="fixture-item-b")
        self.assertEqual(self.attempt(event=other, intent=intent_fixture(other))["state"], "pending_payment")
        self.assertEqual(self.adapter.calls, 2)

    def test_same_item_different_performance_is_independent(self):
        self.attempt()
        other = event_fixture(key="event-b")
        other["target"]["starts_at"] = "2030-01-03T12:00:00+00:00"
        self.assertEqual(self.attempt(event=other, intent=intent_fixture(other))["state"], "pending_payment")
        self.assertEqual(self.adapter.calls, 2)

    def test_rejected_captcha_and_uncertain_attempts_are_retained(self):
        for status in ("rejected", "captcha", "uncertain"):
            with tempfile.TemporaryDirectory() as directory:
                guard = OrderGuard(directory)
                adapter = FixtureAdapter({"status": status})
                first = guard.attempt(self.event, self.intent, adapter, NOW, enabled=True)
                self.assertEqual(first["state"], status)
                repeat = guard.attempt(self.event, self.intent, adapter, NOW, enabled=True)
                self.assertEqual(repeat["state"], "duplicate_order")
                self.assertEqual(adapter.calls, 1)

    def test_exception_is_uncertain_and_cannot_retry(self):
        adapter = FixtureAdapter(exception=True)
        self.assertEqual(self.attempt(adapter=adapter)["reason"], "submission_exception")
        self.assertEqual(self.attempt(adapter=adapter)["state"], "duplicate_order")
        self.assertEqual(adapter.calls, 1)
        self.assertNotIn("secret", "".join(path.read_text() for path in self.path.glob("*.json")))

    def test_malformed_result_amount_or_deadline_stops_uncertain(self):
        results = [[], {}, {"status": "paid"},
                   {"status": "pending_payment", "amount_minor": 198001},
                   {"status": "pending_payment", "amount_minor": True}]
        base = FixtureAdapter().result
        for field, value in (("order_ref", ""), ("deadline", "bad"),
                             ("deadline", "2030-01-01T12:00:00+00:00"),
                             ("deadline", "2030-01-02T12:00:00+00:00"),
                             ("deadline", "2030-01-03T12:00:00+00:00")):
            result = copy.deepcopy(base)
            result[field] = value
            results.append(result)
        for result in results:
            with self.subTest(result=result), tempfile.TemporaryDirectory() as directory:
                adapter = FixtureAdapter(result)
                guard = OrderGuard(directory)
                outcome = guard.attempt(self.event, self.intent, adapter, NOW, enabled=True)
                self.assertEqual(outcome["state"], "uncertain")
                self.assertEqual(guard.attempt(self.event, self.intent, adapter, NOW, enabled=True)["state"], "duplicate_order")
                self.assertEqual(adapter.calls, 1)

    def test_expected_deadline_mismatch_is_uncertain(self):
        self.intent["expected_deadline"] = "2030-01-01T12:16:00+00:00"
        self.assertEqual(self.attempt()["reason"], "result_deadline_mismatch")

    def test_existing_corrupt_journal_never_calls_adapter(self):
        self.attempt()
        next(self.path.glob("event-*.json")).write_text("{")
        self.assertEqual(self.attempt()["state"], "uncertain")
        self.assertEqual(self.adapter.calls, 1)

    def test_unavailable_journal_never_calls_adapter(self):
        self.path.write_text("file blocks directory")
        self.assertEqual(self.attempt()["reason"], "journal_io_error")
        self.assertEqual(self.adapter.calls, 0)

    def test_claim_exists_before_adapter_call(self):
        path = self.path

        class InspectAdapter(FixtureAdapter):
            def submit_pending(self, intent):
                records = [json.loads(file.read_text()) for file in path.glob("*.json")]
                if len(records) != 2 or any(record["state"] != "submitting" for record in records):
                    raise AssertionError("submit started before durable claims")
                return super().submit_pending(intent)

        adapter = InspectAdapter()
        self.assertEqual(self.attempt(adapter=adapter)["state"], "pending_payment")
        self.assertEqual(adapter.calls, 1)

    def test_concurrent_calls_submit_only_once(self):
        barrier = threading.Barrier(6)

        def call(_):
            barrier.wait()
            return self.attempt()

        with ThreadPoolExecutor(max_workers=6) as executor:
            outcomes = list(executor.map(call, range(6)))
        self.assertEqual(self.adapter.calls, 1)
        self.assertEqual(sum(item["state"] == "pending_payment" for item in outcomes), 1)
        self.assertTrue(all(item["state"] in ("pending_payment", "duplicate_order", "uncertain") for item in outcomes))
        self.assertFalse(hasattr(self.guard, "pay"))


class NotificationTests(unittest.TestCase):
    def test_only_whitelisted_fields_reach_simulated_message(self):
        result = {"event_key": "event-a", "state": "pending_payment", "reason": "pending_order_created",
                  "amount_minor": 198000, "deadline": "2030-01-01T12:15:00+00:00",
                  "order_ref": "private-order", "spectator_refs": ["private-person"],
                  "device_serial": "private-device", "webhook": "private-webhook"}
        message = simulated_notification(result)
        self.assertEqual(set(message["payload"]), {"event_key", "state", "reason", "amount_minor", "deadline"})
        self.assertNotIn("private", json.dumps(message))
        reversed_result = dict(reversed(list(result.items())))
        self.assertEqual(message["dedupe_id"], simulated_notification(reversed_result)["dedupe_id"])
        self.assertEqual(DINGTALK_WEBHOOK_ENV, "DAMAI_DINGTALK_WEBHOOK")

    def test_deduplication_survives_reopen(self):
        result = {"event_key": "event-a", "state": "captcha", "reason": "submission_captcha"}
        with tempfile.TemporaryDirectory() as directory:
            first = SimulatedOutbox(directory).emit(result)
            second = SimulatedOutbox(directory).emit(result)
            self.assertTrue(first["emitted"])
            self.assertFalse(second["emitted"])
            self.assertEqual(first["dedupe_id"], second["dedupe_id"])
            self.assertEqual(len(list(Path(directory).glob("*.json"))), 1)
            result["state"] = "uncertain"
            self.assertTrue(SimulatedOutbox(directory).emit(result)["emitted"])

    def test_invalid_types_are_not_coerced_into_notification(self):
        message = simulated_notification({"event_key": [], "amount_minor": True, "reason": {"secret": "value"}})
        self.assertEqual(message["payload"], {})
        with self.assertRaises(ValueError):
            simulated_notification([])


if __name__ == "__main__":
    unittest.main()
