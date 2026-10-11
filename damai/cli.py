"""Bounded local rehearsal and explicit read-only preflight; no live monitor."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import tempfile

from .adapters import FixtureOrderAdapter, preflight
from .config import ConfigError, keys, load_config, load_json, timestamp
from .coordination import LocalCoordinator
from .engine import FleetMonitor
from .notifications import SimulatedOutbox, simulated_notification
from .orders import OrderGuard, UnsupportedLive

ROOT = Path(__file__).resolve().parent.parent


def emit(result):
    # Never print the internal intent, spectator refs, raw device or page data.
    safe = {key: value for key, value in result.items() if key in {"event_key", "state", "reason", "stop", "scope", "simulated", "next_delay_seconds", "proposed_action", "inventory_verified", "price_minor", "adjacent_verified", "amount_minor", "deadline", "mode", "ui_adapter_available", "identity_verified", "screen_on", "locked", "candidate_app_installed", "login_verified"}}
    print(json.dumps(safe, ensure_ascii=False, sort_keys=True))


def run_fixture(config, data, runtime_dir, simulate_orders=False):
    keys(data, {"observations"}, {"order_results"})
    snapshots = data["observations"]
    if not isinstance(snapshots, list) or not snapshots:
        raise ConfigError("fixture observations must be nonempty")
    results = data.get("order_results", {})
    if not isinstance(results, dict):
        raise ConfigError("order results must be a fixture mapping")
    if any(not isinstance(snapshot, dict) or snapshot.get("source") != "fixture" for snapshot in snapshots):
        raise ConfigError("fixture replay cannot contain real device observations")
    coordinator = LocalCoordinator(config, "fixture-mac", timestamp(snapshots[0]["captured_at"]))
    print(json.dumps({"coordination": coordinator.envelope()}, ensure_ascii=False, sort_keys=True))
    monitor = FleetMonitor(coordinator.execution_config())
    guard = OrderGuard(Path(runtime_dir) / "orders")
    outbox = SimulatedOutbox(Path(runtime_dir) / "notifications")
    for snapshot in snapshots:
        now = timestamp(snapshot["captured_at"])
        device = snapshot.get("device")
        if not isinstance(device, dict):
            device = {}
        connected = device.get("state") == "device"
        health = coordinator.accept_heartbeat({
            "schema_version": 1, "mode": "dry_run", "source": "fixture",
            "config_revision": coordinator.config_revision, "executor_key": coordinator.executor_key,
            "sequence": snapshot["sequence"], "sent_at": snapshot["heartbeat_at"],
            "executor_state": "connected" if connected else "disconnected",
            "device": {"identity_verified": all(device.get(field) == value for field, value in
                {"serial": "fixture-vivo", "manufacturer": "vivo", "model": "V2547A", "transport": "usb"}.items()),
                "connected": connected, "locked": device.get("locked"), "screen_on": device.get("screen_on")},
            "capabilities": {"ui_observation": "available"},  # Synthetic fixture capability only.
        }, now)
        plan = coordinator.plan(now, event_keys=[snapshot["event_key"]])
        # Even an unscheduled captured fixture is classified for risk signals.
        # No device/service request occurs merely by consuming fixture metadata.
        result = monitor.step(snapshot, now)
        if (health["paused"] or plan["paused"]) and not (result["stop"] and result["scope"] == "session"):
            result = monitor._stop_session(snapshot["event_key"], "executor_paused", "本地协调模拟没有有效执行端许可；暂停整轮")
        if result["state"] == "confirmation_ready" and simulate_orders:
            key = snapshot["event_key"]
            has_job = any(job["event_key"] == key and job["config_revision"] == coordinator.config_revision
                          and job["source"] == "fixture" and job["action"] == "dry_run_check"
                          for job in plan["jobs"])
            limited = monitor.reserve_submission(key, now) if has_job else monitor._stop_session(
                key, "job_unavailable", "没有该场当前配置版本的离线任务许可；暂停整轮")
            if limited is not None:
                result = limited
            else:
                # Preserve evidence time; never relabel an older snapshot as fresh.
                intent = {**result["intent"], "observed_at": snapshot["captured_at"]}
                outcome = guard.attempt(monitor.events[key], intent, FixtureOrderAdapter(results.get(key)), now, enabled=config["orders_enabled"])
                result = monitor.record_order_result(key, outcome, now)
        recorded = coordinator.record_event_result(snapshot["event_key"], result, now)
        if recorded["paused"] and not (result["stop"] and result["scope"] == "session"):
            result = monitor._stop_session(snapshot["event_key"], "executor_paused", "协调结果合约不一致；暂停整轮")
        emit(result)
        if result["stop"]:
            notification = outbox.emit(result)
            print(json.dumps({"notification": notification}, ensure_ascii=False, sort_keys=True))
        if result["scope"] == "session" and result["stop"]:
            break
    return monitor


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--demo", action="store_true", help="synthetic two-event rehearsal in virtual time")
    group.add_argument("--fixture", type=Path, help="replay synthetic snapshots without sleeping")
    group.add_argument("--observation", type=Path, help="classify one permitted-tool snapshot only")
    group.add_argument("--check-config", action="store_true")
    group.add_argument("--preflight", action="store_true", help="explicit read-only check of an existing ADB server")
    parser.add_argument("--config", type=Path)
    parser.add_argument("--simulate-orders", action="store_true", help="fixture results only; never enables live orders")
    parser.add_argument("--runtime-dir", type=Path, help="local durable fixture journals; retain to test duplicate prevention")
    args = parser.parse_args(argv)
    if args.simulate_orders and not (args.demo or args.fixture):
        parser.error("--simulate-orders is only available with --demo or --fixture")
    if args.demo and args.config:
        parser.error("--demo always uses synthetic configuration; choose --fixture for custom input")
    try:
        if args.preflight:
            emit(preflight(os.environ.get("DAMAI_DEVICE_SERIAL")))
            return 0
        if args.demo:
            config_path = ROOT / "tests/fixtures/demo-config.json"
            fixture_path = ROOT / "tests/fixtures/demo-observations.json"
        else:
            if args.config is None:
                parser.error("--config is required outside --demo and --preflight")
            config_path, fixture_path = args.config, args.fixture
        config = load_config(config_path)
        if args.check_config:
            emit({"state": "config_valid", "reason": "Configuration syntax verified; no device, UI, scheduling or submission started.", "stop": True, "simulated": True})
        elif args.observation:
            monitor = FleetMonitor(config, expected_serial=os.environ.get(config["device"]["serial_env"]))
            result = monitor.step(load_json(args.observation), dt.datetime.now(dt.timezone.utc))
            emit(result)
            print(json.dumps({"notification": simulated_notification(result)}, ensure_ascii=False, sort_keys=True))
        else:
            data = load_json(fixture_path)
            if args.runtime_dir:
                run_fixture(config, data, args.runtime_dir, args.simulate_orders)
            else:
                with tempfile.TemporaryDirectory(prefix="damai-rehearsal-") as runtime:
                    run_fixture(config, data, runtime, args.simulate_orders)
        return 0
    except (ConfigError, KeyError, TypeError, ValueError, OSError, UnsupportedLive):
        emit({"state": "input_or_adapter_blocked", "reason": "Invalid configuration/input, unavailable adapter or journal failure; no live action was performed.", "stop": True, "scope": "session", "simulated": True})
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
