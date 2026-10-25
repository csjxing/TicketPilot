"""Offline tests only: commands and device responses are injected, never run against a phone."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("ticket_helper_doctor", Path(__file__).with_name("doctor.py"))
doctor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(doctor)


class DoctorTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)

    def touch(self, path, content=""):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def project(self):
        project = self.base / "project"
        self.touch(project / "app/build.gradle", "applicationId 'cn.local.tickethelper'\nversionName '0.1.10-preview'\nversionCode 11\ncompileSdk 35\n")
        self.touch(project / "gradlew")
        self.touch(project / "gradlew.bat")
        self.touch(project / "gradle/wrapper/gradle-wrapper.jar")
        self.touch(project / "gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.11.1-bin.zip\n")
        return project

    def test_java_major_variants(self):
        self.assertEqual(17, doctor.parse_java('openjdk version "17.0.21" 2026-10-20'))
        self.assertEqual(17, doctor.parse_java('java version "17.0.2"'))
        self.assertEqual(8, doctor.parse_java('java version "1.8.0_402"'))
        self.assertEqual(21, doctor.parse_java('openjdk 21.0.4 2024-07-16'))
        self.assertIsNone(doctor.parse_java("could not execute"))

    def test_unknown_lock_state_is_not_unlocked(self):
        self.assertEqual("unknown", doctor.parse_lock_state("mWakefulness=Awake\nsecure=false\nshowing=false"))
        self.assertEqual("unknown", doctor.parse_lock_state("mKeyguardDelegate:\n  inputRestricted=false\n  secure=false"))

    def test_explicit_lock_signals_and_conflict(self):
        self.assertEqual("unlocked", doctor.parse_lock_state("mKeyguardDelegate:\n  showing=false\n  secure=true"))
        self.assertEqual("locked", doctor.parse_lock_state("mKeyguardDelegate:\n  showing=true\n  secure=true"))
        self.assertEqual("locked", doctor.parse_lock_state("mKeyguardShowing=true\nmKeyguardDelegate:\n showing=false"))
        self.assertEqual("not_awake", doctor.parse_awake("mWakefulness=Dozing"))

    def test_vivo_delegate_header_without_colon_and_unrelated_showing(self):
        captured_shape = "  KeyguardServiceDelegate\n    showing=false\n    secure=true\n  AnotherPolicy\n    showing=true\n"
        self.assertEqual("unlocked", doctor.parse_lock_state(captured_shape))
        self.assertEqual("locked", doctor.parse_lock_state(captured_shape.replace("showing=false", "showing=true")))

    def test_package_failure_does_not_claim_missing(self):
        self.assertIsNone(doctor.parse_package("permission denied")["installed"])
        self.assertIsNone(doctor.parse_package("")["installed"])
        self.assertFalse(doctor.parse_package("Unable to find package: cn.damai")["installed"])

    def test_package_parser_only_returns_public_versions(self):
        parsed = doctor.parse_package("versionCode=109003700 minSdk=21\n versionName=9.0.37\n private_account=hidden@example.test")
        self.assertEqual({"installed": True, "version_name": "9.0.37", "version_code": 109003700}, parsed)
        self.assertNotIn("hidden", json.dumps(parsed))

    def test_enabled_service_not_confused_with_talkback(self):
        self.assertFalse(doctor.helper_service_enabled("com.example.talkback/com.example.talkback.Service"))
        self.assertTrue(doctor.helper_service_enabled(doctor.SERVICE))
        self.assertTrue(doctor.helper_service_enabled("cn.local.tickethelper/.MonitorService"))
        self.assertFalse(doctor.helper_service_enabled("null"))
        self.assertIsNone(doctor.helper_service_enabled("SecurityException: permission denied"))

    def test_bound_and_enabled_are_separate(self):
        self.assertIsNone(doctor.helper_service_bound("Enabled services: {" + doctor.SERVICE + "}"))
        self.assertFalse(doctor.helper_service_bound("Bound services: {}"))
        self.assertTrue(doctor.helper_service_bound("Bound services: {Service[componentName=ComponentInfo{" + doctor.SERVICE + "}]}"))

    def test_vivo_bound_labels_without_components_remain_unknown(self):
        captured_shape = "Bound services:{Service[label=票务助手 · 界面读取, feedbackType[FEEDBACK_GENERIC], capabilities=161]}\nEnabled services:{{" + doctor.SERVICE + "}}"
        self.assertIsNone(doctor.helper_service_bound(captured_shape))

    def test_permission_unknown_conflict_is_not_granted(self):
        self.assertIsNone(doctor.notification_permission("no result"))
        self.assertTrue(doctor.notification_permission("android.permission.POST_NOTIFICATIONS: granted=true, flags=[]"))
        self.assertIsNone(doctor.notification_permission("android.permission.POST_NOTIFICATIONS: granted=true\nandroid.permission.POST_NOTIFICATIONS: granted=false"))

    def test_multiple_devices_without_explicit_choice_never_runs_shell(self):
        calls = []
        def runner(args):
            calls.append(args)
            return "List of devices attached\nPRIVATE_A device model:V2547A\nPRIVATE_B device model:Another\n"
        result = doctor.collect_device(Path("adb"), None, runner)
        self.assertEqual("multiple_devices", result["status"])
        self.assertEqual(1, len(calls))
        self.assertNotIn("PRIVATE", json.dumps(result))
        self.assertEqual("this_observation_only", result["selection_scope"])

    def test_unauthorized_device_never_runs_shell(self):
        calls = []
        def runner(args):
            calls.append(args); return "List of devices attached\nPRIVATE_PHONE unauthorized\n"
        result = doctor.collect_device(Path("adb"), None, runner)
        self.assertEqual("unauthorized", result["status"])
        self.assertEqual(1, len(calls))
        self.assertFalse(result["selected"])

    def test_invalid_index_cannot_fall_back_to_first_phone(self):
        result = doctor.collect_device(Path("adb"), 3, lambda args: "List of devices attached\nPRIVATE_A device model:V2547A\n")
        self.assertEqual("invalid_device_index", result["status"])
        self.assertFalse(result["selected"])

    def test_selected_device_commands_remain_read_only_and_report_is_private(self):
        calls = []
        responses = {
            ("dumpsys", "power"): "mWakefulness=Awake\nPRIVATE_ACCOUNT",
            ("dumpsys", "window", "policy"): "mKeyguardDelegate:\n showing=false\n raw_ui=PRIVATE_UI",
            ("dumpsys", "package", doctor.HELPER): "versionCode=11\nversionName=0.1.10-preview\nandroid.permission.POST_NOTIFICATIONS: granted=true\nPRIVATE_LOG",
            ("dumpsys", "package", doctor.DAMAI): "versionCode=109003700\nversionName=9.0.37\nPRIVATE_ACCOUNT",
            ("dumpsys", "package", doctor.SHIZUKU): "versionCode=130600\nversionName=13.6.0",
            ("settings", "get", "secure", "enabled_accessibility_services"): doctor.SERVICE,
            ("dumpsys", "accessibility"): "Bound services: {" + doctor.SERVICE + "}",
            ("pidof", "shizuku_server"): "12345",
        }
        def runner(args):
            calls.append(args)
            if args[1:] == ["devices", "-l"]:
                return "List of devices attached\nPRIVATE_SERIAL device model:V2547A\n"
            self.assertEqual(["-s", "PRIVATE_SERIAL", "shell"], args[1:4])
            return responses[tuple(args[4:])]
        report = doctor.collect_device(Path("adb"), None, runner)
        self.assertEqual("observed", report["status"])
        self.assertEqual("unlocked", report["screen"]["lock"])
        self.assertTrue(report["permissions"]["helper_accessibility_bound"])
        self.assertEqual("verify_on_phone", report["shizuku"]["helper_authorization"])
        self.assertEqual("verify_on_phone", report["shizuku"]["helper_input_connection"])
        self.assertNotIn("PRIVATE", json.dumps(report))
        self.assertNotIn("12345", json.dumps(report))
        allowed = set(responses)
        self.assertTrue(all(tuple(call[4:]) in allowed for call in calls[1:]))

    def test_command_failure_does_not_mean_uninstalled_or_no_shizuku(self):
        def runner(args):
            if args[1:] == ["devices", "-l"]:
                return "List of devices attached\nPRIVATE_SERIAL device\n"
            raise doctor.CommandError("PRIVATE_SERIAL and private error")
        report = doctor.collect_device(Path("adb"), None, runner)
        self.assertIsNone(report["helper"]["installed"])
        self.assertEqual("unknown", report["screen"]["lock"])
        self.assertIsNone(report["shizuku"]["server_process_observed"])
        self.assertNotIn("PRIVATE", json.dumps(report))

    def test_windows_host_discovery_does_not_execute_gradle_or_adb(self):
        project = self.project(); sdk = self.base / "sdk"; java_home = self.base / "jdk"
        self.touch(sdk / "platform-tools/adb.exe"); self.touch(sdk / "platforms/android-35/android.jar")
        for tool in ("aapt2.exe", "zipalign.exe", "apksigner.bat"):
            self.touch(sdk / "build-tools/35.0.0" / tool)
        java = self.touch(java_home / "bin/java.exe")
        calls = []
        def runner(args):
            calls.append(args); return 'openjdk version "17.0.21"'
        host, adb = doctor.collect_host(project, None, None, None,
                {"ANDROID_HOME": str(sdk), "JAVA_HOME": str(java_home), "GRADLE_USER_HOME": str(self.base / "cache")},
                runner, lambda name: None, windows=True)
        self.assertEqual([[str(java), "-version"]], calls)
        self.assertTrue(host["java"]["matches_jdk17"])
        self.assertTrue(host["android_sdk"]["platform_available"])
        self.assertEqual(["35.0.0"], host["android_sdk"]["build_tools_available"])
        self.assertTrue(host["gradle"]["wrapper_files_present"])
        self.assertEqual("8.11.1", host["gradle"]["declared_version"])
        self.assertFalse(host["gradle"]["declared_runtime_cached"])
        self.assertEqual(sdk / "platform-tools/adb.exe", adb)
        self.assertNotIn(str(self.base), json.dumps(host))

    def test_explicit_missing_adb_does_not_silently_use_another(self):
        existing = self.touch(self.base / "other-adb")
        found, source = doctor.resolve_file(str(self.base / "missing"), None, (), "adb", {}, lambda _: str(existing))
        self.assertIsNone(found); self.assertEqual("explicit", source)

    def test_sdk_env_and_linux_wrapper_cache_discovery(self):
        project = self.project(); sdk = self.base / "sdk"; java_home = self.base / "jdk"; cache = self.base / "cache"
        self.touch(sdk / "platform-tools/adb"); self.touch(sdk / "platforms/android-35/android.jar")
        self.touch(java_home / "bin/java")
        self.touch(cache / "wrapper/dists/gradle-8.11.1-bin/hash/gradle-8.11.1/bin/gradle")
        host, adb = doctor.collect_host(project, None, str(sdk), None, {"JAVA_HOME": str(java_home), "GRADLE_USER_HOME": str(cache)},
                lambda args: 'openjdk version "17.0.21"', lambda _: None)
        self.assertTrue(host["gradle"]["declared_runtime_cached"])
        self.assertEqual("resolved_sdk", host["adb"]["source"])
        self.assertEqual(sdk / "platform-tools/adb", adb)
        self.assertEqual([], host["android_sdk"]["build_tools_available"])

    def test_host_missing_tools_returns_followup_without_executing_commands(self):
        project = self.project()
        def forbidden(args):
            self.fail("missing tools must not execute anything")
        host, adb = doctor.collect_host(project, None, None, None, {"GRADLE_USER_HOME": str(self.base / "cache")}, forbidden, lambda _: None)
        self.assertIsNone(adb)
        steps = doctor.next_steps(host, {"status": "not_checked_offline"}, True)
        self.assertTrue(any("JDK 17" in item for item in steps))
        self.assertTrue(any("仅检查电脑" in item for item in steps))


if __name__ == "__main__":
    unittest.main()
