#!/usr/bin/env python3
"""Fixed idle process for the short-lived trusted file bridge container."""
import signal
import sys


if sys.argv[1:] != ["idle"]:
    raise SystemExit(64)


def stop(_signum, _frame):
    raise SystemExit(0)


signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
while True:
    signal.pause()
