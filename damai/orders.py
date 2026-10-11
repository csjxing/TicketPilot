"""Fixture-only pending-order validation and durable duplicate protection.

This module deliberately exposes no payment operation.  A real UI submission
adapter is not implemented: explicitly enabled live attempts raise
UnsupportedLive before touching an adapter or creating a journal.
"""

import copy
import hashlib
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path


class UnsupportedLive(RuntimeError):
    """The first version cannot submit a real order."""


def _digest(value):
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"),
                         ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _timestamp(value):
    if not isinstance(value, str) or not value:
        raise ValueError("timestamp must be an ISO string")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("timestamp needs a timezone")
    return parsed.astimezone(timezone.utc)


def _minor(value):
    return type(value) is int and value >= 0


def _text(value):
    return isinstance(value, str) and bool(value.strip())


def _prices(tiers):
    if not isinstance(tiers, list) or not tiers:
        return []
    prices = []
    for tier in tiers:
        price = tier.get("price_minor") if isinstance(tier, dict) else tier
        if not _minor(price) or price == 0:
            return []
        prices.append(price)
    return prices


class OrderGuard:
    """Validate one event and atomically claim its fixture submission.

    All claims remain on disk, including rejected or uncertain attempts.  There
    is no automatic reset or retry.  Locks cover both an event key and a target
    identity, so renaming an event or changing its configuration cannot bypass
    an earlier attempt.  Journal files contain only hashes and outcome fields,
    never the device identity or spectator references.
    """

    def __init__(self, journal_dir):
        self.journal_dir = Path(journal_dir)

    def attempt(self, event, intent, adapter, now, enabled=False):
        key = event.get("key") if isinstance(event, dict) else None
        base = {"event_key": key if isinstance(key, str) else "",
                "state": "invalid_intent"}
        if enabled is not True or not isinstance(intent, dict) or intent.get("submit_enabled") is not True:
            return dict(base, state="disabled", reason="submission_disabled")
        if getattr(adapter, "mode", None) != "fixture":
            raise UnsupportedLive("Live order submission has no verified UI adapter; only fixture mode is supported.")
        reason = self.validate_intent(event, intent, now)
        if reason:
            return dict(base, reason=reason)

        event_path = self.journal_dir / ("event-" + _digest(key) + ".json")
        target_digest = _digest({"item_id": event["target"]["item_id"].strip(),
                                 "starts_at": _timestamp(event["target"]["starts_at"]).isoformat()})
        # Display-name aliases and equivalent timezone representations cannot
        # bypass the same item/performance identity.
        target_path = self.journal_dir / ("target-" + target_digest + ".json")
        record = {"schema": 1, "event_key": key, "target_digest": target_digest,
                  "intent_digest": _digest(intent), "state": "submitting",
                  "reason": "submission_claimed", "created_at": now.isoformat()}
        try:
            self.journal_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
            if not self._claim(event_path, record):
                return self._duplicate(base, event_path, "event_already_attempted")
            if not self._claim(target_path, record):
                result = self._duplicate(base, target_path, "target_already_attempted")
                self._save(event_path, dict(record, **result))
                return result
        except OSError:
            # No adapter call has happened, and any partial lock is retained.
            return dict(base, state="uncertain", reason="journal_io_error")

        # Both O_EXCL claim files have been fsynced before the only adapter call.
        try:
            raw_result = adapter.submit_pending(copy.deepcopy(intent))
        except Exception:
            result = dict(base, state="uncertain", reason="submission_exception")
        else:
            result = self._outcome(base, event, intent, raw_result, now)

        try:
            self._save(event_path, dict(record, **result))
            self._save(target_path, dict(record, **result))
        except OSError:
            # Claim files still prevent a second call if an outcome write fails.
            return dict(base, state="uncertain", reason="outcome_journal_io_error")
        return result

    @staticmethod
    def validate_intent(event, intent, now):
        if not isinstance(event, dict) or not _text(event.get("key")):
            return "invalid_event_key"
        if not isinstance(now, datetime) or now.tzinfo is None or now.utcoffset() is None:
            return "invalid_now"
        if event.get("verified") is not True:
            return "parameters_not_verified"
        target = event.get("target")
        if not isinstance(target, dict):
            return "invalid_target"
        for field in ("item_id", "name", "city", "starts_at"):
            if not _text(target.get(field)) or intent.get(field) != target[field]:
                return "target_mismatch"
        try:
            starts_at = _timestamp(target["starts_at"])
            observed_at = _timestamp(intent.get("observed_at"))
        except (ValueError, TypeError):
            return "invalid_timestamp"
        age = (now - observed_at).total_seconds()
        if age < 0 or age > 30:
            return "stale_observation"
        if starts_at <= now:
            return "event_already_started"
        window = event.get("window")
        if not isinstance(window, dict):
            return "invalid_time_window"
        try:
            window_start = _timestamp(window.get("start"))
            window_end = _timestamp(window.get("end"))
        except (ValueError, TypeError):
            return "invalid_time_window"
        if window_start >= window_end or window_end > starts_at:
            return "invalid_time_window"
        if not window_start <= now < window_end:
            return "outside_time_window"
        quantity = event.get("quantity")
        if type(quantity) is not int or quantity <= 0 or intent.get("quantity") != quantity or type(intent.get("quantity")) is not int:
            return "quantity_mismatch"
        refs = event.get("spectator_refs")
        if not isinstance(refs, list) or len(refs) != quantity or any(not _text(ref) for ref in refs) or len(set(refs)) != quantity:
            return "invalid_spectator_refs"
        if intent.get("spectator_refs") != refs:
            return "spectator_mismatch"
        tier = intent.get("tier_minor")
        if not _minor(tier) or tier not in _prices(event.get("tiers")):
            return "tier_mismatch"
        total, fees, budget = intent.get("total_minor"), intent.get("fees_minor"), event.get("budget_minor")
        if not all(_minor(value) for value in (total, fees, budget)):
            return "invalid_amount"
        if tier * quantity + fees != total:
            return "amount_mismatch"
        if total > budget:
            return "over_budget"
        adjacent = intent.get("adjacent")
        if adjacent is not None and type(adjacent) is not bool:
            return "invalid_adjacent"
        seating = event.get("seating")
        if seating not in ("required", "preferred"):
            return "invalid_seating_policy"
        if seating == "required" and adjacent is not True:
            return "adjacent_not_confirmed"
        return None

    @staticmethod
    def _outcome(base, event, intent, raw, now):
        if not isinstance(raw, dict):
            return dict(base, state="uncertain", reason="missing_submit_result")
        status = raw.get("status")
        if status == "uncertain":
            return dict(base, state="uncertain", reason="submit_result_uncertain")
        if status in ("rejected", "captcha"):
            return dict(base, state=status, reason="submission_" + status)
        if status != "pending_payment":
            return dict(base, state="uncertain", reason="unknown_submit_result")
        amount = raw.get("amount_minor")
        if not _minor(amount) or amount != intent["total_minor"]:
            return dict(base, state="uncertain", reason="result_amount_mismatch")
        if not _text(raw.get("order_ref")):
            return dict(base, state="uncertain", reason="missing_order_reference")
        try:
            deadline = _timestamp(raw.get("deadline"))
            starts_at = _timestamp(event["target"]["starts_at"])
        except (ValueError, TypeError):
            return dict(base, state="uncertain", reason="invalid_payment_deadline")
        if deadline <= now or deadline >= starts_at:
            return dict(base, state="uncertain", reason="invalid_payment_deadline")
        if "expected_deadline" in intent and raw["deadline"] != intent["expected_deadline"]:
            return dict(base, state="uncertain", reason="result_deadline_mismatch")
        return dict(base, state="pending_payment", reason="pending_order_created",
                    order_ref=raw["order_ref"], amount_minor=amount,
                    deadline=deadline.isoformat(), simulated=True, scope="event")

    @staticmethod
    def _claim(path, record):
        try:
            descriptor = os.open(str(path), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError:
            return False
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(record, handle, sort_keys=True, ensure_ascii=False)
            handle.flush()
            os.fsync(handle.fileno())
        OrderGuard._sync_directory(path.parent)
        return True

    @staticmethod
    def _save(path, record):
        descriptor, temp_path = tempfile.mkstemp(prefix=".journal-", dir=str(path.parent))
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
                json.dump(record, handle, sort_keys=True, ensure_ascii=False)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_path, str(path))
            OrderGuard._sync_directory(path.parent)
        finally:
            if os.path.exists(temp_path):
                os.unlink(temp_path)

    @staticmethod
    def _sync_directory(directory):
        descriptor = os.open(str(directory), os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    @staticmethod
    def _duplicate(base, path, reason):
        try:
            with path.open(encoding="utf-8") as handle:
                previous = json.load(handle)
            if not isinstance(previous, dict) or not _text(previous.get("state")):
                raise ValueError("invalid record")
        except (OSError, ValueError):
            return dict(base, state="uncertain", reason="unreadable_existing_journal")
        return dict(base, state="duplicate_order", reason=reason,
                    previous_state=previous["state"])
