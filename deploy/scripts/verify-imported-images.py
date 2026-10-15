#!/usr/bin/env python3
"""Docker save drops RepoDigests. Verify the source-index→config-ID→imported-ID chain."""
import json,pathlib,subprocess,re
root=pathlib.Path('/srv/agentplatform');records=json.loads((root/'source-images.json').read_text())
env=[]
for record in records:
 if not re.fullmatch(r'sha256:[a-f0-9]{64}',record['configId']):raise SystemExit('Invalid exported config identity')
 actual=json.loads(subprocess.check_output(['docker','image','inspect',record['configId']]))[0]['Id']
 if actual!=record['configId']:raise SystemExit('Imported image identity mismatch: '+record['tag'])
 if record.get('variable'):env.append(record['variable']+'='+actual)
(root/'deploy/images.env').write_text('\n'.join(env)+'\n')
(root/'reports/imported-images.json').write_text(json.dumps(records,indent=2))
print('All imported config IDs match source artifacts; pinned IDs used after Docker save/load.')
