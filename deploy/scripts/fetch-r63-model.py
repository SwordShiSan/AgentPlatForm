#!/usr/bin/env python3
"""Fetch only the immutable, checksum-pinned local R6.3 embedding artifacts."""
from __future__ import annotations
import argparse
import hashlib
import os
from pathlib import Path
import tempfile
import urllib.request

REVISION = "761b726dd34fb83930e26aab4e9ac3899aa1fa78"
FILES = {
    "onnx/model_quantized.onnx": "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193",
    "tokenizer.json": "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39",
}

def digest(path: Path) -> str:
    sha=hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1024*1024), b""):
            sha.update(part)
    return sha.hexdigest()

def fetch(root: Path) -> None:
    root.mkdir(parents=True, exist_ok=True)
    for rel, expected in FILES.items():
        target=root/rel
        if target.exists():
            if digest(target)==expected:
                print(f"verified {rel}")
                continue
            raise SystemExit(f"existing artifact checksum mismatch: {rel}")
        target.parent.mkdir(parents=True,exist_ok=True)
        url=f"https://huggingface.co/Xenova/multilingual-e5-small/resolve/{REVISION}/{rel}?download=true"
        request=urllib.request.Request(url,headers={"User-Agent":"AgentPlatForm-R63-model-fetch/1"})
        fd,tmp=tempfile.mkstemp(prefix=".r63-",dir=target.parent);os.close(fd)
        temporary=Path(tmp)
        try:
            sha=hashlib.sha256()
            with urllib.request.urlopen(request,timeout=90) as response,temporary.open("wb") as output:
                while True:
                    block=response.read(1024*1024)
                    if not block:break
                    sha.update(block);output.write(block)
                output.flush();os.fsync(output.fileno())
            actual=sha.hexdigest()
            if actual!=expected:raise SystemExit(f"download checksum mismatch for {rel}: {actual}")
            os.replace(temporary,target)
            print(f"downloaded and verified {rel} sha256={actual}")
        finally:
            temporary.unlink(missing_ok=True)

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("directory",type=Path,help="ignored local model cache directory")
    fetch(parser.parse_args().directory)
