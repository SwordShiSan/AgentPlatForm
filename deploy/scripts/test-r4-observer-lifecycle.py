"""Regression for a completed temporary helper observed between ps and inspect."""
from pathlib import Path
import importlib.util,copy,unittest
spec=importlib.util.spec_from_file_location('observer',Path(__file__).with_name('r4-functional-observe.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
def row(identity,running=True,oom=False,exit_code=0,swap=64):
 return {'Id':identity,'State':{'Running':running,'OOMKilled':oom,'ExitCode':exit_code},'HostConfig':{'Memory':64,'MemorySwap':swap}}
class Lifecycle(unittest.TestCase):
 def test_completed_temporary_container_does_not_mean_service_failure(self):
  for exit_code in [0,137,143]:self.assertEqual(['core'],[r['Id'] for r in m.live_resources([row('core'),row('helper',False,exit_code=exit_code)],{'core'})])
 def test_required_container_must_remain_running(self):
  with self.assertRaises(AssertionError):m.live_resources([row('core',False)],{'core'})
 def test_required_container_cannot_disappear(self):
  with self.assertRaises(AssertionError):m.live_resources([row('helper')],{'core'})
 def test_temporary_oom_still_blocks(self):
  with self.assertRaises(AssertionError):m.live_resources([row('core'),row('helper',False,True)],{'core'})
 def test_running_temporary_swap_expansion_still_blocks(self):
  with self.assertRaises(AssertionError):m.live_resources([row('core'),row('helper',swap=-1)],{'core'})
if __name__=='__main__':unittest.main()
