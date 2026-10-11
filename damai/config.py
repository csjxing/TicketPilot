"""Strict, generic multi-event configuration. No historical targets are imported."""
from __future__ import annotations

import datetime as dt
import json
from pathlib import Path


class ConfigError(ValueError):
    pass


def timestamp(value):
    if not isinstance(value, str):
        raise ConfigError("time must be ISO-8601 text with timezone")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ConfigError("invalid timestamp") from exc
    if parsed.tzinfo is None:
        raise ConfigError("timestamp must include timezone")
    return parsed.astimezone(dt.timezone.utc)


def keys(obj, required, optional=()):
    if not isinstance(obj, dict) or set(required) - set(obj) or set(obj) - set(required) - set(optional):
        raise ConfigError("missing or unknown fields")


def positive(value, minimum=1):
    if type(value) is not int or value < minimum:
        raise ConfigError("expected an integer in the allowed range")
    return value


def bool_value(value):
    if type(value) is not bool:
        raise ConfigError("expected a JSON boolean")
    return value


def nonempty(value):
    if not isinstance(value, str) or not value.strip():
        raise ConfigError("required text is missing")
    return value


def load_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ConfigError("duplicate JSON key")
            result[key] = value
        return result
    def finite(_):
        raise ConfigError("nonfinite number")
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=unique, parse_constant=finite)
    except (OSError, json.JSONDecodeError) as exc:
        raise ConfigError("cannot read configuration/observation JSON") from exc


def validate_config(config):
    keys(config, {"schema_version", "mode", "device", "profile", "poll", "orders_enabled", "constraints", "events"})
    if type(config["schema_version"]) is not int or config["schema_version"] != 1 or config["mode"] != "dry_run":
        raise ConfigError("only schema 1 dry_run is implemented")
    device = config["device"]
    keys(device, {"serial_env", "manufacturer", "model", "transport"})
    if device != {"serial_env": "DAMAI_DEVICE_SERIAL", "manufacturer": "vivo", "model": "V2547A", "transport": "usb"}:
        raise ConfigError("first version is restricted to the selected physical vivo model and USB")
    profile = config["profile"]
    keys(profile, {"id", "package", "version", "verified", "evidence_ref"})
    nonempty(profile["id"])
    if profile["package"] != "cn.damai":
        raise ConfigError("only the domestic Damai App package is supported")
    bool_value(profile["verified"])
    if profile["verified"]:
        nonempty(profile["version"])
        nonempty(profile["evidence_ref"])
    policy = config["poll"]
    fields = {"local_minimum_seconds", "readiness_check_seconds", "changed_poll_seconds", "stable_poll_seconds", "stable_max_seconds", "max_loading_seconds", "max_read_errors", "max_backoff_seconds", "heartbeat_timeout_seconds", "snapshot_ttl_seconds", "max_polls", "max_session_seconds", "service_minimum_seconds", "service_frequency_verified"}
    keys(policy, fields)
    for key in fields - {"service_minimum_seconds", "service_frequency_verified"}:
        positive(policy[key])
    bool_value(policy["service_frequency_verified"])
    if policy["local_minimum_seconds"] < 5:
        raise ConfigError("local request floor must be at least five seconds; this is not a service guarantee")
    if policy["changed_poll_seconds"] < policy["local_minimum_seconds"] or policy["stable_poll_seconds"] < policy["changed_poll_seconds"] or policy["stable_max_seconds"] < policy["stable_poll_seconds"]:
        raise ConfigError("invalid change/stable polling bounds")
    if policy["max_backoff_seconds"] < policy["stable_max_seconds"]:
        raise ConfigError("backoff cap below normal wait cap")
    if policy["service_minimum_seconds"] is not None:
        positive(policy["service_minimum_seconds"])
        if policy["service_minimum_seconds"] > policy["max_backoff_seconds"]:
            raise ConfigError("service floor exceeds wait cap")
    if policy["service_frequency_verified"] and policy["service_minimum_seconds"] is None:
        raise ConfigError("verified service limits need a concrete floor")
    bool_value(config["orders_enabled"])
    constraints = config["constraints"]
    keys(constraints, {"verified", "max_unpaid_orders", "allow_split_orders"})
    bool_value(constraints["verified"])
    bool_value(constraints["allow_split_orders"])
    if constraints["allow_split_orders"]:
        raise ConfigError("split orders are unsupported and have not been authorized")
    if constraints["max_unpaid_orders"] is not None:
        positive(constraints["max_unpaid_orders"])
    if constraints["verified"] and constraints["max_unpaid_orders"] is None:
        raise ConfigError("verified constraints must include unpaid-order capacity")
    if not isinstance(config["events"], list) or not config["events"]:
        raise ConfigError("at least one fully specified event is required")
    seen_keys, seen_targets = set(), set()
    for event in config["events"]:
        keys(event, {"key", "target", "quantity", "tiers", "strategy", "seating", "budget_minor", "window", "spectator_refs", "verified"})
        nonempty(event["key"])
        if event["key"] in seen_keys:
            raise ConfigError("event keys must be unique")
        seen_keys.add(event["key"])
        target = event["target"]
        keys(target, {"item_id", "name", "city", "starts_at"})
        for key in ("item_id", "name", "city"):
            nonempty(target[key])
        starts = timestamp(target["starts_at"])
        identity = (target["item_id"], starts)
        if identity in seen_targets:
            raise ConfigError("duplicate target aliases are not independent events")
        seen_targets.add(identity)
        positive(event["quantity"])
        positive(event["budget_minor"])
        tiers = event["tiers"]
        if not isinstance(tiers, list) or not tiers or any(type(x) is not int or x <= 0 for x in tiers) or len(set(tiers)) != len(tiers):
            raise ConfigError("tiers must be unique positive integer prices in minor currency units")
        if min(tiers) * event["quantity"] > event["budget_minor"]:
            raise ConfigError("budget cannot cover any configured tier before fees")
        if event["strategy"] not in ("priority", "lowest", "any") or event["seating"] not in ("required", "preferred"):
            raise ConfigError("unknown tier/seating strategy")
        window = event["window"]
        keys(window, {"start", "end"})
        if not timestamp(window["start"]) < timestamp(window["end"]) <= starts:
            raise ConfigError("event window must start before end and end no later than the performance")
        refs = event["spectator_refs"]
        if not isinstance(refs, list) or any(not isinstance(x, str) or not x for x in refs) or len(set(refs)) != len(refs):
            raise ConfigError("spectators must be distinct opaque references")
        bool_value(event["verified"])
        if event["verified"] and len(refs) != event["quantity"]:
            raise ConfigError("confirmed events require one selected spectator per ticket")
    return config


def load_config(path):
    return validate_config(load_json(path))
