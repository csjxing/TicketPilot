"""Persistent local Vision OCR worker, with bounded fallback to one-shot OCR."""
import atexit
import json
from pathlib import Path
import select
import subprocess
import time

class VisionReader:
    def __init__(self, binary, fallback):
        self.binary=Path(binary);self.fallback=Path(fallback);self.worker=None
        self.last_ms=None
        atexit.register(self.close)
    def close(self):
        if self.worker is not None:
            p=self.worker;self.worker=None
            if p.poll() is None:
                p.terminate()
                try:p.wait(timeout=1)
                except subprocess.TimeoutExpired:p.kill();p.wait()
            for stream in (p.stdin,p.stdout):
                if stream:stream.close()
    @staticmethod
    def parse(raw):
        rows=json.loads(raw)
        if not isinstance(rows,list):raise ValueError('invalid OCR result')
        for row in rows:
            if not isinstance(row,dict) or not isinstance(row.get('text'),str):raise ValueError('invalid OCR row')
            for key in ['x','y','confidence']:
                value=row.get(key)
                if isinstance(value,bool) or not isinstance(value,(int,float)) or not 0<=value<=1:raise ValueError('invalid OCR geometry')
        return rows
    def read(self,path):
        path=str(Path(path).resolve())
        if '\n' in path or '\r' in path:raise ValueError('invalid image path')
        start=time.monotonic()
        try:
            if self.worker is None or self.worker.poll() is not None:
                self.close()
                self.worker=subprocess.Popen([str(self.binary),'--serve'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1)
            self.worker.stdin.write(path+'\n');self.worker.stdin.flush()
            if not select.select([self.worker.stdout],[],[],5)[0]:raise TimeoutError('OCR timeout')
            rows=self.parse(self.worker.stdout.readline())
        except (OSError,ValueError,TimeoutError):
            self.close()
            r=subprocess.run([str(self.fallback),path],capture_output=True,timeout=15,check=True)
            rows=self.parse(r.stdout)
        self.last_ms=round((time.monotonic()-start)*1000)
        return rows
