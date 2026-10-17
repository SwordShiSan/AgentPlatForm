"""Protected one-time R1 cloud provisioning. Key input is stdin, never argv/logs."""
import json,os,re,secrets,subprocess,sys,uuid
from pathlib import Path

root=Path('/srv/agentplatform')
assert (root/'deploy/r1-enabled').is_file()
os.umask(0o077)
private=root/'operations/r1-accounts';private.mkdir(mode=0o700,exist_ok=True)
assert private.resolve().is_relative_to(root/'operations')
env=dict(os.environ,PLATFORM_ROOT=str(root))
compose=['docker','compose','--env-file',str(root/'deploy/images.env'),'-p','agentplatform-app',
    '-f',str(root/'deploy/compose.app.yaml'),'-f',str(root/'deploy/compose.production.app.yaml'),
    '-f',str(root/'deploy/compose.r1-http.app.yaml'),'--profile','r1-ops']
def run(command,**kwargs):
    result=subprocess.run(command,env=env,capture_output=True,text=True,timeout=120,**kwargs)
    if result.returncode:
        (private/'last-operation-private.log').write_text(result.stdout+'\n'+result.stderr)
        raise RuntimeError('Protected provisioning operation failed; see private operation log')
    return result.stdout
def sql(value):
    return run(['docker','exec','-i','agentplatform-env-postgres-1','psql','-XAt','-U','ap_admin','-d','agentplatform','-v','ON_ERROR_STOP=1'],input=value)
def operator(args,secret=None,profile='chat-ops'):
    command=compose+['run','--rm','-T','-e','SPRING_PROFILES_ACTIVE='+profile]
    if secret is None:
        return run(command+['--interactive=false','operator',*args],stdin=subprocess.DEVNULL)
    shell='umask 077; cat > /tmp/operator-input; java -Xms16m -Xmx160m -jar /app/platform.jar "$@"; status=$?; rm -f /tmp/operator-input; exit "$status"'
    return run(command+['--entrypoint','sh','operator','-c',shell,'operator',*args],input=secret)
for name in ['chat_master','chat_cursor']:
    file=root/'secrets'/name
    if not file.exists():
        with file.open('xb') as stream:stream.write(os.urandom(32))
        file.chmod(0o400);os.chown(file,10001,10001)
    assert file.stat().st_size==32 and not file.stat().st_mode&0o077
file=private/'accounts.json'
if not file.exists():
    file.write_text(json.dumps({'accounts':[{'username':name,'password':secrets.token_urlsafe(32),'requestId':str(uuid.uuid4()),'action':action} for name,action in [('platformadmin','bootstrap'),('r1acceptance','create')]]},indent=2)+'\n')
    file.chmod(0o600)
accounts=json.loads(file.read_text())['accounts']
for account in accounts:
    operator(['--identity-action='+account['action'],'--username='+account['username'],'--request-id='+account['requestId'],'--password-file=/tmp/operator-input'],account['password'],profile='identity-ops')
assert sql('SELECT count(*) FROM pf.conversation;').strip()=='0', 'First provisioning expects no migrated test history'
record=private/'platform-credential-id'
if not record.exists():
    value=sys.stdin.read(8193).strip()
    assert value and len(value)<=4096 and not any(c.isspace() for c in value),'Private key input required'
    output=operator(['--chat-action=import-credential','--scope=PLATFORM','--key-file=/tmp/operator-input'],value)
    value=None
    match=re.search(r'Credential imported: ([0-9a-f-]{36})',output);assert match
    record.write_text(match.group(1)+'\n');record.chmod(0o600)
credential=str(uuid.UUID(record.read_text().strip()))
models=[('0199e000-0000-7000-8000-000000000002','deepseek-flash',2000000,8000000),('0199e000-0000-7000-8000-000000000003','deepseek-v4-pro',9000000,27000000)]
for model,name,price_in,price_out in models:
    sql("INSERT INTO pf.model_definition(id,model_name,endpoint_alias,input_micros_per_million,output_micros_per_million,pricing_version) VALUES('"+model+"','"+name+"','DEEPSEEK',"+str(price_in)+","+str(price_out)+",'"+name+"-2026-10-17-peak-bound') ON CONFLICT(id) DO NOTHING;")
    for account in accounts:
        identity=sql("SELECT u.id::text||','||s.id::text FROM pf.app_user u JOIN pf.space s ON s.owner_user_id=u.id WHERE u.username_norm='"+account['username']+"' AND s.type='PERSONAL';").strip()
        user,space=identity.split(',');uuid.UUID(user);uuid.UUID(space)
        operator(['--chat-action=bind-model','--source-policy=PLATFORM','--credential-id='+credential,'--model-id='+model,'--user-id='+user,'--space-id='+space])
output=operator(['--chat-action=verify-restored-credentials'])
assert 'Restored credential authentication/decryption PASS' in output
print(json.dumps({'status':'PASS','accounts':2,'initialConversations':0,'platformCredentialEncrypted':True,'providerCalls':0,'privateCredentialDelivery':str(file)}))
