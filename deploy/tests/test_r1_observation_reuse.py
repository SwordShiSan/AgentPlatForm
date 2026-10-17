import copy,importlib.util,json,tempfile,unittest,uuid
from pathlib import Path

source=Path(__file__).parents[1]/'scripts/r1-observe.py'
spec=importlib.util.spec_from_file_location('r1_observe',source)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class ObservationReuseTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();(self.root/'operations').mkdir()
        self.path=self.root/'operations/prior-observation.json';self.task=str(uuid.uuid4())
        self.usage=[{'status':'COMPLETED','inputTokens':30,'outputTokens':3,'costMicros':351}]
        self.value={'status':'PASS','durationSeconds':1800.14,'model':{'taskId':self.task,'status':'PASS','model':'deepseek-v4-pro','usage':copy.deepcopy(self.usage)}}
        self.status='SUCCEEDED';self.model='deepseek-v4-pro';self.requests=[]
    def request(self,method,path):
        self.assertEqual(method,'GET');self.requests.append(path)
        if path.endswith('/usage'):return 200,{'items':self.usage}
        if path.endswith('/configuration'):return 200,{'values':{'modelName':self.model}}
        return 200,{'status':self.status}
    def load(self):
        self.path.write_text(json.dumps(self.value),encoding='utf-8')
        return module.reused_model(self.path,self.root,self.request)
    def test_exact_persisted_facts_need_only_reads(self):
        model,proof=self.load();self.assertEqual(model,self.value['model']);self.assertEqual(proof['newProviderCalls'],0)
        self.assertEqual(len(self.requests),3);self.assertEqual(len(proof['sha256']),64)
    def test_short_or_failed_window_rejected(self):
        for status,duration in [('FAIL',1800.14),('PASS',1799)]:
            self.value.update(status=status,durationSeconds=duration)
            with self.assertRaises(AssertionError):self.load()
        self.assertEqual(self.requests,[])
    def test_changed_usage_rejected(self):
        self.usage[0]['inputTokens']=31
        with self.assertRaises(AssertionError):self.load()
    def test_unknown_task_rejected(self):
        self.status='UNKNOWN'
        with self.assertRaises(AssertionError):self.load()
    def test_changed_model_rejected(self):
        self.model='deepseek-flash'
        with self.assertRaises(AssertionError):self.load()
    def test_foreign_path_rejected_before_read(self):
        with self.assertRaises(AssertionError):module.reused_model(self.root/'prior-observation.json',self.root,self.request)
        self.assertEqual(self.requests,[])

if __name__=='__main__':unittest.main()
