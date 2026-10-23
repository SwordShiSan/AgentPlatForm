"""Fixed R3.2 process cases, called only by the protected serial maintenance controller.

No standalone/public endpoint. Faulted fixture databases remain independently
quarantined; the original database OID is restored without resetting unknowns.
"""
from pathlib import Path
import copy
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
import uuid


def run(context):
    root, op, plan = context['ROOT'], context['OP'], context['PLAN']
    call, sql, save, progress = (context[n] for n in ['call', 'sql', 'save', 'progress'])
    base, cid, api = context['BASE'], context['cid'], context['api']
    assert re.fullmatch('/srv/agentplatform/acceptance/r32-full-[a-z0-9-]+', str(root))
    assert op.resolve().is_relative_to(root.resolve())
    assert api(context['base']+'/environment')[2]['state'] == 'STOPPED'
    assert sql("SELECT count(*) FROM pf.workspace_environment_use WHERE status IN('CLAIMED','RUNNING','UNKNOWN');") == '0'
    assert sql('SELECT environment_id IS NULL FROM pf.workspace_sandbox_slot WHERE id=1;') == 't'
    normal = json.loads((op/'compose.json').read_text())
    stamp = str(int(time.time())); canonical = 'r32_canonical_'+stamp
    canonical_oid = sql('SELECT oid FROM pg_database WHERE datname=current_database();')
    clones = []; renamed = False; active_case = None

    def admin(query):
        return call(['docker','exec','-i',cid('postgres'),'psql','--no-psqlrc','-U','postgres','-d','postgres','-v','ON_ERROR_STOP=1','-t','-A'],query)

    def stop_apps():
        call(base+['stop','api','gateway','worker','worker-2','runner'],timeout=120)
        for name in ['api','gateway','worker','worker-2','runner']:
            identity = cid(name)
            if identity:
                state = json.loads(call(['docker','inspect',identity]))[0]['State']
                assert not state['Running'] and state['Pid'] == 0

    def disconnects():
        for _ in range(30):
            if admin("SELECT count(*) FROM pg_stat_activity WHERE datname='environment_test';") == '0':return
            time.sleep(.2)
        raise AssertionError('Fixture connections remain; no forced backend termination')

    def empty_queue():
        output = call(['docker','exec',cid('rabbitmq'),'rabbitmqctl','-q','list_queues','-p','/agentplatform','name','messages_ready','messages_unacknowledged'],timeout=30)
        lines = [line.split() for line in output.splitlines() if line.startswith('ap.workspace.environment.v2\t')]
        assert len(lines) == 1 and lines[0][1:] == ['0','0'], 'Retain pending messages; never purge to pass'

    def preserve_case(name):
        stop_apps();empty_queue()
        dump = op/'reports'/(name+'.private.dump')
        with dump.open('xb') as stream:
            subprocess.run(['docker','exec',cid('postgres'),'pg_dump','--no-password','-U','postgres','-d','environment_test','-Fc'],stdout=stream,check=True,timeout=90)
        oid = sql('SELECT oid FROM pg_database WHERE datname=current_database();')
        admin('ALTER DATABASE environment_test ALLOW_CONNECTIONS false;');disconnects()
        admin('ALTER DATABASE environment_test RENAME TO '+name+';')
        clones.append({'name':name,'oid':oid,'dumpSha256':hashlib.sha256(dump.read_bytes()).hexdigest(),'connectionsDisabled':True})
        save('creation-clone-index',{'canonicalOid':canonical_oid,'canonicalName':canonical,'clones':clones})

    def import_proof(owner, case):
        folder=op/'ops'/case;folder.mkdir(mode=0o700,parents=True);os.chown(folder,10001,10001)
        metadata=folder/'metadata.json';metadata.write_text(json.dumps({k:owner[k] for k in ['runnerInstanceId','containerId','imageId','startedAt']}));metadata.chmod(0o600)
        proof=folder/'proof.json'
        call(['python3',str(op/'attest-workspace-runner-stop.py'),'--metadata-file',str(metadata),'--host-key-file',str(root/'host-proof-key'),'--output',str(proof)])
        for source,name in [(proof,'receipt.json'),(root/'host-proof-key','host-key'),(root/'ops-password','password')]:
            target=folder/name;target.write_bytes(source.read_bytes());os.chown(target,10001,10001);target.chmod(0o400)
        call(['docker','run','--rm','--name',plan['project']+'-proof-'+case,'--label','r32.root='+str(root),
              '--network',plan['project']+'-control','--user','10001:10001','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true',
              '--memory','320m','--memory-swap','320m','--pids-limit','128','--cpus','1','--tmpfs','/tmp:rw,noexec,nosuid,size=32m,uid=10001,gid=10001,mode=700',
              '--mount','type=bind,src='+str(folder)+',dst=/run/ops,readonly','-e','SPRING_PROFILES_ACTIVE=workspace-environment-ops',
              '-e','PLATFORM_WORKSPACE_ENVIRONMENT_OPS_ENABLED=true','-e','PLATFORM_STORAGE_USER=ap_workspace_ops',
              '-e','PLATFORM_STORAGE_PASSWORD_FILE=/run/ops/password','-e','PLATFORM_STORAGE_JDBC_URL=jdbc:postgresql://postgres:5432/environment_test?connectTimeout=2&socketTimeout=5',
              '--entrypoint','java',context['ids']['api'],'-Xms16m','-Xmx112m','-jar','/app/platform.jar','--workspace-environment-action=import-runner-proof',
              '--proof-file=/run/ops/receipt.json','--host-key-file=/run/ops/host-key'],timeout=100)
        assert sql("SELECT count(*) FROM pf.workspace_runner_stop_proof WHERE runner_instance_id='"+owner['runnerInstanceId']+"';") == '1'

    try:
        stop_apps();disconnects();empty_queue()
        size=int(admin("SELECT pg_database_size('environment_test');"))
        assert shutil.disk_usage('/').free >= 5*1024**3+size*4+64*1024**2
        admin('ALTER DATABASE environment_test ALLOW_CONNECTIONS false;');disconnects()
        admin('ALTER DATABASE environment_test RENAME TO '+canonical+';');renamed=True
        for index,point in enumerate(['environment-before-create','environment-created-before-start','environment-started-before-readiness']):
            case='c'+str(index+1);active_case='r32_fault_'+stamp+'_'+case
            progress('CREATION_FAULT_'+case)
            admin('CREATE DATABASE environment_test TEMPLATE '+canonical+';')
            admin("COMMENT ON DATABASE environment_test IS '"+plan['project']+"';")
            config=copy.deepcopy(normal)
            for worker in ['worker','worker-2']:
                config['services'][worker]['mem_limit']='288m';config['services'][worker]['memswap_limit']='288m'
                config['services'][worker]['entrypoint']=['java','-Xms32m','-Xmx128m','-jar','/app/platform.jar']
                if 'deploy' in config['services'][worker]:config['services'][worker].pop('deploy')
            # Existing fixture sessions avoid a concurrent password hash here.
            # Includes both workers, guest512/helper64/runtime128 below2887MiB.
            config['services']['api']['mem_limit']='256m';config['services']['api']['memswap_limit']='256m'
            config['services']['api']['entrypoint']=['java','-Xms32m','-Xmx128m','-jar','/app/platform.jar']
            config['services']['api'].pop('deploy',None)
            env=config['services']['runner']['environment'];env['SPRING_PROFILES_ACTIVE']='workspace-runner,workspace-fixture'
            env['PLATFORM_WORKSPACE_FIXTURE_STAGE']='r32';env['PLATFORM_WORKSPACE_FIXTURE_POINT']=point
            fault=op/(case+'-compose.json');fault.write_text(json.dumps(config,indent=2))
            commands=['docker','compose','-p',plan['project'],'-f',str(fault)]
            call(commands+['--profile','application','up','-d','--wait','--wait-timeout','180','api','gateway','worker','worker-2','runner'],timeout=230)
            context['sample']()
            view=api(context['base']+'/environment')[2];assert view['state']=='STOPPED'
            accepted=api(context['base']+'/environment',{}, {**context['headers'],'Idempotency-Key':str(uuid.uuid4()),'If-Match':'"'+str(view['version'])+'"'})[2]
            runner=cid('runner');deadline=time.monotonic()+65
            while True:
                marker=subprocess.run(['docker','exec',runner,'test','-f','/tmp/workspace-fixture-ready'],capture_output=True,timeout=5)
                if marker.returncode==0:break
                assert time.monotonic()<deadline,'Creation fault marker missing';time.sleep(.05)
            operation=accepted['operationId']
            owner=json.loads(sql("SELECT json_build_object('runnerInstanceId',r.id,'containerId',r.container_id,'imageId',r.image_id,'startedAt',r.started_at,'environmentId',o.environment_id,'generation',o.generation,'commandId',o.command_id,'operationId',o.id)::text FROM pf.workspace_environment_operation o JOIN pf.workspace_environment_command c ON c.operation_id=o.id JOIN pf.workspace_runner_instance r ON r.id=c.runner_instance_id WHERE o.id='"+operation+"';"))
            assert owner['containerId']==runner
            call(['docker','kill','--signal','KILL',runner])
            call(commands+['stop','api','gateway'],timeout=60)
            name='ap-sandbox-'+owner['environmentId']+'-'+str(owner['generation'])
            found=subprocess.run(['docker','inspect',name],capture_output=True,text=True,timeout=15)
            physical=None
            if index==0:
                assert found.returncode!=0 and 'No such' in found.stderr
            else:
                assert found.returncode==0;physical=json.loads(found.stdout)[0]
                assert physical['Image']==context['ids']['sandbox'] and physical['Config']['Labels']['ap.command']==owner['commandId']
                assert physical['State']['Running'] == (index==2)
                assert physical['State']['Status']==('running' if index==2 else 'created')
            save(case+'-before-recovery',{'point':point,'owner':owner,'physical':None if physical is None else {'id':physical['Id'],'image':physical['Image'],'state':physical['State']},'twoWorkers':True})
            import_proof(owner,case)
            # Replacement management process only queries the existing command.
            config['services']['runner']['environment']=copy.deepcopy(normal['services']['runner']['environment'])
            fault.write_text(json.dumps(config,indent=2));call(commands+['--profile','application','up','-d','runner'],timeout=120)
            until=time.monotonic()+150
            while True:
                state=sql("SELECT state FROM pf.workspace_environment WHERE workspace_id='"+context['workspaceId']+"';")
                if index<2 and state=='BLOCKED':break
                if index==2 and state in ['READY','STOPPED']:break
                assert time.monotonic()<until,'Original creation command failed to reach its expected factual state';time.sleep(1)
            assert sql("SELECT count(*) FROM pf.workspace_environment_command WHERE operation_id='"+operation+"';")=='1'
            assert sql("SELECT count(*) FROM pf.workspace_environment_inbox WHERE operation_id='"+operation+"';")=='1'
            assert sql("SELECT count(DISTINCT owner_id) FROM pf.workspace_environment_operation WHERE id='"+operation+"';")=='1'
            if index<2:assert sql('SELECT environment_id IS NOT NULL FROM pf.workspace_sandbox_slot WHERE id=1;')=='t'
            if physical is not None:
                latest=json.loads(call(['docker','inspect',physical['Id']]))[0]
                if latest['State']['Running']:call(['docker','stop','-t','3',physical['Id']],timeout=20)
                latest=json.loads(call(['docker','inspect',physical['Id']]))[0]
                assert not latest['State']['Running'] and latest['State']['Pid']==0
                if index==1:assert latest['State']['Status']=='created' and latest['State']['StartedAt'].startswith('0001')
                else:assert latest['State']['FinishedAt']>latest['State']['StartedAt']
                call(['docker','rm',physical['Id']])
            else:
                assert subprocess.run(['docker','inspect',name],capture_output=True,timeout=10).returncode!=0
            save(case+'-result',{'status':'PASS','point':point,'operationId':operation,'finalState':state,'oneOwner':True,'oneInbox':True,'oneCommand':True,'createdContainers':0 if index==0 else 1,'noReplay':True,'unknownCapacityRetained':index<2,'supplierCalls':0})
            preserve_case(active_case);active_case=None
        save('creation-fault-result',{'status':'PASS','cases':3,'twoWorkers':True,'canonicalOid':canonical_oid,'clones':clones,'supplierCalls':0})
    finally:
        actual_name=admin('SELECT datname FROM pg_database WHERE oid='+canonical_oid+';')
        if actual_name==canonical:
            stop_apps();disconnects()
            if admin("SELECT count(*) FROM pg_database WHERE datname='environment_test';")=='1':
                preserve_case(active_case or ('r32_incomplete_'+stamp))
            admin('ALTER DATABASE '+canonical+' RENAME TO environment_test;')
            admin('ALTER DATABASE environment_test ALLOW_CONNECTIONS true;')
            assert sql('SELECT oid FROM pg_database WHERE datname=current_database();')==canonical_oid
            assert sql("SELECT count(*) FROM pf.workspace_environment_use WHERE status IN('CLAIMED','RUNNING','UNKNOWN');")=='0'
            save('creation-canonical-restored',{'originalOid':canonical_oid,'sameOid':True,'faultedClonesPreserved':clones})
        elif actual_name=='environment_test':
            admin('ALTER DATABASE environment_test ALLOW_CONNECTIONS true;')
        else:
            raise AssertionError('Canonical fixture database identity changed; preserve all records for host recovery')
