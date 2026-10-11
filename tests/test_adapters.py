import unittest
from unittest.mock import patch

from damai.adapters import ExistingAdbReader, LiveUIAdapter, preflight
from damai.orders import UnsupportedLive


class ReadFixture:
    def __init__(self, devices=None, manufacturer="vivo", model="V2547A", locked="false", screen="ON"):
        self.device_list = devices if devices is not None else "fixture-usb device usb:123 model:V2547A\nemulator-5554 device product:mock\n"
        self.outputs = {"getprop ro.product.manufacturer": manufacturer, "getprop ro.product.model": model,
                        "dumpsys window policy": "showing=" + locked,
                        "dumpsys display": "mScreenState=" + screen,
                        "pm path cn.damai": "package:/fixture/base.apk"}
        self.calls = []

    def devices(self):
        return self.device_list

    def read(self, serial, command):
        self.calls.append((serial, command))
        return self.outputs[command]


class AdapterTests(unittest.TestCase):
    def test_device_check_does_not_infer_ui_or_login(self):
        reader = ReadFixture()
        result = preflight("fixture-usb", reader)
        self.assertEqual(result["state"], "device_only_verified")
        self.assertTrue(result["identity_verified"])
        self.assertTrue(result["stop"])
        self.assertFalse(result["ui_adapter_available"])
        self.assertFalse(result["login_verified"])
        self.assertNotIn("serial", result)
        self.assertTrue(all(serial == "fixture-usb" for serial, _ in reader.calls))

    def test_serial_missing_does_not_open_socket(self):
        with patch("socket.create_connection") as connect:
            self.assertEqual(preflight(None)["state"], "device_identity_missing")
            connect.assert_not_called()

    def test_emulator_is_never_selected(self):
        reader = ReadFixture()
        self.assertEqual(preflight("emulator-5554", reader)["state"], "device_identity_missing")
        self.assertFalse(reader.calls)

    def test_usb_required_before_any_shell_read(self):
        reader = ReadFixture(devices="fixture-usb device product:mock\n")
        self.assertEqual(preflight("fixture-usb", reader)["state"], "device_offline")
        self.assertFalse(reader.calls)

    def test_wrong_model(self):
        self.assertEqual(preflight("fixture-usb", ReadFixture(model="other"))["state"], "wrong_device")

    def test_locked_or_asleep_stops(self):
        for reader in (ReadFixture(locked="true"), ReadFixture(screen="OFF")):
            self.assertEqual(preflight("fixture-usb", reader)["state"], "screen_blocked")

    def test_ambiguous_oem_state_stops(self):
        reader = ReadFixture()
        reader.outputs["dumpsys window policy"] = "showing=true showing=false"
        self.assertEqual(preflight("fixture-usb", reader)["state"], "device_state_unknown")

    def test_no_adb_server_does_not_start_one(self):
        with patch("socket.create_connection", side_effect=ConnectionRefusedError), patch("subprocess.run") as run:
            self.assertEqual(preflight("fixture-usb")["state"], "device_read_unavailable")
            run.assert_not_called()

    def test_reader_rejects_non_diagnostic_commands_before_connection(self):
        reader = ExistingAdbReader()
        with patch("socket.create_connection") as connect:
            for command in ("input tap 1 2", "settings put global foo 0", "am start cn.damai", "pm clear cn.damai", "reboot"):
                with self.assertRaises(ValueError):
                    reader.read("fixture-usb", command)
            connect.assert_not_called()

    def test_live_adapter_unavailable(self):
        adapter = LiveUIAdapter()
        self.assertFalse(adapter.available)
        with self.assertRaises(UnsupportedLive):
            adapter.observe()
        with self.assertRaises(UnsupportedLive):
            adapter.submit_pending({})


if __name__ == "__main__":
    unittest.main()
