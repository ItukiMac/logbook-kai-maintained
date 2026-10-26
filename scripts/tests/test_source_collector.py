import io
from email.message import Message
from http.client import IncompleteRead
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from collect_maintenance_sources import collect, canonical_url, MAX_BYTES

URL='https://zekamashi.net/kancolle-kouryaku/ensei/'
class Response(io.BytesIO):
    status=200
    def __init__(self,body=b'<html>fixture</html>',url=URL):
        super().__init__(body);self.url=url;self.headers=Message();self.headers['Content-Type']='text/html'
    def geturl(self):return self.url

class SourceCollectorTest(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name);self.out=self.root/'evidence'
    def test_capture_is_not_semantic_approval_and_uses_no_credentials(self):
        seen=[]
        def opener(request,timeout):seen.append((request,timeout));return Response()
        r=collect([URL],self.out,open_url=opener)
        self.assertTrue(r['complete']);self.assertFalse(r['approved']);self.assertFalse(r['semantic_reviewed'])
        self.assertEqual(0,r['paid_api_calls']);self.assertEqual(10,seen[0][1])
        self.assertNotIn('Authorization',seen[0][0].headers);self.assertNotIn('Cookie',seen[0][0].headers)
    def test_unsafe_url_types_auth_hosts_queries_never_contact_network(self):
        for value in [123,None,'http://zekamashi.net/x','https://x.com/not_official/status/1',
                      'https://u:p@zekamashi.net/x','https://zekamashi.net.evil/x',URL+'?token=secret',URL+'#x']:
            with self.subTest(value=value),self.assertRaises(ValueError):
                collect([value],self.out,open_url=lambda *_a,**_k:self.fail('Network invoked'))
            self.assertFalse(self.out.exists())
    def test_redirect_oversize_and_binary_responses_rejected(self):
        for i,response in enumerate((Response(url='https://evil.invalid'),Response(b'a'*(MAX_BYTES+1)),Response())):
            if i==2:response.headers.replace_header('Content-Type','application/octet-stream')
            r=collect([URL],self.root/str(i),open_url=lambda *_a,**_k:response)
            self.assertFalse(r['complete']);self.assertEqual('error',r['entries'][0]['status'])
    def test_failed_response_does_not_starve_next_source(self):
        counter=[]
        def opener(req,timeout):
            counter.append(1)
            if len(counter)==1:raise IncompleteRead(b'partial')
            return Response(url=req.full_url)
        r=collect([URL,'https://wikiwiki.jp/kancolle/test'],self.out,open_url=opener)
        self.assertEqual(['error','captured'],[x['status'] for x in r['entries']])
    def test_existing_directory_and_duplicate_requests_rejected(self):
        self.out.mkdir()
        with self.assertRaises(FileExistsError):collect([URL],self.out,open_url=lambda *_a,**_k:self.fail())
        with self.assertRaises(ValueError):collect([URL]*2,self.root/'new')
    def test_japanese_url_is_encoded_without_changing_host(self):
        self.assertEqual('https://wikiwiki.jp/kancolle/%E4%BB%BB%E5%8B%99',canonical_url('https://wikiwiki.jp/kancolle/任務'))

if __name__=='__main__':unittest.main()
