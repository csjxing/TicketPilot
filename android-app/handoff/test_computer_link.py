import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from computer_link import Companion, LinkError, address, save_private

class ComputerLinkTest(unittest.TestCase):
    def test_rejects_public_and_shell_addresses(self):
        for v in ['', '8.8.8.8:5555', '127.0.0.1:5555', 'phone:1234', '192.168.1.2:0',
                  '192.168.1.2:65536', '192.168.1.2:33;echo', '192.168.1.2:１２']:
            with self.assertRaises(LinkError): address(v)
        self.assertEqual('192.168.1.2:1234', address('192.168.1.2:1234'))
        self.assertEqual('[fd12::1]:4321', address('[fd12::1]:4321'))
    def client(self, folder):
        with patch('computer_link.shutil.which',return_value='/safe/adb'):
            c=Companion(state_dir=folder)
        c.state.update(endpoint='192.168.1.2:1234',fingerprint='expected')
        return c
    def test_pair_code_stdin_and_not_persisted(self):
        with tempfile.TemporaryDirectory() as d:
            c=self.client(d)
            with patch.object(c,'run',return_value='Successfully paired') as run:
                c.pair('192.168.1.2:1234','123456')
                self.assertNotIn('123456',run.call_args.args[0])
                self.assertEqual('123456\n',run.call_args.args[1])
            self.assertFalse(c.file.exists())
    def test_other_phone_rejected_before_broadcast(self):
        with tempfile.TemporaryDirectory() as d:
            c=self.client(d)
            with patch.object(c,'identity',return_value='other'),patch.object(c,'run') as run:
                with self.assertRaises(LinkError):c.request('claim')
                run.assert_not_called()
    def test_no_arbitrary_actions(self):
        with tempfile.TemporaryDirectory() as d:
            c=self.client(d)
            for op in ['tap','submit','pay','shell','install']:
                with self.assertRaises(LinkError):c.request(op)
    def test_receipt_must_match_current_request_and_keep_endpoint_private(self):
        with tempfile.TemporaryDirectory() as d:
            c=self.client(d);responses=iter(['broadcast','{"request":"old","status":"ok"}',
                                          '{"schema":1,"request":"'+'a'*32+'","status":"ok","armed":false}'])
            with patch.object(c,'identity',return_value='expected'),patch.object(c,'run',side_effect=lambda *a,**k:next(responses)),patch('computer_link.uuid.uuid4') as uid,patch('computer_link.time.sleep'):
                uid.return_value.hex='a'*32
                r=c.request('status')
                self.assertEqual({'schema':1,'status':'ok','armed':False},r)
                self.assertNotIn('192.168',json.dumps(r))
    def test_matched_failure_is_not_retried(self):
        with tempfile.TemporaryDirectory() as d:
            c=self.client(d)
            with patch.object(c,'identity',return_value='expected'),patch.object(c,'run',side_effect=['broadcast','{"schema":1,"request":"'+'a'*32+'","status":"denied"}']) as run,patch('computer_link.uuid.uuid4') as uid:
                uid.return_value.hex='a'*32
                with self.assertRaises(LinkError):c.request('claim')
                self.assertEqual(2,run.call_count)
    def test_private_state_atomic_write(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'state.json';save_private(p,{'owner':'a'*32})
            self.assertEqual({'owner':'a'*32},json.loads(p.read_text()))
            self.assertEqual([],list(Path(d).glob('*.tmp-*')))

if __name__=='__main__':unittest.main()
