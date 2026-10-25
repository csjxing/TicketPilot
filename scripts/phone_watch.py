"""Local, visible-UI-only Damai watcher. No network API, LLM, or payment calls."""
import argparse
import datetime as dt
import fcntl
import json
import os
from pathlib import Path
import re
import random
import uuid
import subprocess
import time
import xml.etree.ElementTree as ET
from phone_recovery import diagnose
from phone_vision import VisionReader

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / '.runtime/live'
ADB = Path.home() / '.local/share/scrcpy-macos-aarch64-v5.0.1/adb'
WIDTH, HEIGHT = 1260, 2772
VISION = VisionReader(RUN/"phone-ocr-v2",RUN/"phone-ocr")

class Stop(RuntimeError): pass

def command(*args, timeout=12):
    return subprocess.run([str(x) for x in args], check=True, capture_output=True, timeout=timeout).stdout

def adb(*args, **kwargs): return command(ADB, '-d', *args, **kwargs)
def normalize(s): return re.sub(r'\s+', '', s)
def box(node): return tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))
def inside(row, bounds):
    x,y=row['x']*WIDTH,row['y']*HEIGHT
    return bounds[0]<=x<=bounds[2] and bounds[1]<=y<=bounds[3]
def row_text(rows): return '\n'.join(r['text'] for r in rows)
def row_exact(rows, value):
    found=[r for r in rows if normalize(r['text'])==value and r['confidence']>=0.8]
    if len(found)!=1: raise Stop('ambiguous_control')
    return found[0]

def tap_row(row):
    adb('shell','input','tap',round(row['x']*WIDTH),round(row['y']*HEIGHT))
    time.sleep(.7)

def snapshot():
    window=adb('shell','dumpsys','window').decode(errors='replace')
    focus=[s for s in window.splitlines() if 'mCurrentFocus=' in s and 'null' not in s]
    if len(focus)!=1 or 'cn.damai/' not in focus[0]: raise Stop('phone_not_in_damai')
    image=adb('exec-out','screencap','-p')
    if not image.startswith(b'\x89PNG'): raise Stop('screenshot_failed')
    (RUN/'current.png').write_bytes(image)
    rows=VISION.read(RUN/'current.png')
    text=normalize(row_text(rows))
    if any(s in text for s in ['验证码','滑块','安全验证','操作频繁','访问异常','登录后','请输入手机号','网络开小差','网络异常']): raise Stop('verification_or_network_block')
    return rows

def hierarchy():
    result=adb('shell','uiautomator','dump','/sdcard/damai-script.xml',timeout=9)
    if b'dumped to' not in result: raise Stop('ui_dump_failed')
    return ET.fromstring(adb('exec-out','cat','/sdcard/damai-script.xml'))

def sku_options(rows, tree, cfg):
    text=normalize(row_text(rows))
    if not all(s in text for s in ['孙燕姿','杭州','场次','票档','2026-10-11','19:30']): raise Stop('wrong_or_unknown_sku')
    # One visible performance only; do not interpret an unselected date as confirmation.
    perform=next((n for n in tree.iter('node') if n.get('resource-id','').endswith('/project_detail_perform_flowlayout')),None)
    if perform is None or len(list(perform))!=1: raise Stop('ambiguous_performance')
    grid=next((n for n in tree.iter('node') if n.get('resource-id','').endswith('/project_detail_perform_price_flowlayout')),None)
    if grid is None: raise Stop('missing_tier_grid')
    result={}
    for card in list(grid):
        bounds=box(card)
        if len(bounds)!=4: raise Stop('bad_tier_bounds')
        labels=[r for r in rows if inside(r,bounds)]
        cardtext=normalize(row_text(labels))
        prices=re.findall(r'(?<!\d)(\d{3,4})元',cardtext)
        if len(prices)!=1: raise Stop('unreadable_tier')
        price=int(prices[0])
        tags=''.join(n.get('text','') for n in card.iter('node'))+cardtext
        unavailable=any(s in tags for s in ['缺货','无票','售罄','登记'])
        if price in cfg['prices']:
            result[price]={'available':not unavailable,'bounds':bounds}
    if set(result)!=set(cfg['prices']): raise Stop('missing_target_tier')
    if '无票' in text and any(v['available'] for v in result.values()): raise Stop('conflicting_stock')
    return result

