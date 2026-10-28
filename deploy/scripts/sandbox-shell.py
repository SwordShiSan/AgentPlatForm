"""Fixed guest launcher. The runner supplies only a relative cwd and a frozen command.

This file runs inside the sandbox, never on the management host. The container
and all descendants are stopped by the runner after each invocation.
"""
import os
import sys
import unicodedata


def open_directory(root, relative):
    if not isinstance(relative, str) or unicodedata.normalize('NFC', relative) != relative:
        raise ValueError('INVALID_SHELL_DIRECTORY')
    parts = [] if relative == '' else relative.split('/')
    if relative.startswith('/') or '\\' in relative or ':' in relative or any(
            part in ('', '.', '..') or any(ord(c) < 32 or ord(c) == 127 for c in part) for part in parts):
        raise ValueError('INVALID_SHELL_DIRECTORY')
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    current = os.open(root, flags)
    try:
        for part in parts:
            child = os.open(part, flags, dir_fd=current)
            os.close(current)
            current = child
        return current
    except BaseException:
        os.close(current)
        raise


def main():
    if len(sys.argv) != 3:
        raise ValueError('INVALID_SHELL_REQUEST')
    cwd, command = sys.argv[1:]
    if not command.strip() or '\0' in command or len(command.encode('utf-8')) > 32768:
        raise ValueError('INVALID_SHELL_COMMAND')
    directory = open_directory('/workspace', cwd)
    try:
        os.fchdir(directory)
    finally:
        os.close(directory)
    null = os.open('/dev/null', os.O_RDONLY)
    try:
        os.dup2(null, 0)
    finally:
        os.close(null)
    environment = {'PATH': '/usr/local/bin:/usr/bin:/bin', 'HOME': '/tmp', 'TMPDIR': '/tmp',
                   'LANG': 'C.UTF-8', 'PYTHONDONTWRITEBYTECODE': '1', 'PYTHONUNBUFFERED': '1'}
    os.execve('/bin/sh', ['/bin/sh', '-c', command], environment)


if __name__ == '__main__':
    main()
