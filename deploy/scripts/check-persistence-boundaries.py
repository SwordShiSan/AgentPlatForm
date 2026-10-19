"""Reject direct business JDBC across every production package, including inferred var calls."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
ALLOW = {
    'platform-infrastructure/src/main/java/com/agentplatform/infrastructure/storage/PostgresStateStorage.java': 'R0 standalone driver/state diagnostic; production chat uses fenced MyBatis state',
    'platform-infrastructure/src/main/java/com/agentplatform/infrastructure/health/DependencyReadinessProbe.java': 'R0 baseline schema/transport diagnostic only',
    'platform-infrastructure/src/main/java/com/agentplatform/infrastructure/dao/typehandler/UuidTypeHandler.java': 'MyBatis UUID driver conversion extension',
}
FORBIDDEN = re.compile(r'\bjava\.sql\.|\bDriverManager\b|\.\s*(?:prepareStatement|createStatement|executeQuery|executeUpdate|setAutoCommit|commit|rollback)\s*\(')


def main():
    checked = 0
    failures = []
    for path in sorted((ROOT / 'backend').glob('*/src/main/java/**/*.java')):
        name = path.relative_to(ROOT / 'backend').as_posix()
        checked += 1
        if name in ALLOW:
            continue
        text = re.sub(r'//[^\n]*|/\*[\s\S]*?\*/', '', path.read_text(encoding='utf-8'))
        if FORBIDDEN.search(text):
            failures.append(name)
    assert all((ROOT / 'backend' / path).is_file() for path in ALLOW), 'Stale JDBC exception; update the explicit reviewed list'
    assert not failures, 'Business JDBC boundary violation: ' + ', '.join(failures)
    print(f'Persistence boundary PASS: {checked} production Java files; {len(ALLOW)} exact diagnostic/TypeHandler exceptions')


if __name__ == '__main__':
    main()
