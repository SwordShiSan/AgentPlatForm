"""Read-only preflight for the fixed R3.2 Java fixture and its Python controller.

This validates action names only. It never executes the driver, starts Docker,
or marks the replacement cross-container path gate as verified.
"""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import re


def check(java_source: Path, driver: Path) -> dict:
    java = java_source.read_text(encoding="utf-8-sig")
    python = driver.read_text(encoding="utf-8-sig")
    resources = re.findall(r"RESOURCE_PROBES=Map\.of\((.*?)\);", java, re.S)
    actions = re.findall(r"if\(!Set\.of\((.*?)\)\.contains\(action\)&&!RESOURCE_PROBES\.containsKey\(action\)\)", java, re.S)
    if len(resources) != 1 or len(actions) != 1:
        raise ValueError("Fixture action declaration changed; review the preflight parser")
    allowed = set(re.findall(r'"([a-z][a-z-]+)"', resources[0] + actions[0]))
    requested = set()
    for node in ast.walk(ast.parse(python, filename=str(driver))):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "fixture":
            if node.args and isinstance(node.args[0], ast.Constant) and isinstance(node.args[0].value, str):
                requested.add(node.args[0].value)
        if isinstance(node, ast.For) and isinstance(node.target, ast.Name) and node.target.id == "action":
            if not isinstance(node.iter, (ast.List, ast.Tuple)) or not all(
                isinstance(item, ast.Constant) and isinstance(item.value, str) for item in node.iter.elts
            ):
                raise ValueError("Dynamic action list needs explicit preflight review")
            requested.update(item.value for item in node.iter.elts)
    if not requested:
        raise ValueError("No fixed fixture invocations found")
    return {
        "status": "PASS" if requested <= allowed else "FAIL",
        "unsupported": sorted(requested - allowed),
        "requested": sorted(requested),
        "javaSha256": hashlib.sha256(java_source.read_bytes()).hexdigest(),
        "driverSha256": hashlib.sha256(driver.read_bytes()).hexdigest(),
        "scope": "Action compatibility only; native, security and stage gates remain separate",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--driver", type=Path, required=True)
    parser.add_argument("--java", type=Path, default=Path(__file__).resolve().parents[2] /
                        "backend/platform-app/src/main/java/com/agentplatform/app/workspace/EnvironmentFixtureOperator.java")
    args = parser.parse_args()
    result = check(args.java, args.driver)
    print(json.dumps(result, sort_keys=True))
    raise SystemExit(0 if result["status"] == "PASS" else 1)
