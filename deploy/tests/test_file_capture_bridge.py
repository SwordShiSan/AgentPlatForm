import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import uuid

spec = importlib.util.spec_from_file_location("capture", Path(__file__).parents[1] / "scripts/file-capture-bridge.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class CaptureTests(unittest.TestCase):
    def header(self, path="result.txt", maximum=104857600):
        return dict(protocol=bridge.PROTOCOL, operationId=str(uuid.uuid4()), path=path, maxBytes=maximum)

    def ready(self, header, content):
        return io.BytesIO(json.dumps(dict(protocol=bridge.PROTOCOL, operationId=header['operationId'], ready=True,
                                         sha256=hashlib.sha256(content).hexdigest())).encode() + b'\n')

    def test_empty_unicode_and_binary_capture_have_exact_descriptor_body_and_trailer(self):
        with tempfile.TemporaryDirectory() as root:
            for content in [b'', '666😀中文'.encode(), bytes(range(256)) * 300]:
                Path(root, 'result.txt').write_bytes(content)
                header = self.header(); output = io.BytesIO()
                descriptor = bridge.capture(header, self.ready(header, content), output, root)
                wire_header, wire_body = output.getvalue().split(b'\n', 1)
                self.assertEqual(json.loads(wire_header), descriptor)
                self.assertEqual(wire_body, content + hashlib.sha256(content).digest())

    def test_links_devices_directories_paths_and_declared_limit_reject_before_descriptor(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, 'file').write_bytes(b'abc'); Path(root, 'dir').mkdir()
            os.symlink('file', Path(root, 'symlink')); os.link(Path(root, 'file'), Path(root, 'hardlink'))
            os.mkfifo(Path(root, 'pipe'))
            for path in ['../file', '/file', 'dir', 'symlink', 'hardlink', 'pipe', 'missing', 'a/../file']:
                with self.subTest(path=path):
                    output = io.BytesIO()
                    with self.assertRaises(bridge.Rejected):
                        bridge.capture(self.header(path), io.BytesIO(), output, root)
                    self.assertEqual(output.getvalue(), b'')
            Path(root, 'large').write_bytes(b'1234')
            with self.assertRaisesRegex(bridge.Rejected, 'FILE_TOO_LARGE'):
                bridge.capture(self.header('large', 3), io.BytesIO(), io.BytesIO(), root)

    def test_changed_file_between_descriptor_and_ready_never_streams_a_valid_result(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root, 'result.txt'); path.write_bytes(b'first'); header=self.header()
            class Changed(io.BytesIO):
                def readline(self, limit=-1):
                    path.write_bytes(b'other')
                    return super().readline(limit)
            output=io.BytesIO()
            with self.assertRaisesRegex(bridge.Rejected, 'FILE_CHANGED_DURING_READ'):
                bridge.capture(header, Changed(self.ready(header,b'first').getvalue()), output, root)
            # A same-size rewrite may share the filesystem timestamp tick. The
            # second digest must still reject it before emitting a valid trailer.
            self.assertLess(len(output.getvalue().split(b'\n',1)[1]),len(b'first')+32)

    def test_wrong_ready_cannot_switch_the_described_identity(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root, 'result.txt'); path.write_bytes(b'original'); header=self.header()
            output=io.BytesIO()
            with self.assertRaisesRegex(bridge.Rejected,'FILE_CAPTURE_READY_REJECTED'):
                bridge.capture(header,self.ready(header,b'different'),output,root)
            self.assertEqual(output.getvalue().split(b'\n',1)[1],b'')

    def test_100mib_capture_uses_only_bounded_chunks(self):
        with tempfile.TemporaryDirectory() as root:
            chunk=b'0123456789abcdef'*4096;digest=hashlib.sha256();size=100*1024*1024
            with Path(root,'result.txt').open('wb') as target:
                for _ in range(size//len(chunk)):
                    target.write(chunk);digest.update(chunk)
            class Counted:
                def __init__(self):self.count=0;self.largest=0
                def write(self,content):self.count+=len(content);self.largest=max(self.largest,len(content));return len(content)
                def flush(self):pass
            output=Counted();header=self.header()
            ready=io.BytesIO(json.dumps(dict(protocol=bridge.PROTOCOL,operationId=header['operationId'],ready=True,sha256=digest.hexdigest())).encode()+b'\n')
            descriptor=bridge.capture(header,ready,output,root)
            self.assertEqual(descriptor['bytes'],size);self.assertEqual(descriptor['sha256'],digest.hexdigest())
            self.assertLessEqual(output.largest,65536)
            self.assertEqual(output.count,size+32+len(json.dumps(descriptor,separators=(',',':')).encode())+1)

    def test_header_is_exact_and_mount_is_required_in_production(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root,'result.txt').write_bytes(b'a')
            for change in [dict(endpoint='http://other'),dict(maxBytes=True),dict(protocol='file-stream-v2')]:
                header=self.header();header.update(change)
                with self.assertRaises(bridge.Rejected):
                    bridge.capture(header,io.BytesIO(),io.BytesIO(),root)
            with self.assertRaisesRegex(bridge.Rejected,'FILE_MOUNT_REJECTED'):
                bridge.capture(self.header(),io.BytesIO(),io.BytesIO(),root,True)


if __name__ == '__main__':
    unittest.main()
