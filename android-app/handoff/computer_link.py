#!/usr/bin/env python3
"""TicketPilot same-Wi-Fi companion. Standard library; no cloud/API or arbitrary-shell endpoint."""
import argparse
import getpass
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid

PACKAGE = 'cn.local.tickethelper'
ACTION = PACKAGE + '.COMPUTER_LINK'
OPS = {'status', 'claim', 'pause', 'diagnostics', 'release'}


class LinkError(Exception):
    pass


def address(value):
    """Only a literal LAN IP and port, never shell syntax, hostnames, or public ADB."""
    try:
        host, port = value.rsplit(':', 1)
        host = host.removeprefix('[').removesuffix(']')
        ip = ipaddress.ip_address(host)
        lan = any(ip in ipaddress.ip_network(n) for n in
                  (['10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'] if ip.version == 4 else ['fc00::/7']))
        if not lan or not port.isascii() or not port.isdigit() or not 1 <= int(port) <= 65535:
            raise ValueError()
        return ('[' + str(ip) + ']' if ip.version == 6 else str(ip)) + ':' + str(int(port))
    except (ValueError, AttributeError):
        raise LinkError('请填写无线调试页面的局域网 IP:端口；配对端口与连接端口不同。') from None


def save_private(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temp = path.with_name(path.name + '.tmp-' + uuid.uuid4().hex)
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as out:
        json.dump(value, out, ensure_ascii=False, indent=2)
    os.replace(temp, path)


class Companion:
    def __init__(self, adb='adb', state_dir=None):
        self.adb = shutil.which(adb) or (adb if Path(adb).is_file() else None)
        if not self.adb:
            raise LinkError('未找到 adb，请安装官方 Android Platform Tools 并加入 PATH。')
        self.root = Path(state_dir or Path.home() / '.ticketpilot')
        self.file = self.root / 'computer-link.json'
        try:
            self.state = json.loads(self.file.read_text(encoding='utf-8'))
            if not re.fullmatch('[a-f0-9]{32}', self.state['owner']):
                raise ValueError()
        except FileNotFoundError:
            self.state = {'owner': uuid.uuid4().hex}
        except (ValueError, KeyError, TypeError):
            raise LinkError('电脑本机连接记录损坏；请保留文件后人工核查。') from None
        self.lock = threading.Lock()

    def run(self, args, input_text=None, timeout=12):
        try:
            r = subprocess.run([self.adb] + list(args), input=input_text, text=True,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
            if r.returncode:
                raise LinkError('ADB 操作未完成，请检查解锁、同一 Wi-Fi 和无线调试状态。')
            return r.stdout
        except (subprocess.TimeoutExpired, OSError):
            raise LinkError('ADB 连接超时或工具不可用；未自动重试。') from None

    def pair(self, endpoint, code):
        endpoint = address(endpoint)
        if not re.fullmatch(r'[0-9]{6}', code or ''):
            raise LinkError('配对码应为手机当前弹窗中的6位数字。')
        # Code stays off argv and is never persisted or copied into exception text.
        out = self.run(['pair', endpoint], code + '\n', timeout=30)
        if 'successfully paired' not in out.lower():
            raise LinkError('系统未确认配对成功；请重新打开手机配对码弹窗。')
        return {'status': 'paired', 'next': '请使用详情页连接端口连接'}

    def connect(self, endpoint):
        endpoint = address(endpoint)
        out = self.run(['connect', endpoint])
        if not any(s in out.lower() for s in ['connected to', 'already connected']):
            raise LinkError('未连接，请核对详情页端口，而非配对码弹窗端口。')
        self.run(['-s', endpoint, 'get-state'])
        # Confirm the actual app, never assume any connected Android phone is TicketPilot.
        if 'package:' not in self.run(['-s', endpoint, 'shell', 'pm', 'path', PACKAGE]):
            raise LinkError('该设备没有安装 TicketPilot；未接手或安装其他应用。')
        identity = self.identity(endpoint)
        if self.state.get('fingerprint') and self.state['fingerprint'] != identity:
            raise LinkError('连接到了不同的手机，已拒绝接手。请核对本机连接记录。')
        self.state['fingerprint'] = identity
        self.state['endpoint'] = endpoint
        save_private(self.file, self.state)
        return {'status': 'connected', 'next': '手机允许协作后，点击接手'}

    def identity(self, endpoint):
        value = self.run(['-s', endpoint, 'shell', 'settings', 'get', 'secure', 'android_id']).strip()
        if not re.fullmatch('[a-fA-F0-9]{16}', value):
            raise LinkError('未能核对手机身份，停止接手。')
        return hashlib.sha256(value.encode('ascii')).hexdigest()

    def discover(self):
        raw = self.run(['mdns', 'services'])
        result = {'pair': [], 'connect': []}
        for line in raw.splitlines():
            for key, kind in [('pair', '_adb-tls-pairing._tcp'), ('connect', '_adb-tls-connect._tcp')]:
                if kind in line:
                    try: candidate = address(line.split()[-1])
                    except LinkError: continue
                    if candidate not in result[key]: result[key].append(candidate)
        return result  # For the local GUI only, never sent to AI output.

    def request(self, op):
        if op not in OPS:
            raise LinkError('不支持该操作。')
        endpoint = address(self.state.get('endpoint', ''))
        request = uuid.uuid4().hex
        with self.lock:
            if not self.state.get('fingerprint') or self.identity(endpoint) != self.state['fingerprint']:
                raise LinkError('手机身份不匹配；请在电脑工具重新核对连接。')
            self.run(['-s', endpoint, 'shell', 'run-as', PACKAGE, 'am', 'broadcast', '--user', '0',
                      '-a', ACTION, '-n', PACKAGE + '/.ComputerLinkReceiver',
                      '--es', 'operation', op, '--es', 'owner', self.state['owner'], '--es', 'request', request])
            until = time.monotonic() + 6
            while time.monotonic() < until:
                try:
                    data = json.loads(self.run(['-s', endpoint, 'shell', 'run-as', PACKAGE, 'cat',
                                                'files/computer-link-result.json']))
                except (json.JSONDecodeError, LinkError):
                    time.sleep(.2)
                    continue
                if isinstance(data, dict) and data.get('schema') == 1 and data.get('request') == request:
                    data.pop('request', None)
                    if data.get('status') != 'ok':
                        raise LinkError('手机协作未开启、已到期或已由另一台电脑接手；请在手机核对。')
                    return data
                time.sleep(.2)
            raise LinkError('未取得本次请求回执。请核对手机版本和协作开关；不要据此重试下单。')


def gui(client, open_browser=True):
    from computer_link_web import gui as browser_gui
    browser_gui(client, open_browser)


def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',default='adb');p.add_argument('--state-dir',type=Path)
    p.add_argument('command',choices=['gui','pair','connect']+sorted(OPS))
    p.add_argument('--address');p.add_argument('--output',type=Path);p.add_argument('--no-browser',action='store_true')
    a=p.parse_args(argv)
    try:
        c=Companion(a.adb,a.state_dir)
        if a.command=='gui':gui(c,not a.no_browser);return 0
        if a.command=='pair':result=c.pair(a.address or input('手机配对地址: ').strip(),getpass.getpass('手机配对码（不回显）: '))
        elif a.command=='connect':result=c.connect(a.address or input('手机连接地址: ').strip())
        else:result=c.request(a.command)
        if a.output:save_private(a.output,result)
        print(json.dumps(result,ensure_ascii=False,indent=2));return 0
    except LinkError as e:
        print(json.dumps({'status':'unavailable','message':str(e)},ensure_ascii=False));return 2


if __name__=='__main__':
    sys.modules.setdefault('computer_link',sys.modules[__name__])
    sys.exit(main())
