#!/usr/bin/env python3
"""Read-only, privacy-bounded workstation/device diagnostics for Ticket Helper.

No installation, application start, click, unlock, setting mutation, private data read,
network setup or order action. The only file written is an explicitly requested JSON report.
Python 3.9+ standard library; works on macOS, Linux and Windows.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from typing import Callable, Dict, List, Optional, Tuple

HELPER = "cn.local.tickethelper"
DAMAI = "cn.damai"
SHIZUKU = "moe.shizuku.privileged.api"
SERVICE = HELPER + "/" + HELPER + ".MonitorService"
MAX_OUTPUT = 4 * 1024 * 1024


class CommandError(Exception):
    """Never carries stderr, serial numbers, command arguments or raw device output."""


def run_command(args: List[str], timeout: int = 12) -> str:
    try:
        # No shell interpolation. Known read-only executables and argument lists only.
        done = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise CommandError("command_unavailable_or_timeout") from None
    if done.returncode != 0:
        raise CommandError("command_failed")
    data = done.stdout + b"\n" + done.stderr
    if len(data) > MAX_OUTPUT:
        raise CommandError("command_output_limit")
    return data.decode("utf-8", errors="replace")


def safe_version(value: Optional[str]) -> Optional[str]:
    if value and re.fullmatch(r"[A-Za-z0-9_.+\-]{1,80}", value):
        return value
    return None


def parse_java(text: str) -> Optional[int]:
    match = re.search(r'(?:openjdk|java) version\s+"(\d+)(?:\.(\d+))?', text)
    if not match:
        match = re.search(r"(?:openjdk|java)\s+(\d+)(?:\.(\d+))?", text)
    if not match:
        return None
    major = int(match.group(1))
    return int(match.group(2)) if major == 1 and match.group(2) else major


def parse_devices(text: str) -> List[Dict[str, str]]:
    devices = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(("List of devices", "* daemon", "adb server", "adb:")):
            continue
        fields = line.split()
        if len(fields) < 2 or fields[1] not in {"device", "offline", "unauthorized", "no", "recovery", "sideload", "bootloader"}:
            continue
        # Serial is used only in an in-memory adb selector and is never returned in a report.
        model = next((x[6:] for x in fields[2:] if x.startswith("model:")), "unknown")
        model = model if re.fullmatch(r"[A-Za-z0-9_.\-]{1,80}", model) else "unknown"
        devices.append({"serial": fields[0], "state": fields[1], "model": model})
    return devices


def parse_package(text: str) -> Dict[str, object]:
    name = re.search(r"\bversionName=([^\s]+)", text)
    code = re.search(r"\bversionCode=(\d+)", text)
    if not name and not code:
        missing = bool(re.search(r"Unable to find package|Unknown package|Package .* not found", text, re.I))
        return {"installed": False if missing else None, "version_name": None, "version_code": None}
    return {"installed": True, "version_name": safe_version(name.group(1)) if name else None,
            "version_code": int(code.group(1)) if code else None}


def parse_lock_state(text: str) -> str:
    """Only explicit lock signals count. Awake/secure/inputRestricted are not proof of unlocked."""
    values = re.findall(r"\b(?:mKeyguardShowing|mShowingLockscreen|keyguardShowing)\s*=\s*(true|false)\b", text)
    # Android's WindowManagerPolicy dump nests showing under mKeyguardDelegate.
    delegate = re.search(r"(?m)^([ \t]*)(?:mKeyguardDelegate|KeyguardServiceDelegate)[ \t]*(?:[:=][ \t]*)?\{?[ \t]*$", text)
    if delegate:
        # Observed vivo format has a standalone 'KeyguardServiceDelegate' header with no ':'.
        # Read only its indented fields, not unrelated 'showing' flags elsewhere in the dump.
        indent = len(delegate.group(1))
        for line in text[delegate.end():].splitlines()[:32]:
            if not line.strip():
                continue
            if len(line) - len(line.lstrip(" \t")) <= indent:
                break
            match = re.fullmatch(r"\s*showing\s*=\s*(true|false)\s*", line)
            if match:
                values.append(match.group(1))
    if "true" in values:
        return "locked"
    if values and all(value == "false" for value in values):
        return "unlocked"
    return "unknown"


def parse_awake(text: str) -> str:
    match = re.search(r"\bmWakefulness=(Awake|Asleep|Dozing|Dreaming)\b", text)
    if not match:
        return "unknown"
    return "awake" if match.group(1) == "Awake" else "not_awake"


def helper_service_enabled(text: str) -> Optional[bool]:
    value = text.strip()
    if value in {"", "null"}:
        return False
    if not re.fullmatch(r"[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+(?::[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)*", value):
        return None
    names = value.split(":")
    return SERVICE in names or HELPER + "/.MonitorService" in names


def helper_service_bound(text: str) -> Optional[bool]:
    sections = re.findall(r"(?m)^\s*(?:Bound services|boundServices|mBoundServices)\s*[:=]\s*([^\r\n]+)", text)
    if not sections:
        return None
    if any(SERVICE in value or HELPER + "/.MonitorService" in value for value in sections):
        return True
    if all(re.fullmatch(r"\{\s*\}|\[\s*\]", value.strip()) for value in sections):
        return False
    # Some vivo versions expose labels/capabilities but omit bound component names. A label is
    # not enough to identify our service, and a nonempty ambiguous list does not prove absence.
    return None


def notification_permission(text: str) -> Optional[bool]:
    values = re.findall(r"android\.permission\.POST_NOTIFICATIONS:\s*granted=(true|false)", text)
    if not values or len(set(values)) != 1:
        return None
    return values[0] == "true"


def resolve_file(explicit: Optional[str], env_name: Optional[str], subpath: Tuple[str, ...], executable: str,
                 env: Dict[str, str], which: Callable[[str], Optional[str]]) -> Tuple[Optional[Path], str]:
    if explicit:
        path = Path(explicit).expanduser()
        return (path if path.is_file() else None), "explicit"
    if env_name and env.get(env_name):
        path = Path(env[env_name]).expanduser().joinpath(*subpath)
        if path.is_file():
            return path, env_name
    resolved = which(executable)
    return (Path(resolved) if resolved else None), "PATH" if resolved else "not_found"


def sdk_directory(explicit: Optional[str], env: Dict[str, str], project: Path) -> Tuple[Optional[Path], str]:
    if explicit:
        path = Path(explicit).expanduser()
        return (path if path.is_dir() else None), "explicit"
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if env.get(key):
            path = Path(env[key]).expanduser()
            if path.is_dir():
                return path, key
    local = project / "local.properties"
    if local.is_file():
        try:
            text = local.read_text(encoding="utf-8")
            match = re.search(r"(?m)^sdk\.dir\s*=\s*(.+)$", text)
            if match:
                value = re.sub(r"\\([\\:= ])", r"\1", match.group(1).strip())
                path = Path(value).expanduser()
                if path.is_dir():
                    return path, "local.properties"
        except (OSError, UnicodeError):
            pass
    return None, "not_found"


def project_info(project: Path) -> Dict[str, object]:
    result: Dict[str, object] = {"recognized": False, "version_name": None, "version_code": None, "compile_sdk": None}
    try:
        text = (project / "app" / "build.gradle").read_text(encoding="utf-8")
        if not re.search(r"applicationId\s+['\"]cn\.local\.tickethelper['\"]", text):
            return result
        result["recognized"] = True
        for key, pattern in (("version_name", r"versionName\s+['\"]([^'\"]+)['\"]"),
                             ("version_code", r"versionCode\s+(\d+)"), ("compile_sdk", r"compileSdk\s+(\d+)")):
            match = re.search(pattern, text)
            if match:
                result[key] = safe_version(match.group(1)) if key == "version_name" else int(match.group(1))
    except (OSError, UnicodeError):
        pass
    return result


def collect_host(project: Path, adb_arg: Optional[str], sdk_arg: Optional[str], java_arg: Optional[str],
                 env: Dict[str, str], runner: Callable[[List[str]], str], which: Callable[[str], Optional[str]],
                 windows: bool = False) -> Tuple[Dict[str, object], Optional[Path]]:
    suffix = ".exe" if windows else ""
    project_data = project_info(project)
    sdk, sdk_source = sdk_directory(sdk_arg, env, project)
    adb, adb_source = resolve_file(adb_arg, "ANDROID_HOME", ("platform-tools", "adb" + suffix), "adb" + suffix, env, which)
    if adb is None and not adb_arg and sdk is not None and (sdk / "platform-tools" / ("adb" + suffix)).is_file():
        adb, adb_source = sdk / "platform-tools" / ("adb" + suffix), "resolved_sdk"
    java, java_source = resolve_file(java_arg, "JAVA_HOME", ("bin", "java" + suffix), "java" + suffix, env, which)
    major = None
    if java is not None:
        try:
            major = parse_java(runner([str(java), "-version"]))
        except CommandError:
            pass
    sdk_level = project_data.get("compile_sdk")
    platform_available = bool(sdk and sdk_level and (sdk / "platforms" / ("android-" + str(sdk_level)) / "android.jar").is_file())
    build_tools = []
    if sdk and (sdk / "build-tools").is_dir():
        for tool_dir in (sdk / "build-tools").iterdir():
            if tool_dir.is_dir() and re.fullmatch(r"\d+\.\d+\.\d+(?:[-A-Za-z0-9.]*)", tool_dir.name):
                expected = ("aapt2" + suffix, "zipalign" + suffix, "apksigner" + (".bat" if windows else ""))
                if all((tool_dir / name).is_file() for name in expected):
                    build_tools.append(tool_dir.name)
    wrapper_files = ("gradlew.bat" if windows else "gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")
    wrapper_present = all((project / file).is_file() for file in wrapper_files)
    gradle_version = None
    try:
        wrapper_text = (project / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(encoding="utf-8")
        match = re.search(r"gradle-(\d+(?:\.\d+)+)-(?:bin|all)\.zip", wrapper_text)
        if match:
            gradle_version = match.group(1)
    except (OSError, UnicodeError):
        pass
    cache_root = Path(env.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))).expanduser()
    cached = False
    if gradle_version:
        for kind in ("bin", "all"):
            base = cache_root / "wrapper" / "dists" / ("gradle-" + gradle_version + "-" + kind)
            if base.is_dir():
                cached = cached or any(base.glob("*/gradle-" + gradle_version + "/bin/gradle" + (".bat" if windows else "")))
    gradle_on_path = which("gradle.bat" if windows else "gradle") is not None
    return {"project": project_data,
            "java": {"found": java is not None, "source": java_source, "major": major, "matches_jdk17": major == 17},
            "android_sdk": {"found": sdk is not None, "source": sdk_source, "required_platform": sdk_level,
                            "platform_available": platform_available, "build_tools_available": sorted(build_tools)},
            "adb": {"found": adb is not None, "source": adb_source},
            "gradle": {"wrapper_files_present": wrapper_present, "declared_version": gradle_version,
                       "declared_runtime_cached": cached, "executable_on_path": gradle_on_path,
                       "execution": "not_run", "dependency_cache": "not_checked"}}, adb


def collect_device(adb: Path, index: Optional[int], runner: Callable[[List[str]], str]) -> Dict[str, object]:
    try:
        devices = parse_devices(runner([str(adb), "devices", "-l"]))
    except CommandError:
        return {"status": "adb_unavailable", "count": None, "selected": False}
    public = [{"index": i + 1, "state": device["state"], "model": device["model"]} for i, device in enumerate(devices)]
    result: Dict[str, object] = {"status": "not_selected", "count": len(devices), "devices": public,
                               "selected": False, "selection_scope": "this_observation_only"}
    if not devices:
        result["status"] = "no_device"; return result
    if index is None and len(devices) != 1:
        result["status"] = "multiple_devices"; return result
    chosen = 0 if index is None else index - 1
    if chosen < 0 or chosen >= len(devices):
        result["status"] = "invalid_device_index"; return result
    selected = devices[chosen]
    result["selected_index"] = chosen + 1
    if selected["state"] != "device":
        result["status"] = selected["state"]; return result
    result["selected"] = True
    # Serial is never serialized, hashed into a report, displayed or included in errors.
    prefix = [str(adb), "-s", selected["serial"], "shell"]
    unavailable: List[str] = []
    def shell(label: str, *args: str) -> Optional[str]:
        try:
            return runner(prefix + list(args))
        except CommandError:
            unavailable.append(label); return None
    power = shell("power", "dumpsys", "power")
    policy = shell("keyguard", "dumpsys", "window", "policy")
    helper = shell("helper_package", "dumpsys", "package", HELPER)
    damai = shell("damai_package", "dumpsys", "package", DAMAI)
    shizuku = shell("shizuku_package", "dumpsys", "package", SHIZUKU)
    services = shell("accessibility_enabled", "settings", "get", "secure", "enabled_accessibility_services")
    bound = shell("accessibility_bound", "dumpsys", "accessibility")
    running = shell("shizuku_running", "pidof", "shizuku_server")
    result.update({"status": "observed", "screen": {"awake": parse_awake(power) if power is not None else "unknown",
                   "lock": parse_lock_state(policy) if policy is not None else "unknown"},
                   "helper": parse_package(helper) if helper is not None else {"installed": None},
                   "damai": parse_package(damai) if damai is not None else {"installed": None},
                   "permissions": {"helper_accessibility_enabled": helper_service_enabled(services) if services is not None else None,
                       "helper_accessibility_bound": helper_service_bound(bound) if bound is not None else None,
                       "notification_runtime_granted": notification_permission(helper) if helper is not None else None,
                       "notification_channel": "not_checked"},
                   "shizuku": {"installed": parse_package(shizuku)["installed"] if shizuku is not None else None,
                               "server_process_observed": (bool(running and re.fullmatch(r"[\d\s]+", running.strip()))
                                                           if running is not None else None),
                               "helper_authorization": "verify_on_phone", "helper_input_connection": "verify_on_phone"},
                   "unavailable_checks": unavailable})
    return result


def next_steps(host: Dict[str, object], device: Dict[str, object], offline: bool) -> List[str]:
    steps = []
    if not host["project"]["recognized"]:
        steps.append("请在解压后的票务助手工程运行本工具，工程根目录应包含 app/build.gradle。")
    if not host["java"]["matches_jdk17"]:
        steps.append("准备 JDK 17，并设置 JAVA_HOME 或使用 --java 指向 java 可执行文件。")
    if not host["android_sdk"]["platform_available"] or not host["android_sdk"]["build_tools_available"]:
        steps.append("准备项目要求的 Android SDK 平台及 Build Tools，设置 ANDROID_HOME；本工具不会下载或安装。")
    if not host["gradle"]["wrapper_files_present"]:
        steps.append("Gradle Wrapper 文件不完整，请重新解压完整电脑交接包。")
    elif not host["gradle"]["declared_runtime_cached"]:
        steps.append("尚未发现 Wrapper 声明版本的本地运行时缓存；后续首次构建可能需要下载，doctor 没有运行 Gradle。")
    if offline:
        steps.append("本次仅检查电脑。需要联调时连接同一台 vivo，再不带 --offline 运行；不要沿用历史连接状态。")
        return steps
    status = device.get("status")
    if status in {"adb_missing", "adb_unavailable"}:
        steps.append("准备 Android Platform Tools，通过 --adb 指定 adb，或将其加入 ANDROID_HOME / PATH。")
    elif status == "multiple_devices":
        steps.append("检测到多台设备，未执行设备检查。建议仅保留目标 vivo；如使用 --device-index，编号只对应本次枚举。")
    elif status == "invalid_device_index":
        steps.append("设备编号已变化或无效，请重新核对连接，建议仅保留目标 vivo。")
    elif status in {"unauthorized", "offline", "no", "recovery", "sideload", "bootloader", "no_device"}:
        steps.append("目标手机尚未成为可读的已授权 ADB 设备。请在手机正常开启 USB 调试并确认本电脑；不要修改或绕过锁屏。")
    elif status == "observed":
        screen = device["screen"]
        if screen["lock"] != "unlocked" or screen["awake"] != "awake":
            steps.append("屏幕或解锁状态未确认。需要操作界面时，请用户在手机正常解锁并保持亮屏；doctor 不会解锁。")
        if device["helper"].get("installed") is not True:
            steps.append("未核实到票务助手安装。先核对现有安装与签名，不能为绕过签名不匹配而卸载清数据。")
        if device["damai"].get("installed") is not True:
            steps.append("未核实到真实大麦安装；账号登录须由用户在官方 App 完成。")
        permissions = device["permissions"]
        if permissions["helper_accessibility_enabled"] is not True or permissions["helper_accessibility_bound"] is not True:
            steps.append("无障碍服务未完全确认：在手机设置中核对“票务助手 · 界面读取”，不需要 TalkBack。")
        if permissions["notification_runtime_granted"] is not True:
            steps.append("通知运行时权限未确认，请在手机允许助手通知并检查“有票及需要处理”类别。")
        steps.append("Shizuku 的助手授权和输入连接不能仅凭进程存在证明；请在手机助手设置中核对。")
        if device.get("unavailable_checks"):
            steps.append("部分只读检查返回未知；不要把未知解释为缺权限、缺货或运行正常。")
    steps.append("先读 AGENTS.md、handoff/START_HERE.md 与 VALIDATION.md。自动锁单未开放，USB 物理拔线运行仍未验收。")
    return steps


def summarize(report: Dict[str, object]) -> str:
    host, device = report["host"], report["device"]
    lines = ["票务助手 · 电脑接手只读诊断", "未安装、启动助手、点击、下单、解锁或更改手机设置。",
             "Java: " + ("JDK 17 就绪" if host["java"]["matches_jdk17"] else "待检查"),
             "Android SDK: " + ("平台已找到" if host["android_sdk"]["platform_available"] else "待准备"),
             "Gradle Wrapper: " + ("文件齐全，未执行" if host["gradle"]["wrapper_files_present"] else "文件不完整"),
             "设备检查: " + str(device.get("status", "unknown"))]
    if device.get("status") == "observed":
        lines.append("屏幕: " + device["screen"]["awake"] + " / " + device["screen"]["lock"])
        for label, name in (("票务助手", "helper"), ("大麦", "damai")):
            p = device[name]; lines.append(label + ": " + (str(p.get("version_name") or "版本未知") if p.get("installed") else "未确认安装"))
    if device.get("status") in {"multiple_devices", "invalid_device_index"}:
        for item in device.get("devices", []):
            lines.append("候选 " + str(item["index"]) + " · " + item["model"] + " · " + item["state"])
    lines.append("下一步：")
    lines.extend(str(i + 1) + ". " + step for i, step in enumerate(report["next_steps"]))
    return "\n".join(lines)


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="票务助手电脑接手只读诊断；不操作购票流程、不输出设备序列号或账号。")
    parser.add_argument("--offline", action="store_true", help="只检查电脑，不调用 adb")
    parser.add_argument("--adb", help="adb 可执行文件路径")
    parser.add_argument("--sdk", help="Android SDK 根目录")
    parser.add_argument("--java", help="java 可执行文件路径")
    parser.add_argument("--device-index", type=int, help="多设备时显式选择本次枚举编号，从 1 开始；建议只连接目标 vivo")
    parser.add_argument("--json", metavar="FILE", help="将脱敏报告写入指定文件；使用 - 只输出 JSON")
    args = parser.parse_args(argv)
    if args.device_index is not None and args.device_index < 1:
        parser.error("--device-index 必须大于 0")
    project = Path(__file__).resolve().parent.parent
    try:
        host, adb = collect_host(project, args.adb, args.sdk, args.java, dict(os.environ), run_command, shutil.which, os.name == "nt")
        device = ({"status": "not_checked_offline", "selected": False} if args.offline
                  else {"status": "adb_missing", "selected": False} if adb is None
                  else collect_device(adb, args.device_index, run_command))
        report = {"schema": 1, "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                  "scope": "read_only_diagnostic", "host_platform": sys.platform,
                  "host": host, "device": device, "next_steps": next_steps(host, device, args.offline),
                  "limits": ["No serial/account/preferences/UI/raw log output", "No application/order action",
                             "Shizuku grant and click readiness require phone verification", "Build success and untethered operation are not asserted"]}
        encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.json == "-":
            print(encoded, end="")
        else:
            print(summarize(report))
            if args.json:
                # The only deliberate write: the report file explicitly selected by the caller.
                with open(Path(args.json).expanduser(), "w", encoding="utf-8") as out:
                    out.write(encoded)
                print("脱敏 JSON 报告已保存。")
        host_ready = (host["project"]["recognized"] and host["java"]["matches_jdk17"] and
                      host["android_sdk"]["platform_available"] and bool(host["android_sdk"]["build_tools_available"])
                      and host["gradle"]["wrapper_files_present"])
        return 0 if host_ready and (args.offline or device.get("status") == "observed") else 1
    except (OSError, ValueError, CommandError):
        print("诊断未完成；请检查工具路径和报告保存位置。未输出原始错误或设备资料。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
