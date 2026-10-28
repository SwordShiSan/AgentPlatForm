"""Selection and idle guards only; no host switch or container mutation."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('switch',Path(__file__).parents[1]/'scripts/platform-switch.py')
switch=importlib.util.module_from_spec(spec);spec.loader.exec_module(switch)

class ReleaseSelectionTests(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory();self.addCleanup(self.directory.cleanup)
        self.ops=Path(self.directory.name);self.scope=patch.object(switch,'OPS',self.ops);self.scope.start();self.addCleanup(self.scope.stop)
    def select(self,project):
        (self.ops/'release-selection.json').write_text(json.dumps({'appProject':project,'appServices':['runner','api','worker','gateway']}))
    def test_only_the_selected_r4_graph_is_inventory_even_when_r3_is_retained(self):
        self.select('agentplatform-r4-app')
        def row(name,project=None,service=None):
            return {'Id':name,'Name':'/'+name,'Image':'sha256:fixture','Config':{'Labels':{'com.docker.compose.project':project,'com.docker.compose.service':service}},
                    'State':{'Running':False},'HostConfig':{'RestartPolicy':{'Name':'no'},'Memory':1,'MemorySwap':1,'PidsLimit':1},'Mounts':[]}
        rows=[row(name) for name in switch.LEGACY]
        rows += [row('env-'+s,'agentplatform-env',s) for s in switch.NEW_ENV]
        for project in ['agentplatform-r3-app','agentplatform-r4-app']:
            rows += [row(project+'-'+s,project,s) for s in ['runner','api','worker','gateway']]
        with patch.object(switch,'call',return_value='fixture'),patch.object(switch,'inspect',return_value=rows):
            new,old=switch.inventory()
        self.assertEqual(len(new),11);self.assertEqual(len(old),10)
        self.assertEqual({r['project'] for r in new},{'agentplatform-env','agentplatform-r4-app'})
    def test_r4_cannot_skip_file_and_environment_ownership_while_idle(self):
        self.select('agentplatform-r4-app')
        with patch.object(switch,'business_idle',return_value=True),patch.object(switch,'new_sql',return_value='1'):
            self.assertFalse(switch.idle([]))
        with patch.object(switch,'business_idle',return_value=True),patch.object(switch,'new_sql',return_value='0'):
            self.assertTrue(switch.idle([]))
    def test_unreviewed_projects_are_rejected_and_r3_remains_compatible(self):
        self.select('agentplatform-r3-app');self.assertEqual(switch.release_profile()[0],'agentplatform-r3-app')
        self.select('another-project')
        with self.assertRaises(AssertionError):switch.release_profile()

if __name__=='__main__':unittest.main()
