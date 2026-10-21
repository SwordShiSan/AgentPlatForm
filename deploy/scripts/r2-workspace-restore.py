"""Trusted host checks on a private restored XFS copy, never the source volume."""
import argparse
import errno
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def verified_mount(root):
    root = root.resolve(strict=True)
    assert re.fullmatch(r'/srv/agentplatform/operations/r2-restore-\d{8}T\d{6}Z', str(root))
    workspace = root / 'workspaces'
    rows = json.loads(subprocess.check_output(['findmnt', '-J', '--target', str(workspace)], text=True))['filesystems']
    assert len(rows) == 1
    row = rows[0]
    assert row['target'] == str(workspace) and row['fstype'] == 'xfs' and 'prjquota' in row['options'], 'MOUNT_GUARD'
    actual = subprocess.check_output(['findmnt', '-n', '-o', 'UUID', '--target', str(workspace)], text=True).strip()
    assert actual == (root / 'quota/filesystem.uuid').read_text().strip(), 'UUID_GUARD'
    original = Path('/srv/agentplatform/quota/filesystem.uuid').read_text().strip()
    assert actual != original, 'Original source UUID must never be reused for normal restore mounting'
    return workspace


def quota(root):
    workspace = verified_mount(root)
    assert shutil.disk_usage(workspace).free > 2 * 1024**3
    folder = workspace / 'r2-private-quota-check'
    assert not folder.exists()
    folder.mkdir()
    os.chown(folder, 10001, 10001)
    def xfs(command):
        subprocess.run(['xfs_quota', '-x', '-c', command, str(workspace)], check=True, capture_output=True)
    try:
        xfs('project -s -p ' + str(folder) + ' 200002')
        xfs('limit -p bhard=128k ihard=100000 200002')
        # The child drops host root privileges; actual writes must hit both quotas.
        script = '''import os,errno,pathlib,sys
os.chdir(sys.argv[1])
os.setgroups([]);os.setgid(10001);os.setuid(10001)
p=pathlib.Path('.');mode=sys.argv[2];denied=False
if mode=='bytes':
 try:
  with (p/'bytes').open('wb') as f:os.posix_fallocate(f.fileno(),0,256*1024)
 except OSError as e:
  assert e.errno in (errno.ENOSPC,errno.EDQUOT);denied=True
 assert denied,'Byte quota did not refuse allocation'
 (p/'bytes').unlink(missing_ok=True)
else:
 try:
  for i in range(24):(p/str(i)).touch()
 except OSError as e:
  assert e.errno in (errno.ENOSPC,errno.EDQUOT);denied=True
 assert denied,'Inode quota did not refuse file creation'
'''
        subprocess.run(['python3', '-c', script, str(folder), 'bytes'], check=True, capture_output=True)
        xfs('limit -p bhard=0 ihard=8 200002')
        subprocess.run(['python3', '-c', script, str(folder), 'inodes'], check=True, capture_output=True)
    finally:
        for path in folder.iterdir():
            assert path.is_file() and not path.is_symlink()
            path.unlink()
        folder.rmdir()
        xfs('limit -p bhard=0 ihard=0 200002')
    print('PASS restored-copy byte/inode quota denial; source untouched')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['verify-mount', 'quota'])
    parser.add_argument('root', type=Path)
    args = parser.parse_args()
    quota(args.root) if args.mode == 'quota' else verified_mount(args.root)
