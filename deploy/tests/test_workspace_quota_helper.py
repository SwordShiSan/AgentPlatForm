"""Fail-closed helper boundaries. These tests do not claim native XFS acceptance."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import types
import unittest
import uuid
from unittest.mock import patch

source=Path(__file__).parents[1]/'scripts/workspace-quota-helper.py'
spec=importlib.util.spec_from_file_location('workspace_quota_helper',source)
helper=importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)

class QuotaHelperBoundaryTest(unittest.TestCase):
    def invoke(self,root,existing=False,mounted=True):
        space,workspace,operation,command,mount=[str(uuid.uuid4()) for _ in range(5)]
        if existing:
            previous=os.umask(0o077)
            try:(root/space/workspace/'work').mkdir(parents=True)
            finally:os.umask(previous)
        argv=['helper','--space',space,'--workspace',workspace,'--operation',operation,'--command',command,'--project','200001','--mount-uuid',mount]
        calls=[]
        def native(args):
            calls.append(args)
            if args[0]=='findmnt':return json.dumps({'filesystems':[{'target':str(root),'fstype':'xfs' if mounted else 'ext4','options':'rw,prjquota'}]})
            if args[0]=='xfs_io':return mount
            if args[0]=='xfs_quota' and args[3]=='state -p':return 'Accounting: ON\nEnforcement: ON'
            raise AssertionError('No mutating command may run for a rejected or ambiguous allocation')
        out=io.StringIO()
        real_stat=Path.stat
        def private_fixture_metadata(path,*args,**kwargs):
            value=real_stat(path,*args,**kwargs)
            if os.name!='nt':return value
            # Windows has no POSIX owner/mode. Model that precondition only; Linux checks real metadata.
            fields=list(value);fields[0]&=~0o077;fields[4]=0
            return os.stat_result(fields)
        with patch.object(helper,'ROOT',root),patch.object(helper,'run',native),patch.object(Path,'stat',private_fixture_metadata),patch('sys.argv',argv),contextlib.redirect_stdout(out):
            with self.assertRaises(SystemExit) as stopped:helper.main()
            self.assertEqual(1,stopped.exception.code)
        return json.loads(out.getvalue()),calls
    def test_existing_directory_is_unknown_never_retryable_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            result,calls=self.invoke(Path(folder),existing=True)
            self.assertEqual('UNKNOWN',result['state']);self.assertEqual('MAY_HAVE_STARTED',result['effectState'])
            self.assertFalse(any(c[0]=='xfs_quota' and c[3]!='state -p' for c in calls))
    def test_missing_xfs_is_rejected_without_creating_anything(self):
        with tempfile.TemporaryDirectory() as folder:
            result,calls=self.invoke(Path(folder),mounted=False)
            self.assertEqual('FAILED',result['state']);self.assertEqual('NOT_STARTED',result['effectState'])
            self.assertEqual([],list(Path(folder).iterdir()));self.assertEqual(1,len(calls))
    def test_canonical_uuid_and_fixed_command_reject_user_paths(self):
        for invalid in ['../../workspace','/tmp/arbitrary','abc','00000000-0000-0000-0000-00000000000A']:
            with self.assertRaises(ValueError):helper.identity(invalid)
    def test_hard_limit_report_must_match_exact_project_and_amount(self):
        helper.hard_limit('#200001 4 0 2097152 00 [--------]',200001,2097152)
        for report in ['', '#200002 4 0 2097152', '#200001 4 0 0', '#200001 4 0 2G', '#200001 4 0 2097152\n#200001 4 0 2097152']:
            with self.assertRaises(RuntimeError):helper.hard_limit(report,200001,2097152)
    @unittest.skipUnless(hasattr(os,'geteuid') and os.geteuid()==0,'Actual POSIX UID drop requires isolated Linux root')
    def test_real_0700_ancestor_and_uid_drop_with_modeled_native_quota_calls(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);space,workspace,operation,command,mount=[str(uuid.uuid4()) for _ in range(5)]
            argv=['helper','--space',space,'--workspace',workspace,'--operation',operation,'--command',command,'--project','200001','--mount-uuid',mount]
            def native(args):
                if args[0]=='findmnt':return json.dumps({'filesystems':[{'target':str(root),'fstype':'xfs','options':'rw,prjquota'}]})
                if args[0]=='xfs_io':return mount if args[3]=='fsuuid' else 'fsxattr.projid = 200001'
                if args[3]=='state -p':return 'Accounting: ON\nEnforcement: ON'
                if args[3].startswith('report'):return '#200001 1 0 '+('2097152' if ' -b ' in args[3] else '100000')
                return ''
            out=io.StringIO();previous=os.umask(0o077)
            try:
                with patch.object(helper,'ROOT',root),patch.object(helper,'run',native),patch.object(helper.os,'statvfs',return_value=types.SimpleNamespace(f_bavail=786432,f_frsize=4096)),patch('sys.argv',argv),contextlib.redirect_stdout(out):helper.main()
                result=json.loads(out.getvalue());self.assertEqual('COMMITTED',result['state'])
                self.assertEqual(0o700,(root/space).stat().st_mode&0o777);self.assertEqual(0o700,(root/space/workspace).stat().st_mode&0o777)
                self.assertEqual(10001,(root/space/workspace/'work').stat().st_uid)
                cap_eff=int(next(line.split()[1] for line in Path('/proc/self/status').read_text().splitlines() if line.startswith('CapEff:')),16)
                if cap_eff&6:self.assertFalse((root/space/workspace/'work/.r31-storage-canary').exists())
                else:
                    with self.assertRaises(PermissionError):(root/space/workspace/'work/.r31-storage-canary').exists()
            finally:
                # Restore only this test's empty directory so root-without-DAC_OVERRIDE can clean it.
                target=root/space/workspace/'work'
                if target.exists():os.chown(target,0,0);self.assertEqual([],list(target.iterdir()))
                os.umask(previous)

if __name__=='__main__':unittest.main()
