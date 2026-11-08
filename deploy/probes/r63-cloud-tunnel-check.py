"""Read-only check for the isolated R6.3 SSH reverse forwards on the cloud host."""
import json
import socket
import urllib.error
import urllib.request


def tcp(host: str, port: int) -> None:
    with socket.create_connection((host, port), timeout=4):
        pass


tcp("127.0.0.1", 25463)  # R6.3-only PostgreSQL test database forward.
try:
    with urllib.request.urlopen("http://127.0.0.1:28333/", timeout=4) as response:
        s3_status = response.status
except urllib.error.HTTPError as error:
    # An unsigned S3 root request is expected to be denied; reaching SeaweedFS is the check.
    s3_status = error.code

if s3_status not in {200, 400, 403}:
    raise SystemExit("Unexpected S3 endpoint response")

print(json.dumps({"status": "PASS", "postgresTcp": True, "s3HttpStatus": s3_status}))
