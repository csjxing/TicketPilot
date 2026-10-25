"""Start, inspect, or stop the explicitly authorized local watcher."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[1]
RUN=ROOT/'.runtime/live'
def main():
 p=argparse.ArgumentParser();p.add_argument('action',choices=['start','stop','status']);a=p.parse_args()
 RUN.mkdir(parents=True,exist_ok=True);os.umask(0o077)
 pidfile=RUN/'runner.pid';pid=int(pidfile.read_text()) if pidfile.exists() else None
 running=False
 if pid:
  command=subprocess.run(['ps','-p',str(pid),'-o','command='],capture_output=True,text=True).stdout
  running=str(ROOT/'scripts/phone_watch.py') in command
 if a.action=='stop':
  if running:os.kill(pid,signal.SIGTERM)
  print('stopped' if running else 'not running');return
 if a.action=='start' and not running:
  with (RUN/'runner.log').open('a') as log:
   child=subprocess.Popen([sys.executable,str(ROOT/'scripts/phone_watch.py')],cwd=ROOT,stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
  pidfile.write_text(str(child.pid));pid=child.pid
  subprocess.Popen(['/usr/bin/caffeinate','-i','-w',str(pid)],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
  print(json.dumps({'started_pid':pid}));return
 print(json.dumps({'process_running':running,'pid':pid}))
 if (RUN/'status.json').exists():print((RUN/'status.json').read_text())
if __name__=='__main__':main()
