import sys
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import tempfile
import xml.etree.ElementTree as ET
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
import phone_watch as p
from phone_vision import VisionReader

class PhoneWatchTests(unittest.TestCase):
 def setUp(self):
  self.cfg={'prices':[1680,1380,980],'quantity':1,'max_ticket_total':1680,'spectator':'测试甲','expires_at':'2030-01-01T19:30:00+08:00'}
 def test_dynamic_interval_bounded(self):
  self.assertEqual([p.dynamic_interval(x) for x in [0,1,3,4,12,13,1000]],[4,4,4,5,5,6,6])
 def test_jitter_stays_in_bounds(self):
  for base in [4,5,6]:
   for _ in range(50):self.assertTrue(round(base*.8,2)<=p.jitter_interval(base)<=round(base*1.2,2))
 def test_fast_negative_requires_all_three_explicit_tags(self):
  rows=[{'text':'孙燕姿 杭州 场次 票档 2026-10-11 19:30','x':.5,'y':.2,'confidence':1}]
  for price,x,y in [(980,.2,.5),(1380,.65,.5),(1680,.2,.6)]:
   rows += [{'text':'内场'+str(price)+'元','x':x,'y':y,'confidence':1},{'text':'缺货登记','x':x+.18,'y':y,'confidence':1}]
  self.assertTrue(p.visible_all_sold_out(rows,self.cfg))
  self.assertFalse(p.visible_all_sold_out(rows[:-1],self.cfg))
  rows[0]['text']=rows[0]['text'].replace('10-11','10-09')
  self.assertFalse(p.visible_all_sold_out(rows,self.cfg))
 def test_ocr_error_is_not_empty_inventory(self):
  for raw in ['{"error":"recognition_failed"}','[{"text":"980元","x":2,"y":0.5,"confidence":1}]']:
   with self.assertRaises(ValueError):VisionReader.parse(raw)
 def test_history_appends_and_keeps_unknown_distinct(self):
  with tempfile.TemporaryDirectory() as d,patch.object(p,'RUN',Path(d)):
   w=p.Watcher(self.cfg)
   w.status('out_of_stock',tiers={'980':'out_of_stock'},spectator='PRIVATE')
   w.status('stock_signal',price=980,tiers={'980':'in_stock_signal'})
   w.status('blocked',reason='unreadable_tier')
   history=Path(d,'poll-history.jsonl').read_text()
   rows=[json.loads(line) for line in history.splitlines()]
   self.assertEqual([r['inventory_signal'] for r in rows],['out_of_stock','in_stock_signal','unknown'])
   self.assertEqual([r['event_seq'] for r in rows],[1,2,3])
   self.assertNotIn('PRIVATE',history)
   self.assertEqual(json.loads(Path(d,'status.json').read_text())['state'],'blocked')
 def test_unknown_checkout_never_submits(self):
  self.assertFalse(p.checkout_verified([],self.cfg,980,ET.fromstring('<hierarchy/>')))
 def test_visible_unselected_name_is_not_enough(self):
  text='确认购买 孙燕姿 杭州 2026-10-11 19:30 测试甲 1张 980元 立即提交'
  rows=[{'text':text,'y':.5},{'text':'¥980','y':.94}]
  self.assertFalse(p.checkout_verified(rows,self.cfg,980,ET.fromstring('<hierarchy/>')))
 def test_unknown_sku_is_not_sold_out(self):
  with self.assertRaises(p.Stop):p.sku_options([],ET.fromstring('<hierarchy/>'),self.cfg)
 def test_submission_record_prevents_repeat(self):
  with tempfile.TemporaryDirectory() as d,patch.object(p,'RUN',Path(d)),patch.object(p,'adb') as adb:
   Path(d,'submission.json').write_text('{}')
   with self.assertRaises(p.Stop):p.Watcher(self.cfg).attempt([], {980:{'available':True}})
   adb.assert_not_called()
 def test_deadline_blocks_submit(self):
  cfg={**self.cfg,'expires_at':'2000-01-01T00:00:00+08:00'}
  rows=[{'text':'立即提交','confidence':1,'x':.5,'y':.95}]
  with patch.object(p,'tap_row') as tap:
   with self.assertRaises(p.Stop):p.Watcher(cfg).submit(rows,980)
   tap.assert_not_called()
if __name__=='__main__':unittest.main()
