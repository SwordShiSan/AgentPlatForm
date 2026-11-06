"""Compare a retained file index without emitting names, content or SQL dump rows."""
from pathlib import Path
import importlib.util,json,sys
root=Path(sys.argv[1]).resolve(strict=True)
spec=importlib.util.spec_from_file_location('release',root/'r5-release-control.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);w=module.Window(root)
w.stop_apps('r4')
header=None;rows=[]
with (root/'backup-r4/postgres.sql').open() as source:
    for line in source:
        if header is None:
            if line.startswith('COPY pf.file_entry ('):header=line.replace('COPY pf.file_entry (','COPY r54_original_file_entry (',1)
        else:
            if line.strip()==r'\.':break
            rows.append(line)
assert header and rows
sql='CREATE TEMP TABLE r54_original_file_entry (LIKE pf.file_entry);\n'+header+''.join(rows)+'\\.\n'
sql+="SELECT json_build_object('sameIds',NOT EXISTS(SELECT 1 FROM r54_original_file_entry b FULL JOIN pf.file_entry a USING(id) WHERE a.id IS NULL OR b.id IS NULL),'changedColumns',(SELECT json_agg(DISTINCT d.key) FROM r54_original_file_entry b JOIN pf.file_entry a USING(id) CROSS JOIN LATERAL jsonb_each(to_jsonb(a)) d WHERE d.value IS DISTINCT FROM to_jsonb(b)->d.key));"
result=json.loads(w.sql(sql));(root/'completion-file-projection.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
