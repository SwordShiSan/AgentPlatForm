"""R0.3 explicit real-service fixtures, not production scheduler/asset use cases."""
import concurrent.futures,hashlib,json,os,pathlib,socket,struct,threading,time,uuid
import boto3,pika,psycopg
from botocore.config import Config
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials
from sts_denial import validate_denial
import urllib.error,urllib.parse,urllib.request

reports=[]
def case(name,run):
 try:
  run();reports.append({'case':name,'status':'PASS'});print(name+':PASS',flush=True)
 except Exception as e:
  reports.append({'case':name,'status':'FAIL','exception':type(e).__name__})
  print(name+':FAIL:'+type(e).__name__,flush=True);raise
def secret(name):return (pathlib.Path('/run/secrets')/name).read_text().strip()
def db(user='ap_api'):
 password=secret('pg_migration' if user=='ap_migration' else 'pg_api')
 return psycopg.connect(host='postgres',dbname='agentplatform',user=user,password=password,connect_timeout=3)
def mq(user='mq_publisher'):
 return pika.BlockingConnection(pika.ConnectionParameters('rabbitmq',virtual_host='/agentplatform',credentials=pika.PlainCredentials(user,secret(user)),heartbeat=15,blocked_connection_timeout=3,socket_timeout=3,connection_attempts=1))
def publish_with_lost_confirm(body,event):
 # Real protocol fault: forward handshake/publication, drop broker Basic.Ack,
 # then close the publisher socket. Do not record raw frames or credentials.
 listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen(1);port=listener.getsockname()[1]
 dropped=threading.Event();done=threading.Event()
 def recv_n(s,n):
  data=b''
  while len(data)<n:
   part=s.recv(n-len(data))
   if not part:raise EOFError()
   data+=part
  return data
 def proxy():
  client,addr=listener.accept();upstream=socket.create_connection(('rabbitmq',5672),3)
  def requests():
   try:
    while not done.is_set():
     data=client.recv(65536)
     if not data:break
     upstream.sendall(data)
   except OSError:pass
  forward=threading.Thread(target=requests,daemon=True);forward.start()
  try:
   while not done.is_set():
    header=recv_n(upstream,7);frame,channel,length=struct.unpack('!BHI',header);payload=recv_n(upstream,length+1)
    if frame==1 and payload[:4]==b'\x00\x3c\x00\x50':
     dropped.set();client.shutdown(socket.SHUT_RDWR);break
    client.sendall(header+payload)
  except (OSError,EOFError):pass
  finally:
   done.set();client.close();upstream.close();listener.close()
 thread=threading.Thread(target=proxy,daemon=True);thread.start()
 connection=pika.BlockingConnection(pika.ConnectionParameters('127.0.0.1',port=port,virtual_host='/agentplatform',credentials=pika.PlainCredentials('mq_publisher',secret('mq_publisher')),heartbeat=15,socket_timeout=3))
 try:
  channel=connection.channel();channel.confirm_delivery()
  try:
   channel.basic_publish(exchange='ap.commands.v1',routing_key='execution.ready',body=body,properties=pika.BasicProperties(delivery_mode=2,message_id=event),mandatory=True)
   raise AssertionError('Dropped confirmation incorrectly reported success')
  except pika.exceptions.AMQPConnectionError:pass
  assert dropped.wait(3),'No actual broker Basic.Ack intercepted'
 finally:
  if connection.is_open:connection.close()
  done.set();thread.join(timeout=3)
def s3(read=False):
 return boto3.client('s3',endpoint_url='http://s3:8333',aws_access_key_id='ap-read' if read else 'ap-write',aws_secret_access_key=secret('s3_read' if read else 's3_write'),region_name='us-east-1',config=Config(connect_timeout=3,read_timeout=10,retries={'max_attempts':1},s3={'addressing_style':'path'}))
