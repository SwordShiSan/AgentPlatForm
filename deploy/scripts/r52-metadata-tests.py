"""One independent PG/MyBatis lifecycle regression; never uses a previous stage DB."""
from pathlib import Path
import json,os,secrets,socket,subprocess,time
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'.cache/r52-local'/('metadata-tests-'+str(time.time_ns()))


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    with socket.socket() as check:
        check.settimeout(0.3)
        assert check.connect_ex(('127.0.0.1',15452))!=0,'Test port has an active listener'
    name='agentplatform-r52-metadata-'+secrets.token_hex(6)
    password=OUT/(name+'.password');password.write_text(secrets.token_urlsafe(36),encoding='utf-8')
    settings=OUT/(name+'.env');settings.write_text('POSTGRES_DB=file_test\nPOSTGRES_PASSWORD='+password.read_text()+'\n',encoding='utf-8')
    docker=['docker','--context','desktop-linux'];container=None
    try:
        created=subprocess.run(docker+['run','-d','--name',name,'--label','ap.stage=R52-metadata',
            '--publish','127.0.0.1:15452:5432','--memory','320m','--memory-swap','320m','--cpus','1',
            '--env-file',str(settings),'--tmpfs','/var/lib/postgresql/data:rw,size=192m',
            'pgvector/pgvector:0.8.1-pg17-trixie'],capture_output=True,timeout=60)
        if created.returncode:
            # Docker may have created an exited container before failing the port allocation.
            probe=subprocess.run(docker+['inspect',name],capture_output=True,timeout=10)
            if probe.returncode==0:
                row=json.loads(probe.stdout)[0]
                if row['Config'].get('Labels',{}).get('ap.stage')=='R52-metadata':container=row['Id']
            raise RuntimeError('Independent PG startup failed; precise generated identity retained for cleanup')
        container=created.stdout.decode().strip()
        for _ in range(60):
            if subprocess.run(docker+['exec',container,'pg_isready','-U','postgres'],capture_output=True,timeout=5).returncode==0:break
            time.sleep(1)
        else:raise RuntimeError('PG readiness failed')
        env=dict(os.environ,R52_ACCEPTANCE='1',R52_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:15452/file_test?currentSchema=pf',R52_TEST_PASSWORD_FILE=str(password))
        java=ROOT/'.tools/jdk-21.0.12.1/jdk-21.0.12.1+1';env['JAVA_HOME']=str(java);env['PATH']=str(java/'bin')+os.pathsep+env['PATH']
        args=[str(ROOT/'backend/mvnw.cmd'),'-f',str(ROOT/'backend/pom.xml'),'-o','-pl','platform-app','-am',
            '-Dtest=FilePostgresTest#r52*,FileHistoryServiceTest,WorkspaceOperationsServiceTest','-Dsurefire.failIfNoSpecifiedTests=false','test']
        with (OUT/'tests.private.log').open('wb') as log:
            result=subprocess.run(args,cwd=ROOT,env=env,stdout=log,stderr=log,timeout=240)
        (OUT/'result.json').write_text(json.dumps({'status':'PASS' if result.returncode==0 else 'FAIL','exitCode':result.returncode,
            'scope':'actual PG/Flyway/MyBatis/Spring transaction facts; synthetic runner receipts, not filesystem/runner evidence','supplierCalls':0},indent=2)+'\n',encoding='utf-8')
        print('R52_METADATA_'+('PASS' if result.returncode==0 else 'FAIL'))
        if result.returncode:raise RuntimeError('Inspect first failure in protected metadata-tests/tests.private.log')
    finally:
        if container:
            subprocess.run(docker+['stop','--time','10',container],capture_output=True,timeout=20)
            subprocess.run(docker+['rm',container],capture_output=True,timeout=20)
        password.unlink(missing_ok=True);settings.unlink(missing_ok=True)


if __name__=='__main__':main()
