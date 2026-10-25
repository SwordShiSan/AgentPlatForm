#!/usr/bin/env python3
"""Inspect an already-resolved private Compose JSON without printing its environment."""
import argparse
import json
import re
from pathlib import Path


def quantity(value):
    if isinstance(value, int):
        return value
    match = re.fullmatch(r"([0-9]+)([kmgt]?)(?:i?b)?", str(value).strip(), re.I)
    if not match:
        raise ValueError("UNSUPPORTED_MEMORY_QUANTITY")
    return int(match[1]) * 1024 ** ("kmgt".index(match[2].lower()) + 1 if match[2] else 0)


def inspect(config, selected, reserved_mib=0, maximum_mib=2887):
    if reserved_mib < 0 or maximum_mib <= 0:
        raise ValueError("POSITIVE_BUDGET_AND_NONNEGATIVE_RESERVATION_REQUIRED")
    findings = []
    total = 0
    for name in selected:
        service = config["services"][name]
        limit = quantity(service.get("mem_limit", 0))
        total += limit
        if limit <= 0:
            findings.append((name, "FINITE_CONTAINER_LIMIT_REQUIRED"))
        if quantity(service.get("memswap_limit", 0)) != limit:
            findings.append((name, "SWAP_MUST_EQUAL_MEMORY_LIMIT"))
        environment = service.get("environment", {})
        if not isinstance(environment, dict):
            raise ValueError("RESOLVED_MAPPING_ENVIRONMENT_REQUIRED")
        go = environment.get("GOMEMLIMIT")
        if go and (go == "off" or quantity(go) >= limit):
            findings.append((name, "GO_SOFT_LIMIT_MUST_BE_BELOW_CONTAINER_LIMIT"))
        entrypoint, command = service.get("entrypoint") or [], service.get("command") or []
        if not isinstance(entrypoint, list) or not isinstance(command, list):
            raise ValueError("RESOLVED_ARGUMENT_ARRAY_REQUIRED")
        arguments = [*entrypoint, *command]
        if not all(isinstance(argument, str) for argument in arguments):
            raise ValueError("RESOLVED_ARGUMENT_ARRAY_REQUIRED")
        heaps = [quantity(argument[4:]) for argument in arguments if argument.startswith("-Xmx")]
        if any(heap >= limit for heap in heaps):
            findings.append((name, "JAVA_HEAP_MUST_LEAVE_NATIVE_HEADROOM"))
    declared = total + reserved_mib * 1024 ** 2
    if declared > maximum_mib * 1024 ** 2:
        findings.append(("TOTAL", "DECLARED_COMBINATION_EXCEEDS_BUDGET"))
    return {"status": "PASS" if not findings else "FAIL", "declaredServiceBytes": total,
            "reservedMiB": reserved_mib, "declaredTotalBytes": declared, "maximumMiB": maximum_mib,
            "findings": [{"service": name, "code": code} for name, code in findings],
            "scope": "Static ceilings only; does not prove native overhead, functional capacity or host admission"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("compose", type=Path)
    parser.add_argument("--services", nargs="+", required=True)
    parser.add_argument("--reserved-mib", type=int, default=0,
                        help="Additional simultaneous sandbox/runtime/helper budget, not selected above")
    parser.add_argument("--maximum-mib", type=int, default=2887)
    args = parser.parse_args()
    result = inspect(json.loads(args.compose.read_text(encoding="utf-8-sig")), args.services,
                     args.reserved_mib, args.maximum_mib)
    print(json.dumps(result))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