def provision_bucket():
 admin=boto3.client('s3',endpoint_url='http://s3:8333',aws_access_key_id='ap-admin',aws_secret_access_key=secret('s3_admin'),region_name='us-east-1',config=Config(connect_timeout=3,read_timeout=10,s3={'addressing_style':'path'}))
 try:admin.head_bucket(Bucket='ap-assets')
 except admin.exceptions.ClientError:admin.create_bucket(Bucket='ap-assets')
 try:s3().create_bucket(Bucket='forbidden-'+str(uuid.uuid4()));raise AssertionError('Writer can create arbitrary buckets')
 except s3().exceptions.ClientError as e:assert e.response['Error']['Code']=='AccessDenied'

def setup():
 with db('ap_migration') as c:
  c.execute('CREATE TABLE IF NOT EXISTS pf.r03_probe_dispatch(task_id uuid PRIMARY KEY,generation bigint NOT NULL,fence bigint NOT NULL,owner_id uuid)')
  c.execute('CREATE TABLE IF NOT EXISTS pf.r03_probe_inbox(event_id uuid PRIMARY KEY,task_id uuid NOT NULL)')
  c.execute('CREATE TABLE IF NOT EXISTS pf.r03_probe_reference(object_key text PRIMARY KEY)')
  for name in ['dispatch','inbox','reference']:c.execute('GRANT SELECT,INSERT,UPDATE,DELETE ON pf.r03_probe_'+name+' TO ap_api')

def rollback():
 key='rollback-'+str(uuid.uuid4())
 with db() as c:
  try:
   with c.transaction():
    c.execute('INSERT INTO pf.r03_probe_reference VALUES(%s)',(key,))
    c.execute('SELECT 1/0')
  except psycopg.errors.DivisionByZero:pass
  assert c.execute('SELECT count(*) FROM pf.r03_probe_reference WHERE object_key=%s',(key,)).fetchone()[0]==0

def vector_compatibility():
 with db() as c:
  assert c.execute("SELECT extversion FROM pg_extension WHERE extname='vector'").fetchone()[0]=='0.8.1'
  assert c.execute("SELECT '[1,2,3]'::vector <-> '[1,2,3]'::vector").fetchone()[0]==0.0
  assert c.execute("SELECT '[1,0]'::vector <-> '[0,1]'::vector").fetchone()[0]>1.4

def dispatch():
 task,event=str(uuid.uuid4()),str(uuid.uuid4());owner=str(uuid.uuid4())
 envelope={'schemaVersion':1,'eventId':event,'spaceId':str(uuid.uuid4()),'aggregateId':task,'generation':1,'kind':'execution.ready','occurredAt':'2026-10-13T00:00:00Z','traceparent':None,'payloadRef':task}
 body=json.dumps(envelope).encode();assert len(body)<65536
 with db() as c:c.execute('INSERT INTO pf.r03_probe_dispatch VALUES(%s,1,1,NULL)',(task,))
 publish_with_lost_confirm(body,event)
 pub=mq();ch=pub.channel();ch.confirm_delivery()
 try:
  # Original broker confirmation was lost in real transport; resend same eventId.
  ch.basic_publish(exchange='ap.commands.v1',routing_key='execution.ready',body=body,properties=pika.BasicProperties(delivery_mode=2,message_id=event,content_type='application/json'),mandatory=True)
  try:
   ch.basic_publish(exchange='ap.commands.v1',routing_key='missing.route',body=body,properties=pika.BasicProperties(delivery_mode=2),mandatory=True)
   raise AssertionError('Mandatory unroutable publication accepted')
  except pika.exceptions.UnroutableError:pass
 finally:
  if pub.is_open:pub.close()
 con=mq('mq_execution');consumer=con.channel();consumer.basic_qos(prefetch_count=1)
 claimed=0;seen=0;rolled=False
 try:
  deadline=time.monotonic()+20
  while seen<2 and time.monotonic()<deadline:
   method,props,payload=consumer.basic_get('ap.execution.v1',auto_ack=False)
   if method is None:time.sleep(.1);continue
   item=json.loads(payload)
   assert item['eventId']==event and item['generation']==1
   if not rolled:
    # Failed durable handoff must not ACK. Requeue original delivery.
    with db() as c:
     try:
      with c.transaction():
       c.execute('INSERT INTO pf.r03_probe_inbox VALUES(%s,%s)',(event,task))
       raise RuntimeError('injected-before-commit')
     except RuntimeError:pass
    consumer.basic_nack(method.delivery_tag,requeue=True);rolled=True;continue
   with db() as c:
    with c.transaction():
     existing=c.execute('SELECT task_id FROM pf.r03_probe_inbox WHERE event_id=%s',(event,)).fetchone()
     if not existing:
      row=c.execute('UPDATE pf.r03_probe_dispatch SET owner_id=%s,fence=fence+1 WHERE task_id=%s AND generation=1 AND fence=1 AND owner_id IS NULL RETURNING fence',(owner,task)).fetchone()
      assert row==(2,)
      c.execute('INSERT INTO pf.r03_probe_inbox VALUES(%s,%s)',(event,task));claimed+=1
   consumer.basic_ack(method.delivery_tag);seen+=1
  assert seen==2 and claimed==1 and rolled
  with db() as c:
   assert c.execute('UPDATE pf.r03_probe_dispatch SET owner_id=%s WHERE task_id=%s AND fence=1 RETURNING task_id',(str(uuid.uuid4()),task)).fetchone() is None
   assert c.execute('UPDATE pf.r03_probe_dispatch SET owner_id=%s WHERE task_id=%s AND generation=0 RETURNING task_id',(str(uuid.uuid4()),task)).fetchone() is None
 finally:
  con.close()
  with db() as c:
   c.execute('DELETE FROM pf.r03_probe_inbox WHERE event_id=%s',(event,));c.execute('DELETE FROM pf.r03_probe_dispatch WHERE task_id=%s',(task,))

