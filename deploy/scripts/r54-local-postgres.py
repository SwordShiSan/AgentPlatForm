"""Run R54 typed attachment/extraction facts against one isolated local PostgreSQL."""
from pathlib import Path
import hashlib, json, os, secrets, socket, subprocess, time

ROOT=Path(__file__).resolve().parents[2]
DATA=ROOT/'.cache/r54-local/postgres'
DOCKER=['docker','--context','desktop-linux']

def run(args,**kwargs):
    return subprocess.run([str(x) for x in args],capture_output=True,check=True,**kwargs)

def main():
    global DATA
    DATA=DATA/('run-'+str(time.time_ns()))
    DATA.mkdir(parents=True,exist_ok=True)
    port=None
    for candidate in [15454,15455]:
        try:
            with socket.socket() as probe:probe.bind(('127.0.0.1',candidate))
            port=candidate;break
        except OSError:continue
    if port is None:raise RuntimeError('Both isolated R54 PostgreSQL ports are unavailable; do not stop another project')
    password=secrets.token_urlsafe(32)
    secret=DATA/'postgres.env';secret.write_text('POSTGRES_PASSWORD='+password+'\nPOSTGRES_DB=workbench_test\n',encoding='utf-8')
    password_file=DATA/'password';password_file.write_text(password,encoding='utf-8')
    name='agentplatform-r54-postgres-'+secrets.token_hex(5);identity=None
    try:
        identity=run(DOCKER+['run','-d','--name',name,'--label','ap.stage=R54-persistence','--memory','320m','--memory-swap','320m',
            '--cpus','1','--publish','127.0.0.1:'+str(port)+':5432','--env-file',secret,'--tmpfs','/var/lib/postgresql/data:rw,size=256m',
            'pgvector/pgvector:0.8.1-pg17-trixie'],timeout=90).stdout.decode().strip()
        for _ in range(60):
            if subprocess.run(DOCKER+['exec',identity,'pg_isready','-U','postgres','-d','workbench_test'],capture_output=True).returncode==0:break
            time.sleep(1)
        else:raise RuntimeError('Isolated PostgreSQL not ready')
        env=dict(os.environ,JAVA_HOME=str(ROOT/'.tools/jdk-21.0.12.1/jdk-21.0.12.1+1'),
                 R54_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:'+str(port)+'/workbench_test?sslmode=disable',
                 R54_TEST_PASSWORD_FILE=str(password_file),R54_ACCEPTANCE='1')
        env['PATH']=env['JAVA_HOME']+'/bin;'+env['PATH']
        tracked=run(['git','ls-files','-c','-o','--exclude-standard','-z','--','backend'],cwd=ROOT).stdout.decode().split('\0')
        def inputs():return {name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in sorted(set(tracked)-{''}) if (ROOT/name).is_file()}
        before=inputs()
        result=subprocess.run([str(ROOT/'backend/mvnw.cmd'),'-Dtest=AttachmentPostgresTest','-Dsurefire.failIfNoSpecifiedTests=false','test'],
            cwd=ROOT/'backend',env=env,capture_output=True,timeout=300)
        (DATA/'maven.log').write_bytes(result.stdout+result.stderr)
        unchanged=before==inputs()
        receipt={'status':'PASS' if result.returncode==0 and unchanged else 'FAIL','exitCode':result.returncode,'port':port,'physicalRenderer':'NOT_RUN','inputUnchanged':unchanged,
                 'scope':'Real V20-to-V21 migration, attachment ownership/quota/idempotency, extraction/shared-slot/receipt facts',
                 'source':run(['git','rev-parse','HEAD'],cwd=ROOT).stdout.decode().strip(),'workingTree':'DIRTY_IMPLEMENTATION'}
        (DATA/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
        (DATA/'inputs.json').write_text(json.dumps(before,indent=2)+'\n',encoding='utf-8')
        (DATA.parent/'latest.json').write_text(json.dumps({'folder':str(DATA),'status':receipt['status']})+'\n',encoding='utf-8')
        print(json.dumps(receipt))
        if receipt['status']!='PASS':raise RuntimeError('R54 persistence failed or source changed; inspect protected maven.log')
    finally:
        if identity:
            row=json.loads(run(DOCKER+['inspect',identity]).stdout)[0]
            assert row['Config']['Labels'].get('ap.stage')=='R54-persistence'
            run(DOCKER+['rm','-f',identity])
        secret.unlink(missing_ok=True);password_file.unlink(missing_ok=True)

if __name__=='__main__':main()
