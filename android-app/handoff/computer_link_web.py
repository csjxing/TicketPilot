"""Loopback-only desktop UI; no remote-control server or file serving."""
import json
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PAGE = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>TicketPilot · 电脑协作</title><style>
*{box-sizing:border-box}body{margin:0;background:#f5f7fb;color:#18213a;font:16px system-ui,-apple-system,sans-serif}main{max-width:940px;margin:54px auto;padding:0 24px}header{display:flex;align-items:center;justify-content:space-between;margin-bottom:30px}h1{font-size:32px;margin:0}small{color:#65728c}section{background:white;border:1px solid #e2e7f0;border-radius:20px;padding:26px;margin:18px 0}h2{font-size:19px;margin:0 0 18px}label{display:block;font-size:14px;margin:16px 0 7px}input,select{border:1px solid #ccd5e4;border-radius:10px;padding:12px;font:inherit;width:100%;background:white}button{border:0;border-radius:11px;padding:12px 20px;background:#3d5afe;color:white;font:inherit;cursor:pointer;margin:14px 8px 0 0}button.secondary{background:#eef1ff;color:#2945cf}button:disabled{opacity:.4;cursor:wait}.grid{display:grid;grid-template-columns:1fr 1fr;gap:22px}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f5f7fb;border-radius:12px;padding:18px;margin:18px 0 0;min-height:100px;font-size:14px}p{line-height:1.6;color:#65728c;margin:10px 0}#notice{color:#2945cf;font-size:14px}@media(max-width:650px){.grid{grid-template-columns:1fr}main{margin-top:24px}header{display:block}}
</style><main><header><h1>TicketPilot</h1><small>电脑协作 · 同一 Wi-Fi · 仅本机</small></header>
<section><h2>1　连接手机</h2><p>手机保持解锁，打开系统“无线调试”。首次使用这台电脑时配对一次。</p>
<button class="secondary" id="scan">查找调试地址</button><div class="grid"><div><label for="pair">配对地址</label><input id="pair" list="pairs" placeholder="配对码弹窗中的 IP:端口" autocomplete="off"><datalist id="pairs"></datalist><label for="code">六位配对码</label><input id="code" type="password" inputmode="numeric" maxlength="6" autocomplete="off"><button id="pairButton">首次配对</button></div><div><label for="endpoint">连接地址</label><input id="endpoint" list="endpoints" placeholder="无线调试详情页的 IP:端口" autocomplete="off"><datalist id="endpoints"></datalist><p>已配对后，使用详情页的连接端口。它与配对端口不同。</p><button id="connect">连接手机</button></div></div></section>
<section><h2>2　接手本次协作</h2><p>手机票务助手点“电脑 → 允许电脑协作15分钟”，然后在这里接手。协作期间手机监控暂停。</p>
<button data-op="claim">接手</button><button class="secondary" data-op="status">检查状态</button><button class="secondary" data-op="diagnostics">读取诊断</button><button class="secondary" data-op="release">结束协作</button><p id="notice">尚未操作</p><pre id="result">状态和脱敏诊断会显示在这里。</pre></section>
<p>电脑 AI 用于排查、理解页面和开发增强，普通刷票由手机本地执行。此界面不提交订单或付款；连接地址、配对码和诊断不上传云端。</p></main>
<script>
const token=__TOKEN__;const $=id=>document.getElementById(id);
async function act(operation,extra={}){document.querySelectorAll('button').forEach(b=>b.disabled=true);$('notice').textContent='正在处理…';try{const response=await fetch('/command',{method:'POST',headers:{'Content-Type':'application/json','X-TicketPilot':token},body:JSON.stringify({operation,...extra})});const data=await response.json();if(operation==='discover'&&response.ok){for(const [id,key] of [['pairs','pair'],['endpoints','connect']]){$(id).replaceChildren(...data[key].map(v=>{const o=document.createElement('option');o.value=v;return o}));}$('notice').textContent='已查找。选择与手机当前页面一致的地址。';}else{$('result').textContent=JSON.stringify(data,null,2);$('notice').textContent=response.ok?'操作已返回结果，请核对下方状态。':'操作未完成。';}}catch(e){$('notice').textContent='本机连接服务不可用，请检查电脑终端。';}finally{document.querySelectorAll('button').forEach(b=>b.disabled=false)}}
$('scan').onclick=()=>act('discover');$('pairButton').onclick=()=>{const code=$('code').value;$('code').value='';act('pair',{address:$('pair').value,code})};$('connect').onclick=()=>act('connect',{address:$('endpoint').value});document.querySelectorAll('[data-op]').forEach(b=>b.onclick=()=>act(b.dataset.op));
</script></html>'''


def create_server(client):
    token = secrets.token_hex(24)
    gate = threading.Lock()
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def send(self, status, body, kind='application/json; charset=utf-8'):
            data=body.encode('utf-8') if isinstance(body,str) else json.dumps(body,ensure_ascii=False).encode('utf-8')
            self.send_response(status);self.send_header('Content-Type',kind)
            self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store')
            self.send_header('X-Frame-Options','DENY');self.send_header('X-Content-Type-Options','nosniff')
            self.send_header('Content-Security-Policy',"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")
            self.end_headers();self.wfile.write(data)
        def host_ok(self): return self.headers.get('Host') == '127.0.0.1:'+str(self.server.server_port)
        def do_GET(self):
            if not self.host_ok():self.send(403,{'status':'denied'});return
            if self.path!='/':self.send(404,{'status':'not_found'});return
            self.send(200,PAGE.replace('__TOKEN__',json.dumps(token)),'text/html; charset=utf-8')
        def do_POST(self):
            origin='http://127.0.0.1:'+str(self.server.server_port)
            if self.path!='/command' or not self.host_ok() or self.headers.get('Origin')!=origin or not secrets.compare_digest(self.headers.get('X-TicketPilot',''),token):
                self.send(403,{'status':'denied'});return
            try:
                size=int(self.headers.get('Content-Length','0'))
                if size<2 or size>4096 or self.headers.get('Content-Type')!='application/json':raise ValueError()
                value=json.loads(self.rfile.read(size));op=value.get('operation')
                if op not in {'pair','connect','discover','status','claim','pause','diagnostics','release'}:raise ValueError()
            except (ValueError,TypeError,AttributeError):self.send(400,{'status':'invalid_request'});return
            if not gate.acquire(blocking=False):self.send(409,{'status':'busy'});return
            try:
                if op=='pair':result=client.pair(value.get('address'),value.get('code'))
                elif op=='connect':result=client.connect(value.get('address'))
                elif op=='discover':result=client.discover()
                else:result=client.request(op)
                self.send(200,result)
            except Exception:
                # Only our controlled LinkError messages are user-readable. Never return raw adb output.
                import computer_link
                import sys
                error=sys.exc_info()[1]
                self.send(400,{'status':'unavailable','message':str(error) if isinstance(error,computer_link.LinkError) else '操作失败，请核对手机和连接状态。'})
            finally:gate.release()
    return ThreadingHTTPServer(('127.0.0.1',0),Handler)


def gui(client, open_browser=True):
    import webbrowser
    server=create_server(client);url='http://127.0.0.1:'+str(server.server_port)+'/'
    print('TicketPilot 电脑协作界面：'+url,flush=True)
    if open_browser:webbrowser.open(url)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()
