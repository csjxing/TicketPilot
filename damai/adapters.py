"""Explicit capability boundaries. No taps, app launch, login or payments."""
from __future__ import annotations

import copy
import re
import socket

from .orders import UnsupportedLive


class LiveUIAdapter:
    mode = "live"
    available = False

    def observe(self):
        raise UnsupportedLive("No verified adapter for the permitted UI tool and real Damai pages exists yet.")

    def submit_pending(self, intent):
        raise UnsupportedLive("Live submission is unsupported; verify the permitted UI adapter first.")


class FixtureOrderAdapter:
    mode = "fixture"

    def __init__(self, result):
        self.result = copy.deepcopy(result)
        self.calls = 0

    def submit_pending(self, intent):
        self.calls += 1
        return copy.deepcopy(self.result)


class ExistingAdbReader:
    """Read fixed diagnostics from an existing loopback ADB server only.

    This never invokes the adb CLI, starts a server, pairs/connects devices,
    changes settings or launches apps. Socket permission failures are returned
    as unavailable. Android service output is never written to logs.
    """
    ALLOWED = {"getprop ro.product.manufacturer", "getprop ro.product.model", "dumpsys window policy", "dumpsys display", "pm path cn.damai"}

    def __init__(self, timeout=3):
        self.timeout = timeout

    @staticmethod
    def _exact(sock, size):
        data = bytearray()
        while len(data) < size:
            chunk = sock.recv(size - len(data))
            if not chunk:
                raise OSError("ADB response truncated")
            data.extend(chunk)
        return bytes(data)

    def _send(self, sock, service):
        data = service.encode("ascii")
        sock.sendall(("%04x" % len(data)).encode("ascii") + data)
        if self._exact(sock, 4) != b"OKAY":
            raise OSError("ADB read request rejected")

    def devices(self):
        with socket.create_connection(("127.0.0.1", 5037), timeout=self.timeout) as sock:
            self._send(sock, "host:devices-l")
            size = int(self._exact(sock, 4), 16)
            if size > 65536:
                raise OSError("ADB device list exceeds bound")
            return self._exact(sock, size).decode("utf-8", "strict")

    def read(self, serial, command):
        if command not in self.ALLOWED or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", serial) or serial.startswith("emulator-"):
            raise ValueError("only fixed diagnostics on a physical USB serial are permitted")
        with socket.create_connection(("127.0.0.1", 5037), timeout=self.timeout) as sock:
            self._send(sock, "host:transport:" + serial)
            self._send(sock, "shell:" + command)
            data = bytearray()
            while True:
                chunk = sock.recv(65536)
                if not chunk:
                    break
                data.extend(chunk)
                if len(data) > 1024 * 1024:
                    raise OSError("ADB diagnostic exceeds bound")
            return bytes(data).decode("utf-8", "replace")


def preflight(expected_serial, reader=None):
    """Future explicit read-only device check; no readiness or login inference."""
    base = {"mode": "read_only", "ui_adapter_available": False, "stop": True}
    if not expected_serial or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", expected_serial) or expected_serial.startswith("emulator-"):
        return {**base, "state": "device_identity_missing", "reason": "Set the locally verified USB serial in DAMAI_DEVICE_SERIAL; never commit it."}
    reader = reader or ExistingAdbReader()
    try:
        rows = [line.split() for line in reader.devices().splitlines() if line.strip()]
        selected = [row for row in rows if row[0] == expected_serial]
        if len(selected) != 1 or len(selected[0]) < 2 or selected[0][1] != "device" or not any(field.startswith("usb:") for field in selected[0][2:]):
            return {**base, "state": "device_offline", "reason": "The selected physical USB device is absent, offline or unauthorized."}
        manufacturer = reader.read(expected_serial, "getprop ro.product.manufacturer").strip()
        model = reader.read(expected_serial, "getprop ro.product.model").strip()
        if manufacturer != "vivo" or model != "V2547A":
            return {**base, "state": "wrong_device", "reason": "USB device identity does not match the approved vivo model."}
        window = reader.read(expected_serial, "dumpsys window policy")
        display = reader.read(expected_serial, "dumpsys display")
        lock = set(re.findall(r"\bshowing=(true|false)\b", window))
        power = set(re.findall(r"\bmScreenState=(ON|OFF)\b", display))
        installed = reader.read(expected_serial, "pm path cn.damai").strip().startswith("package:")
        if len(lock) != 1 or len(power) != 1:
            return {**base, "state": "device_state_unknown", "identity_verified": True, "reason": "OEM lock/display output is ambiguous; verify through permitted tools."}
        locked, screen_on = lock == {"true"}, power == {"ON"}
        return {**base, "state": "screen_blocked" if locked or not screen_on else "device_only_verified", "identity_verified": True, "screen_on": screen_on, "locked": locked, "candidate_app_installed": installed, "login_verified": False, "reason": "Device diagnostics do not prove the App or order controls are operable."}
    except (OSError, ValueError, IndexError, UnicodeError):
        return {**base, "state": "device_read_unavailable", "reason": "Existing ADB server could not be read; no server or connection was started."}
