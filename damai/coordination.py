"""Pure offline configuration and executor coordination contract.

This module neither contacts an executor nor schedules a service. Jobs only
permit fixture checks. They never authorize a tap, refresh, submit or payment.
"""
from __future__ import annotations

import copy
import datetime as dt
import hashlib
import json
import re

from .config import ConfigError, keys, positive, timestamp, validate_config


def _clock(value):
    if not isinstance(value, dt.datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ConfigError("an aware datetime is required")
    return value.astimezone(dt.timezone.utc)


class LocalCoordinator:
    """One bounded, fixed-configuration fixture session with independent events.

    Config and heartbeats are private in-memory inputs. Public envelopes and
    plans omit the device serial, spectator references and target details.
    plan() issues a job at most once per event's due time and advances its count.
    A reported wait may adjust that event's next due time; a stopped event cannot
    be resumed. Health pauses are recoverable by a new valid fixture heartbeat,
    but never extend the fixed session deadline.
    """

    def __init__(self, config, executor_key, started_at):
        if not isinstance(executor_key, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", executor_key):
            raise ConfigError("a fixed non-sensitive executor key is required")
        try:
            frozen = copy.deepcopy(config)
            validate_config(frozen)
            encoded = json.dumps(frozen, sort_keys=True, separators=(",", ":"),
                                 ensure_ascii=False, allow_nan=False).encode("utf-8")
        except (TypeError, ValueError, OverflowError) as exc:
            raise ConfigError("configuration must be strict JSON data") from exc
        self._config = frozen
        self._config_revision = hashlib.sha256(encoded).hexdigest()
        self._executor_key = executor_key
        self._started_at = _clock(started_at)
        self._last_now = self.started_at
        self._heartbeat = None
        self._last_heartbeat = None
        self._heartbeat_reason = "heartbeat_missing"
        self._terminal_reason = None
        self._events = {event["key"]: event for event in frozen["events"]}
        self._runtime = {key: {"next_due": timestamp(event["window"]["start"]),
                              "issued": 0, "finished": False}
                         for key, event in self._events.items()}

    @property
    def config_revision(self):
        return self._config_revision

    @property
    def executor_key(self):
        return self._executor_key

    @property
    def started_at(self):
        return self._started_at

    def execution_config(self):
        """Return a fresh private copy to the local fixture executor, never log it."""
        return copy.deepcopy(self._config)

    def envelope(self):
        """Public revision metadata; no private configuration is serialized."""
        return {"schema_version": 1, "mode": "dry_run", "simulated": True,
                "config_revision": self.config_revision,
                "event_keys": list(self._events)}

    def _base(self, state, reason, paused=False, stop=False, **extra):
        return {**self.envelope(), "state": state, "reason": reason,
                "paused": paused, "stop": stop, "jobs": [],
                "allowed_event_keys": [], "due_event_keys": [], **extra}

    def _time_reason(self, now):
        try:
            now = _clock(now)
        except ConfigError:
            return None, "invalid_clock"
        if now < self._last_now:
            return now, "clock_regressed"
        self._last_now = now
        if (now - self.started_at).total_seconds() >= self._config["poll"]["max_session_seconds"]:
            self._terminal_reason = "session_limit"
        return now, None

    def _health_reason(self, now):
        if self._terminal_reason:
            return self._terminal_reason
        if self._heartbeat is None:
            return self._heartbeat_reason
        heartbeat = self._heartbeat
        age = (now - timestamp(heartbeat["sent_at"])).total_seconds()
        if age < 0:
            return "heartbeat_future"
        if age > self._config["poll"]["heartbeat_timeout_seconds"]:
            return "heartbeat_expired"
        if heartbeat["executor_state"] != "connected":
            return "executor_disconnected" if heartbeat["executor_state"] == "disconnected" else "executor_unknown"
        device = heartbeat["device"]
        if any(value is None for value in device.values()):
            return "device_unknown"
        if device["identity_verified"] is not True:
            return "wrong_device"
        if device["connected"] is not True:
            return "device_disconnected"
        if device["locked"] or not device["screen_on"]:
            return "screen_blocked"
        if heartbeat["capabilities"]["ui_observation"] != "available":
            return "capability_unavailable"
        return None

    def _status(self, now):
        now, reason = self._time_reason(now)
        if reason:
            return None, self._base("paused", reason, paused=True, stop=True)
        reason = self._health_reason(now)
        if reason:
            state = "session_ended" if self._terminal_reason else "paused"
            return now, self._base(state, reason, paused=True, stop=True)
        return now, None

    def accept_heartbeat(self, heartbeat, now):
        """Accept only synthetic fixture heartbeats from the bound executor.

        Exact repeats may reuse the same cached health until its original TTL.
        A lower sequence or changed contents at the same sequence pauses. A bad
        input clears prior health so it cannot reuse an earlier healthy permit.
        """
        checked_now, clock_reason = self._time_reason(now)
        if clock_reason:
            self._heartbeat = None
            self._heartbeat_reason = clock_reason
            return self._base("paused", clock_reason, paused=True, stop=True)
        if self._terminal_reason:
            return self._base("session_ended", self._terminal_reason, paused=True, stop=True)
        try:
            keys(heartbeat, {"schema_version", "mode", "source", "config_revision", "executor_key",
                             "sequence", "sent_at", "executor_state", "device", "capabilities"})
            if type(heartbeat["schema_version"]) is not int or heartbeat["schema_version"] != 1 or heartbeat["mode"] != "dry_run":
                raise ConfigError("invalid heartbeat schema")
            if heartbeat["source"] != "fixture":
                raise ConfigError("only fixture heartbeats are implemented")
            if heartbeat["config_revision"] != self.config_revision or heartbeat["executor_key"] != self.executor_key:
                raise ConfigError("heartbeat is not bound to this revision and executor")
            positive(heartbeat["sequence"])
            sent_at = timestamp(heartbeat["sent_at"])
            if heartbeat["executor_state"] not in ("connected", "disconnected", "unknown"):
                raise ConfigError("unknown executor state")
            keys(heartbeat["device"], {"identity_verified", "connected", "locked", "screen_on"})
            if any(value is not None and type(value) is not bool for value in heartbeat["device"].values()):
                raise ConfigError("device state must be a boolean or explicitly unknown")
            keys(heartbeat["capabilities"], {"ui_observation"})
            if heartbeat["capabilities"]["ui_observation"] not in ("available", "unavailable", "unknown"):
                raise ConfigError("unknown observation capability")
            if sent_at > checked_now:
                raise ConfigError("future heartbeat")
            if self._last_heartbeat is not None:
                previous = self._last_heartbeat
                if heartbeat["sequence"] < previous["sequence"] or (heartbeat["sequence"] == previous["sequence"] and heartbeat != previous):
                    raise ConfigError("replayed heartbeat")
                if heartbeat["sequence"] > previous["sequence"] and sent_at <= timestamp(previous["sent_at"]):
                    raise ConfigError("heartbeat clock did not advance")
            self._heartbeat = copy.deepcopy(heartbeat)
            self._last_heartbeat = copy.deepcopy(heartbeat)
            self._heartbeat_reason = None
        except (ConfigError, TypeError, KeyError, ValueError, AttributeError):
            self._heartbeat = None
            self._heartbeat_reason = "invalid_heartbeat"
            return self._base("paused", "invalid_heartbeat", paused=True, stop=True)
        reason = self._health_reason(checked_now)
        if reason:
            return self._base("paused", reason, paused=True, stop=True)
        return self._base("ready", "fixture_executor_ready")

    def plan(self, now, event_keys=None):
        """Issue bounded fixture checks for due events, optionally a chosen subset.

        allowed/due lists describe the full session before these jobs are issued.
        Each returned job is reserved immediately. Repeating plan at the same
        time cannot issue it again. These are metadata-only fixture permissions;
        an actual service request would require the separate shared UI budget.
        """
        now, blocked = self._status(now)
        if blocked:
            return blocked
        if event_keys is not None and (not isinstance(event_keys, list) or
                any(not isinstance(key, str) or key not in self._events for key in event_keys) or
                len(event_keys) != len(set(event_keys))):
            return self._base("paused", "unknown_event", paused=True, stop=True)
        requested = set(self._events) if event_keys is None else set(event_keys)
        allowed, due, jobs = [], [], []
        for key, event in self._events.items():
            runtime = self._runtime[key]
            if runtime["finished"]:
                continue
            if now >= timestamp(event["window"]["end"]) or runtime["issued"] >= self._config["poll"]["max_polls"]:
                runtime["finished"] = True
                continue
            if now < timestamp(event["window"]["start"]):
                continue
            allowed.append(key)
            if now < runtime["next_due"]:
                continue
            due.append(key)
            if key not in requested:
                continue
            identifier = hashlib.sha256((self.config_revision + ":" + key + ":" + str(runtime["issued"])).encode()).hexdigest()
            jobs.append({"schema_version": 1, "mode": "dry_run", "source": "fixture",
                         "simulated": True, "config_revision": self.config_revision,
                         "job_key": identifier, "event_key": key,
                         "action": "dry_run_check", "parameters_verified": event["verified"],
                         "service_request": False, "submit_enabled": False})
            runtime["issued"] += 1
            runtime["next_due"] = now + dt.timedelta(seconds=self._config["poll"]["changed_poll_seconds"])
        if all(runtime["finished"] for runtime in self._runtime.values()):
            return self._base("finished", "all_events_finished", stop=True)
        next_due = [max(runtime["next_due"], timestamp(self._events[key]["window"]["start"]))
                    for key, runtime in self._runtime.items() if not runtime["finished"]]
        return self._base("ready" if jobs else "waiting", "fixture_checks_due" if jobs else "no_fixture_checks_due",
                          jobs=jobs, allowed_event_keys=allowed, due_event_keys=due,
                          next_due_at=min(next_due).isoformat() if next_due else None)

    def record_event_result(self, event_key, result, now):
        """Apply only a fixture executor's safe scheduling fields.

        Extra monitor fields such as intent are deliberately not stored or
        echoed. A reported session stop is sticky; event stops are independent.
        """
        checked_now, clock_reason = self._time_reason(now)
        if clock_reason:
            return self._base("paused", clock_reason, paused=True, stop=True)
        if self._terminal_reason:
            return self._base("session_ended", self._terminal_reason, paused=True, stop=True)
        try:
            if event_key not in self._events or not isinstance(result, dict):
                raise ConfigError("unmatched result")
            if "event_key" in result and result["event_key"] != event_key:
                raise ConfigError("result event mismatch")
            if not isinstance(result.get("state"), str) or not result["state"] or type(result.get("stop")) is not bool:
                raise ConfigError("missing result state")
            scope = result.get("scope")
            if scope not in ("session", "event", "wait") or (scope == "wait") == result["stop"]:
                raise ConfigError("inconsistent result scope")
            delay = result.get("next_delay_seconds", self._config["poll"]["changed_poll_seconds"])
            positive(delay)
            if result["stop"] and scope == "session":
                self._terminal_reason = "executor_report_stopped"
                return self._base("session_ended", self._terminal_reason, paused=True, stop=True)
            runtime = self._runtime[event_key]
            if result["stop"]:
                runtime["finished"] = True
            elif not runtime["finished"]:
                runtime["next_due"] = checked_now + dt.timedelta(seconds=delay)
        except (ConfigError, TypeError, KeyError, ValueError):
            self._terminal_reason = "invalid_event_result"
            return self._base("session_ended", self._terminal_reason, paused=True, stop=True)
        return self._base("result_recorded", "fixture_result_recorded")
