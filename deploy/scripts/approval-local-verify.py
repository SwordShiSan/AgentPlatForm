"""Run the bounded R4.3 real PostgreSQL/Plan regression against its exact isolated fixture."""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, time

ROOT = Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__)
selection=parser.add_mutually_exclusive_group()
selection.add_argument('--broker-only',action='store_true')
selection.add_argument('--full',action='store_true',help='Full reactor verify plus exact R4.3 PG/MQ cases')
selection.add_argument('--approval-only',action='store_true',help='First run the R4.3 vertical approval gate')
selection.add_argument('--smoke',action='store_true',help='One real SDK/PG original-operation approval before expanding the matrix')
args=parser.parse_args()
DATA = ROOT / '.cache/r43-local'
out = DATA / ('postgres-tests-' + str(time.time_ns()))
out.mkdir(parents=True, exist_ok=False)
java = ROOT / '.tools/jdk-21.0.12.1/jdk-21.0.12.1+1'
assert (java / 'bin/java.exe').is_file(), 'Pinned JDK missing; do not install another runtime'
url = 'jdbc:postgresql://127.0.0.1:15456/r43_test?connectTimeout=2&socketTimeout=5'
env = dict(os.environ, JAVA_HOME=str(java), PATH=str(java / 'bin') + os.pathsep + os.environ['PATH'],
           R43_ACCEPTANCE='1', R43_TEST_JDBC_URL=url, R42_TEST_JDBC_URL=url, R41_TEST_JDBC_URL=url,
           R43_TEST_PASSWORD_FILE=str(DATA / 'fixture/postgres-password.txt'), R42_TEST_PASSWORD_FILE=str(DATA / 'fixture/postgres-password.txt'),
           R41_TEST_PASSWORD_FILE=str(DATA / 'fixture/postgres-password.txt'),
           R41_MQ_PASSWORD_FILE=str(DATA / 'fixture/mq-operator-password'),
           R43_MQ_PASSWORD_FILE=str(DATA / 'fixture/mq-operator-password'))
tests='PlanQuotaPostgresTest#acceptedAnswerUsesRealRabbitConfirmAckAndDuplicateHandoffWithoutSecondOwner' if args.broker_only else 'ApprovalPostgresTest,PermissionPostgresTest,PlanQuotaPostgresTest'
if args.approval_only:tests='ApprovalPostgresTest'
if args.smoke:tests='ApprovalPostgresTest#allowRestoresOriginalOperationAndConsumesExactlyOnce'
command = [str(ROOT / 'backend/mvnw.cmd'), '-B', '-ntp', '-f', str(ROOT / 'backend/pom.xml'),
           '-pl', 'platform-app', '-am']
command += ['verify'] if args.full else ['-Dtest='+tests, '-Dsurefire.failIfNoSpecifiedTests=false', 'test']
log = out / 'maven-postgres.log'
with log.open('xb') as stream:
    result = subprocess.run(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
record = {'status': 'PASS' if result.returncode == 0 else 'FAIL', 'exitCode': result.returncode,
          'sourceBase': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
          'workingTreeClean': not bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
          'supplierCalls': 0, 'logSha256': hashlib.sha256(log.read_bytes()).hexdigest(), 'command': command}
(out / 'result.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps({'status': record['status'], 'exitCode': result.returncode, 'evidence': str(out.relative_to(ROOT))}), flush=True)
raise SystemExit(result.returncode)