manifest=pathlib.Path('/reports/object-manifest.json')
def object_write():
 client=s3()
 try:client.head_bucket(Bucket='ap-assets')
 except client.exceptions.ClientError:client.create_bucket(Bucket='ap-assets')
 key='spaces/'+str(uuid.uuid4())+'/attachment/'+str(uuid.uuid4())+'/'+str(uuid.uuid4())+'/'+str(uuid.uuid4())
 data=('R0.3 中文😀\n'.encode()*4096);sha=hashlib.sha256(data).hexdigest()
 client.put_object(Bucket='ap-assets',Key=key,Body=data,Metadata={'sha256':sha})
 assert client.head_object(Bucket='ap-assets',Key=key)['ContentLength']==len(data)
 assert s3(True).get_object(Bucket='ap-assets',Key=key,Range='bytes=3-127')['Body'].read()==data[3:128]
 # S3 success followed by DB failure yields no visible DB reference.
 with db() as c:
  try:
   with c.transaction():
    c.execute('INSERT INTO pf.r03_probe_reference VALUES(%s)',(key,));raise RuntimeError('injected DB rollback')
  except RuntimeError:pass
  assert c.execute('SELECT count(*) FROM pf.r03_probe_reference WHERE object_key=%s',(key,)).fetchone()[0]==0
 multi=key+'-multipart';parts=[b'A'*(5*1024*1024),b'B'*(1024*1024)]
 upload=client.create_multipart_upload(Bucket='ap-assets',Key=multi)['UploadId']
 try:
  etags=[]
  for i,part in enumerate(parts,1):etags.append({'PartNumber':i,'ETag':client.upload_part(Bucket='ap-assets',Key=multi,UploadId=upload,PartNumber=i,Body=part)['ETag']})
  result=client.complete_multipart_upload(Bucket='ap-assets',Key=multi,UploadId=upload,MultipartUpload={'Parts':etags})
 except Exception:
  client.abort_multipart_upload(Bucket='ap-assets',Key=multi,UploadId=upload);raise
 combined=b''.join(parts);mh=hashlib.sha256(combined).hexdigest()
 assert result['ETag'].strip('"')!=mh
 assert hashlib.sha256(client.get_object(Bucket='ap-assets',Key=multi)['Body'].read()).hexdigest()==mh
 manifest.write_text(json.dumps({'key':key,'sha256':sha,'size':len(data),'multipart':multi,'multipartSha256':mh}))

