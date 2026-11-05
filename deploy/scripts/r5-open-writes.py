"""Open the verified R5 gateway and install its exact platform-switch selection.

The durable opening intent disables old-snapshot rollback before any public
write can arrive. Subsequent repair must retain newly accepted business data.
"""
from pathlib import Path
import argparse,fcntl,hashlib,importlib.util,json,re,shutil,subprocess,time


def main():
    parser=argparse.ArgumentParser();parser.add_argument('folder',type=Path);parser.add_argument('--main',required=True);args=parser.parse_args()
    root=args.folder.resolve(strict=True);assert root.parent==Path('/srv/agentplatform/operations') and root.name.startswith('r5-release-')
    assert re.fullmatch('[a-f0-9]{40}',args.main)
    ops=Path('/srv/agentplatform/operations/platform-switch')
    with (ops/'switch.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        assert not (root/'writes-open-intent.json').exists() and not (root/'writes-open.json').exists()
        for gate in ['smoke','observation','restore','rollback-drill','redeploy','final-smoke','final-observation']:
            assert json.loads((root/(gate+'.json')).read_text())['status']=='PASS',gate+' is not PASS'
        stage=json.loads((root/'stage.json').read_text());config=json.loads((root/'compose.json').read_text())
        assert hashlib.sha256((root/'compose.json').read_bytes()).hexdigest()==stage['composeSha256']
        assert config['name']=='agentplatform-r5-app' and stage['targetMigration']==21
        helper=root/'platform-switch.py';assert helper.is_file();compile(helper.read_text(),helper.name,'exec')
        intended={'main':args.main,'previousComposeSha256':stage['composeSha256'],'helperSha256':hashlib.sha256(helper.read_bytes()).hexdigest(),'time':time.time(),'rule':'No old-snapshot rollback after this opening intent'}
        (root/'writes-open-intent.json').write_text(json.dumps(intended,indent=2))
        shutil.copy2(root/'compose.json',root/'compose-maintenance.json');shutil.copy2(root/'stage.json',root/'stage-maintenance.json')
        for port in config['services']['gateway']['ports']:port['host_ip']='0.0.0.0'
        (root/'compose.json').write_text(json.dumps(config,indent=2));stage['composeSha256']=hashlib.sha256((root/'compose.json').read_bytes()).hexdigest()
        stage.update(maintenanceOnly=False,realWritesOpened=True,releaseMain=args.main);(root/'stage.json').write_text(json.dumps(stage,indent=2))
        subprocess.run(['docker','compose','-p','agentplatform-r5-app','-f',str(root/'compose.json'),'--profile','application','up','-d','--no-deps','gateway'],check=True,capture_output=True,timeout=60)
        shutil.copy2(ops/'platform-switch.py',root/'platform-switch.previous.py');shutil.copy2(ops/'release-selection.json',root/'release-selection.previous.json')
        shutil.copy2(helper,ops/'platform-switch.py')
        ids=[]
        for project in ['agentplatform-env','agentplatform-r5-app']:ids+=subprocess.check_output(['docker','ps','-q','--no-trunc','--filter','label=com.docker.compose.project='+project],text=True).split()
        rows=json.loads(subprocess.check_output(['docker','inspect',*ids]));assert len(rows)==11 and all(row['State']['Running'] and not row['State']['OOMKilled'] for row in rows)
        profile=json.loads((root/'r5-release-profile.json').read_text())
        selection={'release':'R5','main':args.main,'appProject':profile['applicationProject'],'appServices':profile['applicationServices'],'filesystemUuid':stage['productionUuid'],'loopDevice':stage['loop'],'migration':21,'runtimeBaseline':1,'configurationFiles':[str(root/'compose.json'),str(root/'r5-release-profile.json')],'helperSha256':intended['helperSha256'],'selectedAt':time.time()}
        # Install the selector first so the reviewed helper inventories R5 rather
        # than mistaking its retained R4 rollback containers for the chosen stack.
        (ops/'release-selection.json').write_text(json.dumps(selection,indent=2))
        spec=importlib.util.spec_from_file_location('switch',ops/'platform-switch.py');switch=importlib.util.module_from_spec(spec);spec.loader.exec_module(switch)
        new,legacy=switch.inventory();assert len(new)==11 and all(row['running'] for row in new) and all(not row['running'] for row in legacy)
        selection['containers']=new;switch.write(ops/'release-selection.json',selection)
        assert switch.http('/api/v1/readiness',host='124.222.55.140')[0]==200
        active={'status':'PASS','activePlatform':'new','release':'R5','main':args.main,'runningContainers':11,'selectedReleaseSha256':hashlib.sha256((ops/'release-selection.json').read_bytes()).hexdigest(),'time':time.time(),'supplierCalls':0}
        switch.write(ops/'active-switch.json',active)
        result={'status':'PASS','main':args.main,'source':stage['source'],'migration':21,'runtimeBaseline':1,'configurationSha256':stage['composeSha256'],'helperSha256':intended['helperSha256'],'realWritesOpened':True,'productionTag':'production-r5-'+args.main[:12],'supplierCalls':0,'containers':[{'id':row['Id'],'image':row['Image'],'service':row['Config']['Labels']['com.docker.compose.service']} for row in rows]}
        (root/'writes-open.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))


if __name__=='__main__':main()
