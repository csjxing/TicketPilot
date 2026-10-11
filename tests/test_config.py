"""Configuration validation tests using only artificial public fixtures."""
import copy
import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest

from damai.config import ConfigError, load_config, load_json, timestamp, validate_config


FIXTURES = Path(__file__).parent / "fixtures"


def sample_config():
    return copy.deepcopy(load_config(FIXTURES / "safe-config.json"))


class ConfigTests(unittest.TestCase):
    def test_two_independent_events_and_safe_defaults(self):
        config = sample_config()
        self.assertEqual(len(config["events"]), 2)
        self.assertFalse(config["orders_enabled"])
        self.assertEqual(config["mode"], "dry_run")
        self.assertFalse(config["constraints"]["verified"])
        self.assertTrue(all(not event["verified"] for event in config["events"]))
        self.assertTrue(all(not event["spectator_refs"] for event in config["events"]))

    def test_missing_required_parameters_are_rejected(self):
        for field in ("quantity", "budget_minor", "window", "spectator_refs", "target"):
            with self.subTest(field=field):
                config = sample_config()
                del config["events"][0][field]
                with self.assertRaises(ConfigError):
                    validate_config(config)

    def test_unknown_fields_are_not_silently_accepted(self):
        config = sample_config()
        config["payment_enabled"] = True
        with self.assertRaises(ConfigError):
            validate_config(config)

    def test_live_mode_is_unavailable(self):
        config = sample_config()
        config["mode"] = "live"
        with self.assertRaises(ConfigError):
            validate_config(config)

    def test_device_binding_cannot_be_switched_to_emulator_or_network(self):
        for field, value in (("manufacturer", "other"), ("model", "emulator"),
                             ("transport", "tcp"), ("serial_env", "OTHER_DEVICE")):
            with self.subTest(field=field):
                config = sample_config()
                config["device"][field] = value
                with self.assertRaises(ConfigError):
                    validate_config(config)

    def test_verified_profile_requires_version_and_evidence(self):
        for field in ("version", "evidence_ref"):
            with self.subTest(field=field):
                config = sample_config()
                config["profile"]["verified"] = True
                config["profile"]["version"] = "fixture-1"
                config["profile"]["evidence_ref"] = "fixture-mapping"
                config["profile"][field] = ""
                with self.assertRaises(ConfigError):
                    validate_config(config)

    def test_order_constraints_fail_closed_without_capacity(self):
        config = sample_config()
        config["constraints"]["verified"] = True
        with self.assertRaises(ConfigError):
            validate_config(config)

    def test_split_orders_are_rejected(self):
        config = sample_config()
        config["constraints"]["allow_split_orders"] = True
        with self.assertRaises(ConfigError):
            validate_config(config)

    def test_quantity_budget_and_tiers_are_integer_minor_units(self):
        changes = (("quantity", True), ("quantity", 0), ("budget_minor", 1.5),
                   ("budget_minor", 0), ("tiers", [66000, 66000]),
                   ("tiers", [True]), ("tiers", [0]), ("tiers", []))
        for field, value in changes:
            with self.subTest(field=field, value=value):
                config = sample_config()
                config["events"][0][field] = value
                with self.assertRaises(ConfigError):
                    validate_config(config)

    def test_budget_must_cover_one_configured_tier_before_fees(self):
        config = sample_config()
        config["events"][0]["budget_minor"] = 131999
        with self.assertRaises(ConfigError):
            validate_config(config)

    def test_strategy_and_seating_choices_validate(self):
        for field, values in (("strategy", ("priority", "lowest", "any")),
                              ("seating", ("required", "preferred"))):
            for value in values:
                with self.subTest(field=field, value=value):
                    config = sample_config()
                    config["events"][0][field] = value
                    validate_config(config)
            config = sample_config()
            config["events"][0][field] = "guess"
            with self.assertRaises(ConfigError):
                validate_config(config)

    def test_duplicate_event_key_or_same_target_alias_is_rejected(self):
        for duplicate_target in (False, True):
            with self.subTest(duplicate_target=duplicate_target):
                config = sample_config()
                if duplicate_target:
                    config["events"][1]["target"] = copy.deepcopy(config["events"][0]["target"])
                else:
                    config["events"][1]["key"] = config["events"][0]["key"]
                with self.assertRaises(ConfigError):
                    validate_config(config)

    def test_window_must_be_ordered_and_end_before_performance(self):
        for start, end in (("2030-01-01T08:00:00", "2030-01-02T11:00:00+00:00"),
                           ("2030-01-02T11:00:00+00:00", "2030-01-01T08:00:00+00:00"),
                           ("2030-01-01T08:00:00+00:00", "2030-01-02T13:00:00+00:00")):
            with self.subTest(start=start, end=end):
                config = sample_config()
                config["events"][0]["window"] = {"start": start, "end": end}
                with self.assertRaises(ConfigError):
                    validate_config(config)

    def test_confirmed_event_requires_exact_distinct_spectators(self):
        for refs in ([], ["fixture-person-a"], ["fixture-person-a", "fixture-person-a"]):
            with self.subTest(refs=refs):
                config = sample_config()
                config["events"][0]["verified"] = True
                config["events"][0]["spectator_refs"] = refs
                with self.assertRaises(ConfigError):
                    validate_config(config)
        config["events"][0]["spectator_refs"] = ["fixture-person-a", "fixture-person-b"]
        validate_config(config)

    def test_local_frequency_and_service_limits_are_explicit(self):
        for field, value in (("local_minimum_seconds", 4), ("changed_poll_seconds", 4),
                             ("stable_max_seconds", 9), ("max_backoff_seconds", 39),
                             ("max_polls", True), ("max_session_seconds", 0)):
            with self.subTest(field=field):
                config = sample_config()
                config["poll"][field] = value
                with self.assertRaises(ConfigError):
                    validate_config(config)
        config = sample_config()
        config["poll"]["service_frequency_verified"] = True
        with self.assertRaises(ConfigError):
            validate_config(config)
        config["poll"]["service_minimum_seconds"] = 15
        validate_config(config)

    def test_json_loader_rejects_duplicates_nonfinite_and_malformed_values(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "config.json"
            for body in ('{"key": 1, "key": 2}', '{"value": NaN}', '{"value": Infinity}', '{'):
                with self.subTest(body=body):
                    path.write_text(body, encoding="utf-8")
                    with self.assertRaises(ConfigError):
                        load_json(path)

    def test_timezone_normalization(self):
        self.assertEqual(timestamp("2030-01-01T17:00:00+08:00"),
                         dt.datetime(2030, 1, 1, 9, tzinfo=dt.timezone.utc))
        with self.assertRaises(ConfigError):
            timestamp("2030-01-01T09:00:00")


if __name__ == "__main__":
    unittest.main()
