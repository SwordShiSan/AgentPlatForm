"""Initialize only the independent R6.2 fixture bucket; credentials stay in mounted files."""
from pathlib import Path
import time
import boto3
from botocore.config import Config
from botocore.exceptions import ClientError, EndpointConnectionError

access = Path('/fixture/s3-access').read_text().strip()
secret = Path('/fixture/s3-secret').read_text().strip()
client = boto3.client('s3', endpoint_url='http://seaweedfs:8333', region_name='us-east-1',
                      aws_access_key_id=access, aws_secret_access_key=secret,
                      config=Config(connect_timeout=2, read_timeout=5, retries={'max_attempts': 1}, s3={'addressing_style': 'path'}))
deadline = time.monotonic() + 60
while True:
    try:
        try: client.head_bucket(Bucket='r62-assets')
        except ClientError as error:
            if error.response.get('ResponseMetadata', {}).get('HTTPStatusCode') != 404: raise
            client.create_bucket(Bucket='r62-assets')
        client.head_bucket(Bucket='r62-assets')
        print('R62_BUCKET_READY'); break
    except (EndpointConnectionError, ClientError):
        if time.monotonic() >= deadline: raise
        time.sleep(1)
