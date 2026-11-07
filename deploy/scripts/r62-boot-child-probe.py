"""Check the packaged SDK child via an isolated loopback proxy; never calls a remote service."""
from pathlib import Path
import hashlib
import json
import socket
import subprocess
import threading
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / 'backend/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar'
JAVA = ROOT / '.tools/jdk-21.0.12.1/jdk-21.0.12.1+1/bin/java.exe'
DATA = ROOT / '.cache/r62-local/boot-child' / ('run-' + str(time.time_ns()))

def main():
    DATA.mkdir(parents=True)
    assert JAR.is_file() and JAVA.is_file()
    with zipfile.ZipFile(JAR) as archive:
        assert 'BOOT-INF/classes/' in archive.namelist()
        assert 'org/springframework/boot/loader/launch/PropertiesLauncher.class' in archive.namelist()
    listener = socket.socket(); listener.bind(('127.0.0.1', 0)); listener.listen(1); listener.settimeout(20)
    observed = []
    def proxy():
        try:
            with listener.accept()[0] as connection:
                connection.settimeout(5); observed.append(connection.recv(4096).splitlines()[0].decode('ascii'))
                connection.sendall(b'HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n')
        except OSError: pass
    thread = threading.Thread(target=proxy, daemon=True); thread.start()
    body = {'endpoint': 'https://fixture.invalid/mcp', 'authKind': 'NONE', 'header': None, 'secret': None,
            'proxyHost': '127.0.0.1', 'proxyPort': listener.getsockname()[1]}
    command = [str(JAVA), '-Xms16m', '-Xmx128m', '-XX:MaxMetaspaceSize=96m', '-XX:MaxDirectMemorySize=16m',
               '-XX:ActiveProcessorCount=1', '-XX:+ExitOnOutOfMemoryError',
               '-Dloader.main=com.agentplatform.infrastructure.capability.McpDiscoveryChild', '-cp', str(JAR),
               'org.springframework.boot.loader.launch.PropertiesLauncher']
    try:
        result = subprocess.run(command, input=json.dumps(body), text=True, capture_output=True, timeout=30)
        thread.join(timeout=5)
        payload = json.loads(result.stdout)
        assert result.returncode == 0 and payload['status'] == 503 and observed == ['CONNECT fixture.invalid:443 HTTP/1.1']
        receipt = {'status': 'PASS', 'scope': 'packaged SDK child through denied loopback proxy',
                   'jarSha256': hashlib.sha256(JAR.read_bytes()).hexdigest(), 'processExit': result.returncode}
        (DATA / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(receipt))
    finally:
        listener.close(); thread.join(timeout=1)

if __name__ == '__main__': main()
