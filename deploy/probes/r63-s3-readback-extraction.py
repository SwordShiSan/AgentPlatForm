"""Hash and marker verification for an isolated persisted extraction object."""
import hashlib
import json
import os
from pathlib import Path
import boto3
from botocore.config import Config

client = boto3.client('s3', endpoint_url='http://seaweedfs:8333', region_name='us-east-1',
    aws_access_key_id=Path('/fixture/s3-access').read_text().strip(),
    aws_secret_access_key=Path('/fixture/s3-secret').read_text().strip(),
    config=Config(connect_timeout=2, read_timeout=8, retries={'max_attempts': 1}, s3={'addressing_style': 'path'}))
response = client.get_object(Bucket='r63-assets', Key=os.environ['R63_OBJECT_KEY'])
try:
    content = response['Body'].read()
finally:
    response['Body'].close()
digest = hashlib.sha256(content).hexdigest()
expected = int(os.environ['R63_EXPECTED_BYTES'])
required = os.environ['R63_REQUIRED_MARKER'].encode('utf-8')
if len(content) != expected or digest != os.environ['R63_EXPECTED_SHA256'] or required not in content:
    raise SystemExit('Persisted extraction object identity or marker mismatch')
print(json.dumps({'status': 'PASS', 'bytes': len(content), 'sha256': digest, 'markerPresent': True}))
