import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

from damai.config import ConfigError
from damai.scheduler import SharedRequestBudget


NOW = datetime(2030, 1, 1, 12, 0, tzinfo=timezone.utc)


def budget(local=5, service=None):
    return SharedRequestBudget({"local_minimum_seconds": local,
                                "service_minimum_seconds": service})


class SharedRequestBudgetTests(unittest.TestCase):
    def test_all_events_share_the_same_floor(self):
        shared = budget()
        self.assertEqual(shared.reserve("event-a", NOW), {"granted": True, "delay_seconds": 0})
        self.assertEqual(shared.reserve("event-b", NOW + timedelta(seconds=1)),
                         {"granted": False, "delay_seconds": 4})
        self.assertEqual(shared.reserve("event-b", NOW + timedelta(seconds=5)),
                         {"granted": True, "delay_seconds": 0})

    def test_service_floor_wins_when_larger(self):
        shared = budget(service=12)
        self.assertEqual(shared.floor_seconds, 12)
        self.assertTrue(shared.reserve("event-a", NOW)["granted"])
        self.assertEqual(shared.reserve("event-b", NOW + timedelta(seconds=5)),
                         {"granted": False, "delay_seconds": 7})
        self.assertTrue(shared.reserve("event-b", NOW + timedelta(seconds=12))["granted"])

    def test_local_floor_wins_when_larger(self):
        shared = budget(local=10, service=7)
        self.assertEqual(shared.floor_seconds, 10)
        self.assertTrue(shared.reserve("event-a", NOW)["granted"])
        self.assertEqual(shared.reserve("event-b", NOW + timedelta(seconds=7)),
                         {"granted": False, "delay_seconds": 3})
        self.assertTrue(shared.reserve("event-b", NOW + timedelta(seconds=10))["granted"])

    def test_duplicate_requests_do_not_postpone_the_next_slot(self):
        shared = budget()
        shared.reserve("event-a", NOW)
        for key in ("event-a", "event-b", "event-c"):
            self.assertEqual(shared.reserve(key, NOW), {"granted": False, "delay_seconds": 5})
        self.assertTrue(shared.reserve("event-b", NOW + timedelta(seconds=5))["granted"])

    def test_denied_requests_do_not_consume_a_reservation(self):
        shared = budget()
        shared.reserve("event-a", NOW)
        for seconds in (1, 2, 3, 4):
            self.assertEqual(shared.reserve("event-b", NOW + timedelta(seconds=seconds)),
                             {"granted": False, "delay_seconds": 5 - seconds})
        self.assertTrue(shared.reserve("event-c", NOW + timedelta(seconds=5))["granted"])

    def test_fractional_wait_rounds_up(self):
        shared = budget()
        shared.reserve("event-a", NOW)
        self.assertEqual(shared.reserve("event-b", NOW + timedelta(seconds=4.9)),
                         {"granted": False, "delay_seconds": 1})
        self.assertTrue(shared.reserve("event-b", NOW + timedelta(seconds=5))["granted"])

    def test_clock_reversal_stops_without_consuming_a_slot(self):
        shared = budget()
        shared.reserve("event-a", NOW)
        shared.reserve("event-b", NOW + timedelta(seconds=3))
        with self.assertRaisesRegex(ConfigError, "backward"):
            shared.reserve("event-c", NOW + timedelta(seconds=2))
        self.assertTrue(shared.reserve("event-c", NOW + timedelta(seconds=5))["granted"])

    def test_naive_or_invalid_clock_does_not_mutate_budget(self):
        shared = budget()
        for value in (None, "2030-01-01T12:00:00Z", NOW.replace(tzinfo=None)):
            with self.subTest(value=value), self.assertRaises(ConfigError):
                shared.reserve("event-a", value)
        self.assertTrue(shared.reserve("event-a", NOW)["granted"])

    def test_equivalent_timezone_cannot_get_another_grant(self):
        shared = budget()
        self.assertTrue(shared.reserve("event-a", NOW)["granted"])
        represented_elsewhere = NOW.astimezone(timezone(timedelta(hours=8)))
        self.assertEqual(shared.reserve("event-b", represented_elsewhere),
                         {"granted": False, "delay_seconds": 5})

    def test_empty_or_nontext_event_does_not_mutate_budget(self):
        shared = budget()
        for value in (None, 1, [], "", " "):
            with self.subTest(value=value), self.assertRaises(ConfigError):
                shared.reserve(value, NOW)
        self.assertTrue(shared.reserve("event-a", NOW)["granted"])

    def test_policy_has_safe_integer_floors(self):
        for local in (None, 0, 4, True, 5.0, "5"):
            with self.subTest(local=local), self.assertRaises(ConfigError):
                budget(local=local)
        for service in (0, -1, True, 5.0, "5"):
            with self.subTest(service=service), self.assertRaises(ConfigError):
                budget(service=service)
        with self.assertRaises(ConfigError):
            SharedRequestBudget([])

    def test_concurrent_events_obtain_exactly_one_grant(self):
        shared = budget()
        barrier = threading.Barrier(6)

        def attempt(number):
            barrier.wait()
            return shared.reserve("event-" + str(number), NOW)

        with ThreadPoolExecutor(max_workers=6) as executor:
            outcomes = list(executor.map(attempt, range(6)))
        self.assertEqual(sum(result["granted"] for result in outcomes), 1)
        self.assertEqual(sum(result["delay_seconds"] == 5 for result in outcomes), 5)


if __name__ == "__main__":
    unittest.main()
