"""Deterministic, local notification simulation with no network operations."""

import hashlib
import json
import os
from pathlib import Path


DINGTALK_WEBHOOK_ENV = "DAMAI_DINGTALK_WEBHOOK"
# This is only the future configuration name.  Never read or persist its value.


def simulated_notification(result):
    """Return only the approved outcome fields, plus a stable deduplication ID."""
    if not isinstance(result, dict):
        raise ValueError("notification result must be a dictionary")
    payload = {}
    for key in ("event_key", "state", "reason", "deadline"):
        value = result.get(key)
        if isinstance(value, str):
            payload[key] = value
    value = result.get("amount_minor")
    if type(value) is int and value >= 0:
        payload["amount_minor"] = value
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"),
                         ensure_ascii=False).encode("utf-8")
    return {"mode": "simulated", "dedupe_id": hashlib.sha256(encoded).hexdigest(),
            "payload": payload}


class SimulatedOutbox:
    """Persist at most one sanitized local record for each simulated message."""

    def __init__(self, outbox_dir):
        self.outbox_dir = Path(outbox_dir)

    def emit(self, result):
        message = simulated_notification(result)
        self.outbox_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        path = self.outbox_dir / (message["dedupe_id"] + ".json")
        try:
            descriptor = os.open(str(path), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError:
            return dict(message, emitted=False)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(message, handle, sort_keys=True, ensure_ascii=False)
            handle.flush()
            os.fsync(handle.fileno())
        return dict(message, emitted=True)
