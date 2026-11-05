"""Read restored immutable objects with the official SDK; emit no file content."""
from pathlib import Path
from contextlib import closing
import hashlib,json,logging
import boto3
from botocore.config import Config
logging.getLogger('botocore').setLevel(logging.CRITICAL)
objects=json.loads(Path('/run/recovery/objects.json').read_text())
with closing(boto3.client('s3',endpoint_url='http://s3:8333',region_name='us-east-1',aws_access_key_id=Path('/run/recovery/access').read_text().strip(),aws_secret_access_key=Path('/run/recovery/secret').read_text().strip(),config=Config(signature_version='s3v4',connect_timeout=2,read_timeout=10,retries={'total_max_attempts':1},s3={'addressing_style':'path'},request_checksum_calculation='when_required',response_checksum_validation='when_required'))) as client:
 for item in objects:
  h=hashlib.sha256();count=0
  response=client.get_object(Bucket='ap-assets',Key=item['key'])
  with closing(response['Body']) as stream:
   while chunk:=stream.read(65536):h.update(chunk);count+=len(chunk)
  assert count==item['bytes'] and h.hexdigest()==item['sha256'],'Restored immutable object hash mismatch'
print(json.dumps({'status':'PASS','objects':len(objects),'contentEmitted':False,'supplierCalls':0}))
