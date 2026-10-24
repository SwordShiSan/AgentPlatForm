import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest

spec=importlib.util.spec_from_file_location('observer',Path(__file__).parents[1]/'scripts/container_observation.py')
observer=importlib.util.module_from_spec(spec);spec.loader.exec_module(observer)
IDENTITY='a'*64


class ObservationTests(unittest.TestCase):
    def run_result(self,code,stdout='',stderr=''):
        return lambda *args,**kwargs:SimpleNamespace(returncode=code,stdout=stdout,stderr=stderr)

    def row(self,running=True):
        return {'Id':IDENTITY,'Image':'sha256:'+'b'*64,'State':{'Running':running,'OOMKilled':False,'Pid':1234 if running else 0}}

    def test_optional_retired_helper_is_absent_without_fabricated_metrics(self):
        self.assertIsNone(observer.inspect_container(IDENTITY,run=self.run_result(1,stderr='Error: No such object: '+IDENTITY)))
        result=observer.process_sample(self.row(False));self.assertIsNone(result['threads'])

    def test_required_service_missing_or_stopped_still_fails(self):
        with self.assertRaisesRegex(observer.ObservationError,'REQUIRED_CONTAINER_MISSING'):
            observer.inspect_container(IDENTITY,True,self.run_result(1,stderr='Error: No such container: '+IDENTITY))
        with self.assertRaisesRegex(observer.ObservationError,'REQUIRED_CONTAINER_STOPPED'):
            observer.inspect_container(IDENTITY,True,self.run_result(0,json.dumps([self.row(False)])))

    def test_daemon_failure_and_oom_are_never_treated_as_normal_retirement(self):
        with self.assertRaisesRegex(observer.ObservationError,'CONTAINER_INSPECTION_UNAVAILABLE'):
            observer.inspect_container(IDENTITY,run=self.run_result(1,stderr='connection unavailable'))
        row=self.row();row['State']['OOMKilled']=True
        with self.assertRaisesRegex(observer.ObservationError,'CONTAINER_OOM_RECORDED'):
            observer.inspect_container(IDENTITY,run=self.run_result(0,json.dumps([row])))

    def test_host_pid_retirement_is_nullable_only_for_optional_workload(self):
        def gone(path):raise FileNotFoundError()
        self.assertIsNone(observer.process_sample(self.row(),read=gone)['threads'])
        with self.assertRaisesRegex(observer.ObservationError,'REQUIRED_PROCESS_METRICS_UNAVAILABLE'):
            observer.process_sample(self.row(),True,gone)

    def test_valid_threads_and_host_permission_failure(self):
        self.assertEqual(12,observer.process_sample(self.row(),True,lambda path:'Name: test\nThreads:\t12\n')['threads'])
        def denied(path):raise PermissionError()
        with self.assertRaisesRegex(observer.ObservationError,'HOST_PROCESS_METRICS_UNAVAILABLE'):
            observer.process_sample(self.row(),read=denied)


if __name__=='__main__':unittest.main()