def visible_all_sold_out(rows,cfg):
    """Fast negative-only path. Never derives available stock or tap coordinates."""
    text=normalize(row_text(rows))
    if not all(x in text for x in ['孙燕姿','杭州','场次','票档','19:30']):return False
    dates=re.findall(r'2026-\d{2}-\d{2}',text)
    if dates!=['2026-10-11']:return False
    for price in cfg['prices']:
        labels=[r for r in rows if re.fullmatch(r'(看台|内场)'+str(price)+'元',normalize(r['text'])) and .35<r['y']<.8]
        if len(labels)!=1:return False
        label=labels[0]
        tags=[r for r in rows if normalize(r['text']) in ['缺货登记','无票','售罄'] and abs(r['y']-label['y'])<.012 and 0<r['x']-label['x']<.26 and r.get('confidence',0)>=.8]
        if len(tags)!=1:return False
    return True

def fast_read_sku(cfg,rows=None):
    rows=rows if rows is not None else snapshot()
    if visible_all_sold_out(rows,cfg):
        return rows,None,{price:{'available':False} for price in cfg['prices']}
    return read_sku(cfg)

def read_sku(cfg):
    last=None
    for _ in range(3):
        try:
            tree=hierarchy()
            rows=snapshot()
            options=sku_options(rows,tree,cfg)
            return rows,tree,options
        except (Stop,subprocess.TimeoutExpired) as e:
            last=e
            if isinstance(e,Stop) and str(e) in ['phone_not_in_damai','verification_or_network_block']:raise
            time.sleep(.5)
    raise last

def checkout_verified(rows, cfg, price, tree):
    """Fail closed: viewer selection and quantity require explicit UI evidence."""
    text=normalize(row_text(rows))
    needed=['确认购买','孙燕姿','杭州','2026-10-11','19:30',cfg['spectator'],'1张',str(price)+'元','立即提交']
    if not all(s in text for s in needed): return False
    if any(s in text for s in ['保险','配送费','服务费','加购','请选择','缺货','无票']): return False
    # A visible name alone is not proof that the viewer is selected.
    selected=[]
    for n in tree.iter('node'):
        if n.get('checked')=='true':
            selected.append(n)
    if len(selected)!=1: return False
    # Associate checked control with same visible viewer row, not an agreement checkbox.
    b=box(selected[0])
    names=[r for r in rows if cfg['spectator'] in normalize(r['text'])]
    if len(b)!=4 or len(names)!=1 or abs(names[0]['y']*HEIGHT-(b[1]+b[3])/2)>150: return False
    bottom=[r for r in rows if r['y']>.86]
    amounts=re.findall(r'[¥￥](\d+(?:\.\d{1,2})?)',normalize(row_text(bottom)))
    if not amounts or any(float(a)!=price for a in amounts): return False
    return price<=cfg['max_ticket_total']

def dynamic_interval(stable_cycles):
    if stable_cycles<=3:return 4
    if stable_cycles<=12:return 5
    return 6

def jitter_interval(base):
    return round(random.uniform(base*.8,base*1.2),2)

