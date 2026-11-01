import json
import re
import threading
import unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from unittest.mock import Mock
from computer_link_web import create_server

class WebTest(unittest.TestCase):
    def setUp(self):
        self.client=Mock();self.client.request.return_value={'status':'ok','owned':False}
        self.server=create_server(self.client);self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.url='http://127.0.0.1:'+str(self.server.server_port)
        with urlopen(self.url) as r:self.page=r.read().decode();self.headers=r.headers
        self.token=json.loads(re.search(r'const token=("[a-f0-9]+")',self.page).group(1))
    def tearDown(self):self.server.shutdown();self.server.server_close();self.thread.join()
    def post(self,headers=None,op='status',raw=None):
        data=json.dumps({'operation':op}).encode() if raw is None else raw
        h={'Content-Type':'application/json','Origin':self.url,'X-TicketPilot':self.token};h.update(headers or {})
        try:
            with urlopen(Request(self.url+'/command',data=data,headers=h)) as r:return r.status,json.load(r)
        except HTTPError as e:return e.code,json.load(e)
    def test_ui_is_local_and_not_frameable(self):
        self.assertEqual('127.0.0.1',self.server.server_address[0]);self.assertIn('frame-ancestors',self.headers['Content-Security-Policy'])
        self.assertEqual('no-store',self.headers['Cache-Control']);self.assertIn('TicketPilot',self.page)
        self.client.request.assert_not_called()
    def test_request_requires_origin_and_token(self):
        for h in [{'Origin':'https://other.example'},{'X-TicketPilot':'bad'},{'Host':'attacker.example'}]:
            self.assertEqual(403,self.post(h)[0])
        self.client.request.assert_not_called()
    def test_only_fixed_commands_and_small_json(self):
        for op in ['shell','submit','install','pay']:
            self.assertEqual(400,self.post(op=op)[0])
        self.assertEqual(400,self.post(raw=b'x'*5000)[0]);self.client.request.assert_not_called()
    def test_real_command_is_deliberate_and_single(self):
        self.assertEqual(200,self.post()[0]);self.client.request.assert_called_once_with('status')
    def test_errors_do_not_return_raw_details(self):
        self.client.request.side_effect=RuntimeError('private address and key')
        status,body=self.post();self.assertEqual(400,status);self.assertNotIn('private',json.dumps(body))

if __name__=='__main__':unittest.main()
