"""Publish the exact UTF-8 extraction fixture into the isolated R6.3 bucket."""
import hashlib
import os
from pathlib import Path

import boto3
from botocore.config import Config

source = Path('/fixture/extracted.txt').read_bytes()
key = os.environ['R63_OBJECT_KEY']
access = Path('/fixture/s3-access').read_text().strip()
secret = Path('/fixture/s3-secret').read_text().strip()
client = boto3.client(
    's3', endpoint_url='http://seaweedfs:8333', region_name='us-east-1',
    aws_access_key_id=access, aws_secret_access_key=secret,
    config=Config(connect_timeout=2, read_timeout=5, retries={'max_attempts': 1}, s3={'addressing_style': 'path'}))
digest = hashlib.sha256(source).hexdigest()
client.put_object(Bucket='r63-assets', Key=key, Body=source, ContentType='text/plain; charset=utf-8',
                  Metadata={'sha256': digest})
response = client.get_object(Bucket='r63-assets', Key=key)
try:
    actual = response['Body'].read()
finally:
    response['Body'].close()
if len(actual) != len(source) or hashlib.sha256(actual).hexdigest() != digest:
    raise RuntimeError('R63 extracted-text fixture failed read-back integrity check')
print('R63_TEXT_EXTRACTION_FIXTURE_OBJECT_VERIFIED bytes=' + str(len(source)) + ' sha256=' + digest)