def object_after_restart():
 m=json.loads(manifest.read_text());client=s3(True)
 assert client.head_object(Bucket='ap-assets',Key=m['key'])['ContentLength']==m['size']
 assert hashlib.sha256(client.get_object(Bucket='ap-assets',Key=m['key'])['Body'].read()).hexdigest()==m['sha256']
 assert len(client.get_object(Bucket='ap-assets',Key=m['key'],Range='bytes=0-15')['Body'].read())==16
 assert hashlib.sha256(client.get_object(Bucket='ap-assets',Key=m['multipart'])['Body'].read()).hexdigest()==m['multipartSha256']

def redis():
 with socket.create_connection(('redis',6379),3) as c:
  password=secret('redis').encode()
  c.sendall(b'*2\r\n$4\r\nAUTH\r\n$'+str(len(password)).encode()+b'\r\n'+password+b'\r\n');assert c.recv(1024).startswith(b'+OK')
  c.sendall(b'*1\r\n$4\r\nPING\r\n');assert c.recv(1024).startswith(b'+PONG')

def sts_disabled():
 # Disabled routes may return empty405, which the AWS XML parser cannot parse.
 # Assert the actual wire refusal and functioning S3 on both sides; never accept
 # transport errors, arbitrary parser errors, successful identities or grants.
 client=s3();client.head_bucket(Bucket='ap-assets')
 credentials=Credentials('ap-write',secret('s3_write'))
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 for action in ['GetCallerIdentity','AssumeRole','GetFederationToken']:
  parameters={'Action':action,'Version':'2011-06-15'}
  if action=='AssumeRole':parameters.update(RoleArn='arn:aws:iam::000000000000:role/r04-disabled',RoleSessionName='r04-denial')
  if action=='GetFederationToken':parameters['Name']='r04-denial'
  encoded=urllib.parse.urlencode(parameters)
  for placement in ['body','query']:
   url='http://s3:8333/'+('?' + encoded if placement=='query' else '')
   body=encoded.encode() if placement=='body' else b''
   request=AWSRequest(method='POST',url=url,data=body,headers={'Content-Type':'application/x-www-form-urlencoded'})
   SigV4Auth(credentials,'sts','us-east-1').add_auth(request)
   wire=urllib.request.Request(url,data=body,headers=dict(request.headers),method='POST')
   try:response=opener.open(wire,timeout=3)
   except urllib.error.HTTPError as error:response=error
   with response:
    payload=response.read(65537)
    assert len(payload)<=65536
    validate_denial(response.status,payload)
    print('STS-'+action+'-'+placement+':HTTP'+str(response.status),flush=True)
 client.head_bucket(Bucket='ap-assets')

def cleanup():
 client=s3();m=json.loads(manifest.read_text())
 for key in [m['key'],m['multipart']]:client.delete_object(Bucket='ap-assets',Key=key)
 with db('ap_migration') as c:
  for name in ['dispatch','inbox','reference']:c.execute('DROP TABLE pf.r03_probe_'+name)

try:
 mode=os.environ.get('PROBE_MODE','initial')
 if mode=='initial':
  setup();case('DB-vector-version-distance',vector_compatibility);case('DB-rollback',rollback);case('MQ-confirm-duplicate-rollback-fence',dispatch);case('S3-hash-range-multipart-orphan',object_write);case('Redis-auth-ping',redis);case('STS-disabled-denial',sts_disabled)
 elif mode=='bootstrap':case('S3-bucket-admin-only',provision_bucket)
 elif mode=='restart':case('S3-restart-persistence',object_after_restart);case('Redis-restart-auth',redis)
 elif mode=='cleanup':case('Probe-fixture-cleanup',cleanup)
 else:raise ValueError('unknown explicit probe mode')
finally:
 pathlib.Path('/reports').mkdir(exist_ok=True)
 pathlib.Path('/reports/storage-'+os.environ.get('PROBE_MODE','initial')+'.json').write_text(json.dumps(reports,indent=2))
