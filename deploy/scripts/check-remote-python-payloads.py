"""Compile Python orchestration and literal remote payloads before any mutation.

This static preflight cannot validate dynamically assembled payloads; callers must
compile those after assembly and before starting the operation, not just before SSH.
It emits locations and syntax categories, never source text or secret values.
"""
import argparse
import ast
import json
from pathlib import Path


def inspect(path):
    return inspect_source(Path(path).read_text(encoding='utf-8-sig'), str(path))


def inspect_source(source, path='<memory>'):
    errors = []
    literal_count = 0
    dynamic_count = 0
    try:
        tree = ast.parse(source, filename=str(path))
    except SyntaxError as error:
        return {'file': str(path), 'literalPayloads': 0, 'dynamicPayloadsNotChecked': 0,
                'errors': [{'line': error.lineno, 'category': error.msg}]}
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or not node.args:
            continue
        name = node.func.id if isinstance(node.func, ast.Name) else ''
        if name not in {'remote', 'remote_run', 'remote_call', 'run_remote'}:
            continue
        argument = node.args[0]
        if isinstance(argument, ast.Constant) and isinstance(argument.value, str):
            literal_count += 1
            try:
                compile(argument.value, str(path) + ':payload:' + str(node.lineno), 'exec')
            except SyntaxError as error:
                errors.append({'callLine': node.lineno, 'payloadLine': error.lineno,
                               'category': error.msg})
        else:
            dynamic_count += 1
    return {'file': str(path), 'literalPayloads': literal_count,
            'dynamicPayloadsNotChecked': dynamic_count, 'errors': errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('files', nargs='+', type=Path)
    args = parser.parse_args()
    results = [inspect(path) for path in args.files]
    print(json.dumps({'status': 'FAIL' if any(row['errors'] for row in results)
                      else 'PASS_STATIC_LITERAL_PAYLOADS', 'files': results}))
    return int(any(row['errors'] for row in results))


if __name__ == '__main__':
    raise SystemExit(main())
