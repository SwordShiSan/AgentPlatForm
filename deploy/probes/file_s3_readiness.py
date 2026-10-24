"""Read-only, bounded readiness of the isolated R3.3 private bucket via official boto3.

No shell, topology walk, object mutation, credential output or production endpoint.
Run only in the independent fixture's internal network with two explicit key files.
"""
import argparse
from contextlib import closing
import json
import logging
from pathlib import Path
import time
import boto3
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--access-key-file', required=True, type=Path)
    parser.add_argument('--secret-key-file', required=True, type=Path)
    args = parser.parse_args()
    logging.getLogger('botocore').setLevel(logging.CRITICAL)
    start = time.monotonic()
    attempts = 0
    last_class = 'NOT_STARTED'
    with closing(boto3.client('s3', endpoint_url='http://s3:8333', region_name='us-east-1',
            aws_access_key_id=args.access_key_file.read_text().strip(),
            aws_secret_access_key=args.secret_key_file.read_text().strip(),
            config=Config(signature_version='s3v4', connect_timeout=2, read_timeout=3,
                          retries={'total_max_attempts': 1}, s3={'addressing_style': 'path'},
                          request_checksum_calculation='when_required', response_checksum_validation='when_required'))) as client:
        while time.monotonic() - start < 90:
            attempts += 1
            try:
                client.head_bucket(Bucket='r33-assets')
                print(json.dumps({'status': 'READY', 'attempts': attempts,
                                  'elapsedSeconds': round(time.monotonic() - start, 3)}))
                return 0
            except ClientError as failure:
                status = failure.response.get('ResponseMetadata', {}).get('HTTPStatusCode', 0)
                last_class = 'HTTP_' + str(status)
                if status in (401, 403):
                    break  # A credential/permission failure is not transient startup.
            except BotoCoreError:
                last_class = 'TRANSPORT_UNAVAILABLE'
            time.sleep(2)
    print(json.dumps({'status': 'NOT_READY', 'attempts': attempts, 'category': last_class,
                      'elapsedSeconds': round(time.monotonic() - start, 3)}))
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
