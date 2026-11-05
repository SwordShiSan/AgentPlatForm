"""Persist the exact R5 asset resources in existing production AMQP roles."""
from pathlib import Path
import argparse, hashlib, json, os, re, shutil, subprocess, time

EXTRA = r'^(ap\.commands\.v1|ap\.asset\.v1|ap\.asset-extract\.v1)$'
BROKER = 'agentplatform-env-rabbitmq-1'

def apply(folder):
    root = Path(folder).resolve(strict=True)
    assert re.fullmatch(r'/srv/agentplatform/operations/r5-release-[a-f0-9]{12}-[0-9]{8}', str(root))
    path = Path('/srv/agentplatform/config/rabbitmq-definitions.json')
    old = path.read_bytes(); data = json.loads(old)
    archive = root / 'mq-topology-history' / str(time.time_ns())
    archive.mkdir(parents=True, mode=0o700)
    shutil.copy2(path, archive / path.name)
    changed = []
    for user in ['mq_publisher', 'mq_execution']:
        rows = [x for x in data['permissions'] if x['user'] == user and x['vhost'] == '/agentplatform']
        assert len(rows) == 1
        row = rows[0]
        for key in ['configure', 'write', 'read']:
            if EXTRA not in row[key]: row[key] = '(' + row[key] + ')|(' + EXTRA + ')'
            assert all(re.search(row[key], name) for name in ['ap.commands.v1', 'ap.asset.v1', 'ap.asset-extract.v1'])
        changed.append({k: row[k] for k in ['user', 'configure', 'write', 'read']})
    # Keep inode, mode and owner: Docker bind mounts continue to see this file.
    with path.open('wb') as target:
        target.write(json.dumps(data, indent=2).encode()); target.flush(); os.fsync(target.fileno())
    for row in changed:
        subprocess.run(['docker','exec',BROKER,'rabbitmqctl','set_permissions','-p','/agentplatform',row['user'],row['configure'],row['write'],row['read']], check=True, capture_output=True)
    actual = json.loads(subprocess.check_output(['docker','exec',BROKER,'rabbitmqctl','list_permissions','-p','/agentplatform','--formatter','json']))
    for row in changed:
        match = next(x for x in actual if x['user'] == row['user'])
        assert all(match[key] == row[key] for key in ['configure','write','read'])
    result = {'status':'PASS','roles':[x['user'] for x in changed], 'beforeSha256':hashlib.sha256(old).hexdigest(), 'afterSha256':hashlib.sha256(path.read_bytes()).hexdigest(), 'fixedResources':['ap.commands.v1','ap.asset.v1','ap.asset-extract.v1'], 'recordedAt':time.time()}
    (archive/'receipt.json').write_text(json.dumps(result,indent=2)); print(json.dumps(result))

if __name__ == '__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('folder'); apply(parser.parse_args().folder)
