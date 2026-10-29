"""Fresh isolated PostgreSQL schema probe; no production connections or fixture data."""
from pathlib import Path
import hashlib,json,secrets,subprocess,time
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'.cache/r52-local/schema-probe'


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    name='agentplatform-r52-schema-'+secrets.token_hex(6)
    env=OUT/(name+'.env');env.write_text('POSTGRES_PASSWORD='+secrets.token_urlsafe(36)+'\n',encoding='utf-8')
    docker=['docker','--context','desktop-linux']
    def run(args,**kwargs):
        return subprocess.run(docker+args,capture_output=True,timeout=90,**kwargs)
    container=None
    try:
        created=run(['run','-d','--name',name,'--label','ap.stage=R52-schema','--network','none',
            '--memory','320m','--memory-swap','320m','--cpus','1','--env-file',str(env),
            '--tmpfs','/var/lib/postgresql/data:rw,size=192m',
            'pgvector/pgvector:0.8.1-pg17-trixie'])
        assert created.returncode==0,'Schema fixture create failed'
        container=created.stdout.decode().strip()
        for _ in range(60):
            if run(['exec',container,'pg_isready','-U','postgres']).returncode==0:break
            time.sleep(1)
        else:raise RuntimeError('PostgreSQL readiness failed')
        migrations=sorted((ROOT/'backend/platform-app/src/main/resources/db/migration').glob('V*__*.sql'),key=lambda p:int(p.name.split('__')[0][1:]))
        for file in migrations:
            result=run(['exec','-i',container,'psql','-Xq','-v','ON_ERROR_STOP=1','-U','postgres','-d','postgres'],input=b'BEGIN;\n'+file.read_bytes()+b'\nCOMMIT;\n')
            if result.returncode:
                (OUT/'failure.private.log').write_bytes(result.stdout+result.stderr)
                raise RuntimeError('Migration failed: '+file.name+'; inspect protected failure log')
        result={'status':'PASS','scope':'fresh actual PostgreSQL V1-V19 DDL; not application/MQ/XFS acceptance',
                'migrations':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in migrations},'supplierCalls':0}
        (OUT/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'status':'PASS','migrations':len(migrations),'containerStoppedOnExit':True}))
    finally:
        if container:
            run(['stop','--time','10',container])
            run(['rm',container])
        env.unlink(missing_ok=True)


if __name__=='__main__':main()