class Watcher:
    def __init__(self,cfg): self.cfg=cfg; self.run_id=uuid.uuid4().hex; self.event_seq=0; self.cycles=0; self.model_calls=0; self.last_price=None; self.stable_cycles=0; self.last_signature=None; self.next_interval=4; self.reload_count=0; self.last_reload=time.monotonic()
    def status(self,state,**extra):
        self.event_seq+=1
        allowed={'tiers','price','reason','next_interval_seconds','cycle_duration_ms','last_ocr_ms'}
        safe={k:v for k,v in extra.items() if k in allowed}
        obj={'state':state,'checked_at':dt.datetime.now(dt.timezone.utc).isoformat(),
             'run_id':self.run_id,'event_seq':self.event_seq,'cycles':self.cycles,
             'pid':os.getpid(),'llm_calls':self.model_calls,'reload_count':self.reload_count,**safe}
        event={**obj,'kind':'poll_result' if state in ['out_of_stock','stock_signal','blocked'] else 'lifecycle',
               'inventory_signal':{'out_of_stock':'out_of_stock','stock_signal':'in_stock_signal'}.get(state,'unknown')}
        # Append before replacing latest state. Never include screenshots or personal data.
        with (RUN/'poll-history.jsonl').open('a',encoding='utf-8') as history:
            history.write(json.dumps(event,ensure_ascii=False,separators=(',',':'))+'\n')
            history.flush()
            os.fsync(history.fileno())
        tmp=RUN/'status.tmp';tmp.write_text(json.dumps(obj,ensure_ascii=False,indent=2));tmp.replace(RUN/'status.json')
    def notify(self,text):
        # Local desktop only. No messages to external recipients.
        script='display notification '+json.dumps(text,ensure_ascii=False)+' with title "大麦手机监控" sound name "Glass"'
        subprocess.run(['osascript','-e',script],capture_output=True)
        subprocess.Popen(['afplay','/System/Library/Sounds/Glass.aiff'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    def attempt(self,rows,options):
        if (RUN/'submission.json').exists(): raise Stop('submission_already_recorded')
        if dt.datetime.now(dt.timezone.utc)>=dt.datetime.fromisoformat(self.cfg['expires_at']): raise Stop('expired')
        price=next(p for p in self.cfg['prices'] if options[p]['available'])
        self.last_price=price
        self.status('stock_signal',price=price,tiers={str(k):'in_stock_signal' if v['available'] else 'out_of_stock' for k,v in options.items()})
        b=options[price]['bounds'];adb('shell','input','tap',(b[0]+b[2])//2,(b[1]+b[3])//2);time.sleep(.7)
        rows=snapshot();text=normalize(row_text(rows))
        # Selection must show one ticket's exact total. Never blindly change count.
        tree=hierarchy()
        totals=[n.get('text') for n in tree.iter('node') if n.get('resource-id')=='cn.damai:id/tv_price']
        if totals!=[str(price)]: raise Stop('selected_price_or_quantity_unknown')
        if not all(s in text for s in ['2026-10-11','19:30','孙燕姿','杭州']): raise Stop('selection_context_changed')
        tap_row(row_exact(rows,'确定'))
        rows=snapshot();tree=hierarchy()
        if not checkout_verified(rows,self.cfg,price,tree): raise Stop('checkout_requires_review')
        return self.submit(rows,price)
    def submit(self,rows,price):
        submit=row_exact(rows,'立即提交')
        if dt.datetime.now(dt.timezone.utc)>=dt.datetime.fromisoformat(self.cfg['expires_at']): raise Stop('expired')
        # Durable write BEFORE the one permitted submit; all unknown results stay locked.
        with (RUN/'submission.json').open('x') as f:
            json.dump({'state':'submission_started','price':price,'quantity':1,'at':time.time()},f);f.flush();os.fsync(f.fileno())
        tap_row(submit)
        rows=snapshot();text=normalize(row_text(rows))
        if ('待付款' in text or '支付剩余时间' in text) and all(v in text for v in ['孙燕姿','杭州','2026-10-11',str(price)]):
            self.status('pending_payment',price=price)
            self.notify('出现待付款订单，请立即在手机核对并付款。脚本已停止。')
        else:
            self.status('submission_result_unknown')
            self.notify('已尝试提交，结果需在手机核实；脚本已停止，不会重复下单。')
        return True
    def cycle(self, refresh=False):
        cycle_start=time.monotonic()
        self.cycles+=1
        if (RUN/'submission.json').exists(): raise Stop('submission_already_recorded')
        rows=snapshot()
        txt=normalize(row_text(rows))
        if '票档' not in txt and all(v in txt for v in ['孙燕姿','杭州','2026.10.09/10.11']):
            buttons=[r for r in rows if normalize(r['text']) in ['缺货登记','立即购买','立即预订'] and r['y']>.88]
            if len(buttons)!=1:raise Stop('detail_button_unknown')
            tap_row(buttons[0]);rows=snapshot();self.last_reload=time.monotonic()
        rows,tree,options=fast_read_sku(self.cfg,rows)
        if any(v['available'] for v in options.values()): return self.attempt(rows,options)
        if refresh:
            adb('shell','input','keyevent','4');time.sleep(.7)
            detail=snapshot();txt=normalize(row_text(detail))
            if not all(s in txt for s in ['孙燕姿','杭州','2026.10.09/10.11']): raise Stop('detail_changed')
            buttons=[r for r in detail if normalize(r['text']) in ['缺货登记','立即购买','立即预订'] and r['y']>.88]
            if len(buttons)!=1: raise Stop('detail_button_unknown')
            tap_row(buttons[0]);rows,tree,options=fast_read_sku(self.cfg);self.last_reload=time.monotonic();self.reload_count+=1
        if any(v['available'] for v in options.values()): return self.attempt(rows,options)
        signature=tuple((p,options[p]['available']) for p in sorted(options))
        self.stable_cycles=self.stable_cycles+1 if signature==self.last_signature else 1
        self.last_signature=signature
        self.next_interval=jitter_interval(dynamic_interval(self.stable_cycles))
        self.status('out_of_stock',tiers={str(k):'out_of_stock' for k in options},next_interval_seconds=self.next_interval,cycle_duration_ms=round((time.monotonic()-cycle_start)*1000),last_ocr_ms=VISION.last_ms)
        return False
    def recover(self,reason):
        if self.model_calls>=3 or (RUN/'submission.json').exists(): return False
        # Only the connected, foreground app may be inspected; never touch another app.
        try:
            before=snapshot()
        except Exception:
            return False
        self.model_calls+=1
        self.status('model_recovery',reason=reason)
        answer=diagnose(ROOT,RUN,reason,self.cfg)
        rows=snapshot()
        # Do not execute a model decision on a page that changed while it reasoned.
        stable=lambda rs: [(r['text'],round(r['y'],2)) for r in rs if .08<r['y']<.97]
        if stable(rows)!=stable(before): return False
        action=answer.get('action')
        if action=='retry_read':
            sku_options(rows,hierarchy(),self.cfg)
            return True
        if action=='back_to_sku':
            txt=normalize(row_text(rows))
            if not all(v in txt for v in ['孙燕姿','杭州','2026.10.09/10.11']):return False
            buttons=[r for r in rows if normalize(r['text']) in ['缺货登记','立即购买','立即预订'] and r['y']>.88]
            if len(buttons)!=1:return False
            tap_row(buttons[0]);return True
        price=answer.get('price')
        matched=(answer.get('event')=='孙燕姿杭州' and answer.get('date')==self.cfg['date'] and price in self.cfg['prices'] and answer.get('quantity')==1 and answer.get('total')==price and price<=self.cfg['max_ticket_total'])
        if not matched:return False
        text=normalize(row_text(rows))
        if not all(v in text for v in ['孙燕姿','杭州','2026-10-11','19:30',str(price)]):return False
        if action=='tap_confirm':
            sku_options(rows,hierarchy(),self.cfg)
            totals=[n.get('text') for n in hierarchy().iter('node') if n.get('resource-id')=='cn.damai:id/tv_price']
            if totals!=[str(price)]:return False
            self.last_price=price;tap_row(row_exact(rows,'确定'))
            raise Stop('checkout_requires_review')
        if action=='submit_pending':
            if answer.get('spectator')!=self.cfg['spectator'] or answer.get('viewer_selected') is not True or answer.get('no_paid_extras') is not True:return False
            if not all(v in text for v in ['确认购买',self.cfg['spectator'],'立即提交']):return False
            if any(v in text for v in ['保险','配送费','服务费','加购','请选择','缺货','无票']):return False
            amounts=re.findall(r'[¥￥](\d+(?:\.\d{1,2})?)',normalize(row_text([r for r in rows if r['y']>.86])))
            if not amounts or any(float(v)!=price for v in amounts):return False
            self.submit(rows,price)
            return False
        return False
    def run(self,once):
        just_recovered=False
        while True:
            try:
                if dt.datetime.now(dt.timezone.utc)>=dt.datetime.fromisoformat(self.cfg['expires_at']):
                    self.status('expired');return
                start=time.monotonic()
                if self.cycle(refresh=self.cycles>0): return
                just_recovered=False
                if once:return
                time.sleep(max(1,self.next_interval-(time.monotonic()-start)))
            except Exception as e:
                reason=str(e) if isinstance(e,Stop) else type(e).__name__
                self.status('blocked',reason=reason)
                if not once and not just_recovered:
                    try:
                        if self.recover(reason):
                            self.stable_cycles=0
                            self.last_signature=None
                            just_recovered=True
                            continue
                    except Exception as recovery_error:
                        reason+=' / '+type(recovery_error).__name__
                if (RUN/'submission.json').exists():
                    previous=json.loads((RUN/'status.json').read_text())
                    if previous.get('state') not in ['pending_payment','submission_result_unknown']:
                        self.status('submission_result_unknown',reason=reason)
                        self.notify('提交结果无法确认，请立即查看手机订单；脚本停止，不重复下单。')
                    return
                self.status('needs_user',reason=reason)
                self.notify('自动恢复未完成，监控已停止，请查看手机：'+reason)
                return

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--once',action='store_true');args=parser.parse_args()
    os.umask(0o077)
    RUN.mkdir(parents=True,exist_ok=True)
    with (RUN/'watch.lock').open('w') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise SystemExit('watcher already running')
        cfg=json.loads((ROOT/'watch.json').read_text())
        if cfg['quantity']!=1 or set(cfg['prices'])!={980,1380,1680} or cfg['max_ticket_total']!=1680: raise SystemExit('invalid live configuration')
        if adb('shell','getprop','ro.product.model').decode().strip()!='V2547A': raise SystemExit('wrong device')
        Watcher(cfg).run(args.once)
if __name__=='__main__':main()
