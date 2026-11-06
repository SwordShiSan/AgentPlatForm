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
    existing_queues=json.loads(subprocess.check_output(['docker','exec',BROKER,'rabbitmqctl','list_queues','-p','/agentplatform','name','type','--formatter','json']))
    for queue in existing_queues:
        if queue['name']=='ap.asset-extract.v1':assert queue['type']=='classic','Never change the type of a retained queue'
    archive = root / 'mq-topology-history' / str(time.time_ns())
    archive.mkdir(parents=True, mode=0o700)
    shutil.copy2(path, archive / path.name)
    changed = []
    queues = [x for x in data['queues'] if x['vhost']=='/agentplatform' and x['name']=='ap.asset-extract.v1']
    assert len(queues)<=1
    if not queues:data['queues'].append({'name':'ap.asset-extract.v1','vhost':'/agentplatform','durable':True,'auto_delete':False,'arguments':{'x-queue-type':'classic'}})
    binding={'source':'ap.commands.v1','vhost':'/agentplatform','destination':'ap.asset-extract.v1','destination_type':'queue','routing_key':'asset.extract','arguments':{}}
    if binding not in data['bindings']:data['bindings'].append(binding)
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
    subprocess.run(['docker','exec',BROKER,'rabbitmqctl','import_definitions','/etc/rabbitmq/definitions.json'],check=True,capture_output=True)
    compose=root/'compose.json';stage=root/'stage.json'
    for source in [compose,stage]:shutil.copy2(source,archive/source.name)
    config=json.loads(compose.read_text())
    for name in ['api','worker']:config['services'][name]['environment']['SPRING_RABBITMQ_DYNAMIC']='false'
    compose.write_text(json.dumps(config,indent=2));record=json.loads(stage.read_text());record['composeSha256']=hashlib.sha256(compose.read_bytes()).hexdigest();stage.write_text(json.dumps(record,indent=2))
    actual = json.loads(subprocess.check_output(['docker','exec',BROKER,'rabbitmqctl','list_permissions','-p','/agentplatform','--formatter','json']))
    for row in changed:
        match = next(x for x in actual if x['user'] == row['user'])
        assert all(match[key] == row[key] for key in ['configure','write','read'])
    bindings=json.loads(subprocess.check_output(['docker','exec',BROKER,'rabbitmqctl','list_bindings','-p','/agentplatform','source_name','destination_name','routing_key','--formatter','json']))
    assert any(x['source_name']=='ap.commands.v1' and x['destination_name']=='ap.asset-extract.v1' and x['routing_key']=='asset.extract' for x in bindings)
    after_queues=json.loads(subprocess.check_output(['docker','exec',BROKER,'rabbitmqctl','list_queues','-p','/agentplatform','name','type','--formatter','json']))
    assert all(any(y==x for y in after_queues) for x in existing_queues),'Retained queue types must be unchanged'
    result = {'status':'PASS','roles':[x['user'] for x in changed], 'beforeSha256':hashlib.sha256(old).hexdigest(), 'afterSha256':hashlib.sha256(path.read_bytes()).hexdigest(), 'retainedQueueTypesUnchanged':True,'fixedResources':['ap.commands.v1','ap.asset.v1','ap.asset-extract.v1'], 'recordedAt':time.time()}
    (archive/'receipt.json').write_text(json.dumps(result,indent=2)); print(json.dumps(result))

if __name__ == '__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('folder'); apply(parser.parse_args().folder)
