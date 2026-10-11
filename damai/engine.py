"""Deterministic multi-event state machine; returns plans and never executes UI."""
from __future__ import annotations

import datetime as dt
import copy
import hashlib
import json
import math

from .config import ConfigError, bool_value, keys, nonempty, positive, timestamp, validate_config
from .scheduler import SharedRequestBudget
from .orders import OrderGuard

RISKS = {"captcha", "risk", "permission_denied", "login_failed", "refused", "purchase_limit", "unpaid_constraint"}
PAGE_KINDS = {"detail", "tickets", "confirmation", "pending_payment", "loading", "unknown"}


def decision(event_key, state, reason, stop=True, scope="event", **extra):
    return {"event_key": event_key, "state": state, "reason": reason, "stop": stop, "scope": scope, "simulated": True, "proposed_action": None, "inventory_verified": False, **extra}


class FleetMonitor:
    """Single bounded session, many independent event targets, no background loop.

    expected_serial comes from a local environment variable, never from the repo.
    The fixture serial is deliberately not an actual device identity.
    """
    def __init__(self, config, expected_serial=None):
        self.config = validate_config(copy.deepcopy(config))
        self.expected_serial = expected_serial
        self.events = {event["key"]: event for event in self.config["events"]}
        self.requests = SharedRequestBudget(self.config["poll"])
        self.runtime = {key: {"count": 0, "errors": 0, "stable": 0, "fingerprint": None, "next_at": None, "loading_at": None, "final": None} for key in self.events}
        self.global_final = None
        self.started_at = None
        self.last_sequence = 0
        self.last_captured = None
        self.unpaid_events = set()
        self.submitting_events = set()

    def reserve_submission(self, event_key, now):
        """Reserve a shared fixture-submit slot; None means granted.

        A denied reservation discards the old intent. A later fresh snapshot
        must pass all confirmation checks again before a new reservation.
        This method performs no UI or network operation.
        """
        if self.global_final is not None:
            return copy.deepcopy(self.global_final)
        if event_key not in self.events:
            return self._stop_session(event_key, "unknown_event", "动作没有匹配的配置场次")
        confirmation = self.runtime[event_key]["final"]
        if not confirmation or confirmation["state"] != "confirmation_ready":
            return self._stop_session(event_key, "order_uncertain", "没有新鲜确认上下文；不执行动作")
        if event_key in self.submitting_events:
            return self._stop_session(event_key, "order_uncertain", "该场已有未核实结果的动作许可；停止整轮，不重复执行")
        constraints = self.config["constraints"]
        if not self.config["orders_enabled"] or not self.events[event_key]["verified"] or not constraints["verified"] or constraints["max_unpaid_orders"] is None:
            return self._stop_event(event_key, "constraints_unverified", "执行前的参数、入口或未付款约束未确认")
        if len(self.unpaid_events | self.submitting_events) >= constraints["max_unpaid_orders"]:
            return self._stop_event(event_key, "unpaid_constraint", "待付款和未核实结果的动作已占满容量；不授权新动作")
        try:
            age = (now - timestamp(confirmation["intent"]["observed_at"])).total_seconds()
            if not 0 <= age <= self.config["poll"]["snapshot_ttl_seconds"]:
                raise ConfigError("expired confirmation")
            if confirmation["intent"]["submit_enabled"] is not True or OrderGuard.validate_intent(self.events[event_key], confirmation["intent"], now):
                raise ConfigError("confirmation parameters changed")
            slot = self.requests.reserve(event_key, now)
        except (ConfigError, TypeError, AttributeError):
            return self._stop_session(event_key, "order_uncertain", "动作时间或确认上下文无效；停止整轮")
        if slot["granted"]:
            self.submitting_events.add(event_key)
            return None
        self.runtime[event_key]["final"] = None
        return self._wait(event_key, "shared_rate_limited", "其他场占用总请求预算；等待新页面重新确认，不复用旧动作", now, slot["delay_seconds"])

    def _stop_session(self, key, state, reason):
        self.global_final = decision(key, state, reason, scope="session")
        return copy.deepcopy(self.global_final)

    def _stop_event(self, key, state, reason, **extra):
        result = decision(key, state, reason, **extra)
        self.runtime[key]["final"] = copy.deepcopy(result)
        return result

    def _wait(self, key, state, reason, now, delay, action=None, **extra):
        self.runtime[key]["next_at"] = now + dt.timedelta(seconds=delay)
        return decision(key, state, reason, False, "wait", next_delay_seconds=delay, proposed_action=action, **extra)

    def record_order_result(self, event_key, result, now):
        """Accept a guarded adapter result. No submission happens in this method."""
        if event_key not in self.events:
            return self._stop_session(event_key, "unknown_event", "订单结果没有匹配的配置场次")
        if self.global_final is not None:
            return copy.deepcopy(self.global_final)
        state = result.get("state") if isinstance(result, dict) else None
        if state == "pending_payment":
            try:
                event = self.events[event_key]
                confirmation = self.runtime[event_key]["final"]
                if not isinstance(now, dt.datetime) or now.tzinfo is None:
                    raise ConfigError("result clock missing")
                if not confirmation or confirmation["state"] != "confirmation_ready":
                    raise ConfigError("no verified confirmation intent")
                capacity = self.config["constraints"]["max_unpaid_orders"]
                if event_key not in self.submitting_events or capacity is None or len(self.unpaid_events | self.submitting_events) > capacity:
                    raise ConfigError("no reserved capacity for result")
                intent = confirmation["intent"]
                if intent["submit_enabled"] is not True or OrderGuard.validate_intent(event, intent, now):
                    raise ConfigError("confirmation parameters changed")
                age = (now - timestamp(intent["observed_at"])).total_seconds()
                if not 0 <= age <= self.config["poll"]["snapshot_ttl_seconds"]:
                    raise ConfigError("confirmation expired")
                if result.get("event_key") != event_key or result.get("simulated") is not True or result.get("scope") != "event":
                    raise ConfigError("result identity mismatch")
                nonempty(result["order_ref"])
                positive(result["amount_minor"])
                if result["amount_minor"] != intent["total_minor"] or result["amount_minor"] > event["budget_minor"]:
                    raise ConfigError("result amount mismatch")
                if not now < timestamp(result["deadline"]) < timestamp(event["target"]["starts_at"]):
                    raise ConfigError("payment deadline expired or beyond performance")
            except (KeyError, ConfigError, TypeError, AttributeError):
                return self._stop_session(event_key, "order_uncertain", "待付款结果与该场新鲜确认上下文、含费金额或截止时间不匹配；不重试")
            self.submitting_events.discard(event_key)
            self.unpaid_events.add(event_key)
            return self._stop_event(event_key, state, "该场已到模拟待付款状态；立即停该场，不调用支付", amount_minor=result["amount_minor"], deadline=result["deadline"])
        if state in ("uncertain", "duplicate_order"):
            return self._stop_session(event_key, "order_uncertain", "提交结果不明确或已有订单锁；停止整轮，不重试")
        if state in ("rejected", "captcha"):
            return self._stop_session(event_key, state, "提交后出现拒绝或验证；暂停整轮，由用户核对")
        if state in ("invalid_intent", "unsupported_live", "disabled"):
            self.submitting_events.discard(event_key)
            return self._stop_event(event_key, state, "订单未继续；停止该场并核对原因")
        return self._stop_session(event_key, "order_uncertain", "订单结果无法核实；停止整轮，不重试")

    def step(self, snapshot, now):
        if not isinstance(now, dt.datetime) or now.tzinfo is None:
            raise ConfigError("now must include timezone")
        if self.global_final is not None:
            return copy.deepcopy(self.global_final)
        snapshot = copy.deepcopy(snapshot)
        key = snapshot.get("event_key") if isinstance(snapshot, dict) else None
        try:
            keys(snapshot, {"schema_version", "source", "captured_at", "heartbeat_at", "sequence", "event_key", "evidence_ref", "profile_id", "device", "app", "page", "flags", "read_error"})
            if type(snapshot["schema_version"]) is not int or snapshot["schema_version"] != 1 or snapshot["source"] not in ("fixture", "cua_repl"):
                raise ConfigError("unknown observation schema/source")
            if key not in self.events:
                return self._stop_session(key, "unknown_event", "观测没有匹配的配置场次")
            nonempty(snapshot["evidence_ref"])
            sequence = positive(snapshot["sequence"])
            captured, heartbeat = timestamp(snapshot["captured_at"]), timestamp(snapshot["heartbeat_at"])
            policy = self.config["poll"]
            if not 0 <= (now - heartbeat).total_seconds() <= policy["heartbeat_timeout_seconds"]:
                return self._stop_session(key, "heartbeat_stale", "Mac 执行端心跳失效；暂停整轮")
            if not 0 <= (now - captured).total_seconds() <= policy["snapshot_ttl_seconds"]:
                return self._stop_session(key, "stale_observation", "页面观測过期或时间在未来；停止整轮")
            if sequence <= self.last_sequence or (self.last_captured is not None and captured <= self.last_captured):
                return self._stop_session(key, "replayed_observation", "旧快照或重复序号不能当作实时轮询")
            device = snapshot["device"]
            keys(device, {"serial", "manufacturer", "model", "transport", "state", "screen_on", "locked"})
            wanted = "fixture-vivo" if snapshot["source"] == "fixture" else self.expected_serial
            if not wanted or device["serial"] != wanted or any(device[field] != self.config["device"][field] for field in ("manufacturer", "model", "transport")) or device["serial"].startswith("emulator-"):
                return self._stop_session(key, "wrong_device", "仅允许配置绑定的 vivo USB 真机；不选择模拟器")
            if device["state"] != "device":
                return self._stop_session(key, "device_offline", "设备离线、未授权或断连；暂停整轮，不自动连接")
            bool_value(device["locked"])
            bool_value(device["screen_on"])
            if device["locked"] or not device["screen_on"]:
                return self._stop_session(key, "screen_blocked", "手机锁屏或息屏；由用户正常处理")
            app = snapshot["app"]
            keys(app, {"package", "version", "login"})
            if app["package"] != self.config["profile"]["package"]:
                return self._stop_session(key, "wrong_app", "当前页面不是待验证的国内大麦 App")
            nonempty(app["version"])
            if snapshot["source"] == "cua_repl":
                profile = self.config["profile"]
                if not profile["verified"] or app["version"] != profile["version"] or snapshot["profile_id"] != profile["id"]:
                    return self._stop_session(key, "profile_unverified", "包名、版本和真机页面映射尚未校准")
                if not policy["service_frequency_verified"]:
                    return self._stop_session(key, "service_limits_unverified", "真实服务频率限制尚未核实；不启动监控")
            elif snapshot["profile_id"] != "offline-v1":
                raise ConfigError("fixture profile mismatch")
            if not isinstance(snapshot["flags"], list) or any(flag not in RISKS for flag in snapshot["flags"]):
                raise ConfigError("unrecognized safety signal")
            if snapshot["flags"]:
                return self._stop_session(key, sorted(snapshot["flags"])[0], "验证码、风控、拒绝、限购或未付款限制；暂停整轮")
            if app["login"] != "logged_in":
                return self._stop_session(key, "login_required", "登录未知或未登录；停止，不操作账号")
            if snapshot["read_error"] not in (None, "temporary_capture", "temporary_network"):
                raise ConfigError("unrecognized read error")
            page = snapshot["page"]
            keys(page, {"kind", "ready", "target", "tiers", "selection", "pending"})
            bool_value(page["ready"])
            if page["kind"] not in PAGE_KINDS or page["kind"] == "unknown":
                return self._stop_session(key, "unknown_page", "未知页面或控件；停止，不盲点")
        except (ConfigError, TypeError, KeyError, ValueError, AttributeError):
            return self._stop_session(key, "invalid_observation", "观测字段缺失、类型错误或内容冲突；停止")
        self.last_sequence, self.last_captured = sequence, captured
        if self.started_at is None:
            self.started_at = now
        if (now - self.started_at).total_seconds() >= policy["max_session_seconds"]:
            return self._stop_session(key, "session_limit", "已到本轮会话时长上限")
        runtime, event = self.runtime[key], self.events[key]
        if runtime["final"] is not None:
            return copy.deepcopy(runtime["final"])
        if now < timestamp(event["window"]["start"]):
            return self._wait(key, "before_window", "尚未到该场开始窗口", now, math.ceil((timestamp(event["window"]["start"]) - now).total_seconds()))
        if now >= timestamp(event["window"]["end"]):
            return self._stop_event(key, "window_ended", "已到该场截止时间，其他场次可继续")
        if runtime["count"] >= policy["max_polls"]:
            return self._stop_event(key, "poll_limit", "该场达到检查次数上限")
        if runtime["next_at"] is not None and now < runtime["next_at"]:
            return decision(key, "rate_limited", "未到下一次就绪检查；不刷新、不执行动作", False, "wait", next_delay_seconds=math.ceil((runtime["next_at"] - now).total_seconds()))
        runtime["count"] += 1
        if snapshot["read_error"]:
            return self._read_failure(key, now)
        if not page["ready"] or page["kind"] == "loading":
            if runtime["loading_at"] is None:
                runtime["loading_at"] = now
            if (now - runtime["loading_at"]).total_seconds() >= policy["max_loading_seconds"]:
                runtime["loading_at"] = None
                return self._read_failure(key, now)
            return self._wait(key, "awaiting_page", "等待页面加载完成；只检查就绪，不刷新或点击", now, policy["readiness_check_seconds"])
        runtime["loading_at"] = None
        try:
            result = self._classify_ready(key, page, now)
        except (ConfigError, TypeError, KeyError, ValueError):
            return self._stop_session(key, "invalid_page", "页面字段缺失、类型错误或库存控件冲突；停止")
        if result["stop"]:
            if result["scope"] == "session":
                self.global_final = copy.deepcopy(result)
            if "intent" in result:
                result["intent"]["observed_at"] = snapshot["captured_at"]
            runtime["final"] = copy.deepcopy(result)
            return result
        runtime["errors"] = 0
        fingerprint = hashlib.sha256(json.dumps(page, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        runtime["stable"] = runtime["stable"] + 1 if runtime["fingerprint"] == fingerprint else 0
        runtime["fingerprint"] = fingerprint
        floor = max(policy["local_minimum_seconds"], policy["service_minimum_seconds"] or 0)
        delay = max(floor, min(policy["stable_max_seconds"], policy["stable_poll_seconds"] * (2 ** min(runtime["stable"], 20)))) if runtime["stable"] else max(floor, policy["changed_poll_seconds"])
        if result["state"] == "inventory_signal":
            delay = max(floor, policy["changed_poll_seconds"])
        runtime["next_at"] = now + dt.timedelta(seconds=delay)
        return {**result, "next_delay_seconds": delay}

    def _read_failure(self, key, now):
        runtime, policy = self.runtime[key], self.config["poll"]
        runtime["errors"] += 1
        if runtime["errors"] >= policy["max_read_errors"]:
            return self._stop_event(key, "read_error_limit", "该场连续读取或加载失败达到上限")
        floor = max(policy["local_minimum_seconds"], policy["changed_poll_seconds"], policy["service_minimum_seconds"] or 0)
        delay = min(policy["max_backoff_seconds"], floor * (2 ** runtime["errors"]))
        return self._wait(key, "read_backoff", "临时读失败；固定指数退避，不随机伪装", now, delay)

    def _classify_ready(self, key, page, now):
        event = self.events[key]
        target = page["target"]
        keys(target, {"item_id", "name", "city", "starts_at"})
        if any(target[field] != event["target"][field] for field in ("item_id", "name", "city")) or timestamp(target["starts_at"]) != timestamp(event["target"]["starts_at"]):
            return self._page_problem(key, page, "wrong_target", "项目、演出名、城市或精确场次时间不匹配")
        if not isinstance(page["tiers"], list):
            raise ConfigError("tiers missing")
        tiers = {}
        for tier in page["tiers"]:
            keys(tier, {"price_minor", "stock", "enabled", "available_quantity"})
            price = positive(tier["price_minor"])
            bool_value(tier["enabled"])
            if price in tiers or tier["stock"] not in ("available", "sold_out", "unknown") or (tier["stock"] == "sold_out" and tier["enabled"]):
                raise ConfigError("conflicting tier controls")
            if tier["available_quantity"] is not None:
                positive(tier["available_quantity"], 0)
            tiers[price] = tier
        selection = page["selection"]
        if selection is not None:
            keys(selection, {"tier_minor", "quantity", "spectator_refs", "total_minor", "fees_minor", "adjacent", "submit_enabled"})
            for field in ("tier_minor", "quantity", "total_minor"):
                positive(selection[field])
            positive(selection["fees_minor"], 0)
            bool_value(selection["submit_enabled"])
            if selection["adjacent"] is not None:
                bool_value(selection["adjacent"])
            if not isinstance(selection["spectator_refs"], list) or any(not isinstance(ref, str) or not ref for ref in selection["spectator_refs"]):
                raise ConfigError("invalid spectator selection")
            if selection["tier_minor"] not in event["tiers"] or selection["quantity"] != event["quantity"] or selection["total_minor"] != selection["tier_minor"] * selection["quantity"] + selection["fees_minor"]:
                return self._page_problem(key, page, "selection_mismatch", "已选票档、张数或含费总额不匹配")
            if selection["total_minor"] > event["budget_minor"]:
                return self._page_problem(key, page, "over_budget", "含费总额超过该场预算；停止")
            if event["seating"] == "required" and selection["adjacent"] is not True:
                return self._page_problem(key, page, "adjacent_unverified", "要求连坐，但页面没有明确确认连坐")
        if page["kind"] in ("confirmation", "pending_payment") and selection is None:
            raise ConfigError("order page lacks selection")
        if page["kind"] in ("confirmation", "pending_payment"):
            if not event["verified"]:
                return self._page_problem(key, page, "parameters_unverified", "该场日期、票档、观演人和预算仍待正式确认")
            if selection["spectator_refs"] != event["spectator_refs"] or len(set(selection["spectator_refs"])) != event["quantity"]:
                return self._page_problem(key, page, "spectators_mismatch", "观演人选择缺失或与确认配置不匹配")
        if page["kind"] == "pending_payment":
            pending = page["pending"]
            keys(pending, {"order_ref", "amount_minor", "deadline"})
            nonempty(pending["order_ref"])
            positive(pending["amount_minor"])
            if pending["amount_minor"] != selection["total_minor"] or not now < timestamp(pending["deadline"]) < timestamp(event["target"]["starts_at"]):
                return decision(key, "order_uncertain", "待付款金额或截止时间无法确认；停止整轮", scope="session")
            self.unpaid_events.add(key)
            return decision(key, "pending_payment", "该场已到模拟待付款页面；立即停该场，不支付", amount_minor=pending["amount_minor"], deadline=pending["deadline"], adjacent_verified=selection["adjacent"] is True)
        if page["pending"] is not None:
            raise ConfigError("unexpected pending-order fields")
        if page["kind"] == "confirmation":
            if not selection["submit_enabled"]:
                return decision(key, "submit_disabled", "提交控件被禁用；停止，不重试")
            constraints = self.config["constraints"]
            if not constraints["verified"] or constraints["max_unpaid_orders"] is None:
                return decision(key, "constraints_unverified", "真实限购和未付款约束待核实")
            if len(self.unpaid_events | self.submitting_events) >= constraints["max_unpaid_orders"]:
                return decision(key, "unpaid_constraint", "达到已确认的未付款订单数量限制")
            if not self.config["orders_enabled"]:
                return decision(key, "orders_disabled", "确认页条件完整，但提交入口默认禁用")
            return decision(key, "confirmation_ready", "离线模拟确认页已核对；可交给幂等保护框架", proposed_action="simulate_pending_order", intent={**target, **selection, "observed_at": now.isoformat()})
        available = []
        for price in event["tiers"]:
            tier = tiers.get(price)
            if tier and tier["stock"] == "available" and tier["enabled"]:
                if tier["available_quantity"] is None:
                    return decision(key, "quantity_unverified", "有票提示不能证明可选目标张数")
                if tier["available_quantity"] >= event["quantity"] and price * event["quantity"] <= event["budget_minor"]:
                    available.append(price)
        if available:
            if event["strategy"] == "lowest":
                price = min(available)
            elif event["strategy"] == "any":
                price = next(tier["price_minor"] for tier in page["tiers"] if tier["price_minor"] in available)
            else:
                price = available[0]
            return decision(key, "inventory_signal", "目标票档可选信号；未锁定票，费用与连坐仍须确认", False, "wait", proposed_action="review_selection", price_minor=price, adjacent_verified=False)
        if all(price in tiers and tiers[price]["stock"] == "sold_out" for price in event["tiers"]):
            return decision(key, "sold_out", "该场所有候选票档显示缺货", False, "wait")
        if any(price in tiers and tiers[price]["stock"] == "unknown" for price in event["tiers"]) or any(price not in tiers for price in event["tiers"]):
            return decision(key, "inventory_unknown", "票档信息缺失或未知；停止，不猜测库存")
        return decision(key, "no_eligible_tier", "当前没有满足张数和预算的候选票档", False, "wait")

    @staticmethod
    def _page_problem(key, page, state, reason):
        if page["kind"] == "pending_payment":
            return decision(key, "order_uncertain", reason + "；已有待付款页，停止整轮核对，不重试", scope="session")
        return decision(key, state, reason)
