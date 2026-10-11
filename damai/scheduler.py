"""One deterministic request budget shared by all events on one device.

Only service-affecting operations (refresh, navigation, or submission) call
reserve.  Reading already captured page-readiness metadata does not call this
module and does not consume the budget.  This class executes no operation,
starts no loop, and does not claim that its local floor is a service limit.
"""

import math
import threading
from datetime import datetime, timedelta, timezone

from .config import ConfigError


class SharedRequestBudget:
    """Reserve at most one request across all events during each floor window.

    A reservation is conservative: once granted it is consumed even if the
    caller's operation fails.  Denied requests do not postpone the next slot.
    Clock reversal is a configuration/error stop, rather than permission to
    issue a request.  This budget is scoped to one bounded local session.
    """

    def __init__(self, policy):
        if not isinstance(policy, dict):
            raise ConfigError("request policy must be a dictionary")
        local = policy.get("local_minimum_seconds")
        service = policy.get("service_minimum_seconds")
        if type(local) is not int or local < 5:
            raise ConfigError("local request floor must be an integer of at least five seconds")
        if service is not None and (type(service) is not int or service <= 0):
            raise ConfigError("service request floor must be a positive integer or null")
        self.floor_seconds = max(local, service or 0)
        self._last_seen = None
        self._next_available = None
        self._lock = threading.Lock()

    def reserve(self, event_key, now):
        """Return a grant or the rounded-up seconds until a shared slot.

        All inputs are checked before state changes.  Datetimes must include
        their timezone.  The same absolute timestamp cannot obtain another
        reservation by changing event keys or timezone representation.
        """
        if not isinstance(event_key, str) or not event_key.strip():
            raise ConfigError("request event key must be nonempty text")
        if not isinstance(now, datetime) or now.tzinfo is None or now.utcoffset() is None:
            raise ConfigError("request clock must include timezone")
        current = now.astimezone(timezone.utc)
        with self._lock:
            if self._last_seen is not None and current < self._last_seen:
                raise ConfigError("request clock moved backward; stop the session")
            if self._next_available is not None and current < self._next_available:
                self._last_seen = current
                delay = math.ceil((self._next_available - current).total_seconds())
                return {"granted": False, "delay_seconds": delay}
            # Equality is explicitly denied even if future implementations add
            # checks that observe a slot without reserving it.
            if self._last_seen is not None and current == self._last_seen:
                return {"granted": False, "delay_seconds": self.floor_seconds}
            self._last_seen = current
            self._next_available = current + timedelta(seconds=self.floor_seconds)
            return {"granted": True, "delay_seconds": 0}
