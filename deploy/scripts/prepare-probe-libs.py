"""Clear only generated probe dependency directories before fresh packaging."""
import pathlib
import shutil

ROOT = pathlib.Path(__file__).resolve().parents[2]
for module in ['platform-types', 'platform-domain', 'platform-api', 'platform-infrastructure', 'platform-trigger', 'platform-app']:
    path = ROOT / 'backend' / module / 'target/probe-libs'
    if path.exists():
        resolved = path.resolve()
        if path.is_symlink() or resolved != path.absolute() or not resolved.is_relative_to(ROOT / 'backend'):
            raise SystemExit('Refusing redirected generated probe directory')
        shutil.rmtree(path)
print('Generated probe dependency directories cleared; source/runtime data untouched')
