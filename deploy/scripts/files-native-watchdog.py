"""Independent bounded recovery for the exact R51 controller process."""
from pathlib import Path
import importlib.util,json,os,signal,sys,time
root=Path(__file__).resolve().parent;pid=int(sys.argv[1]);identity=Path('/proc/'+str(pid)+'/stat').read_text().split()[21]
for _ in range(400):
 result=root/'reports/window-result.json'
 if result.exists() and json.loads(result.read_text()).get('r4Restored'):raise SystemExit(0)
 try:
  if Path('/proc/'+str(pid)+'/stat').read_text().split()[21]!=identity:break
 except FileNotFoundError:break
 time.sleep(3)
else:
 assert str(root/'files-native-window.py').encode() in Path('/proc/'+str(pid)+'/cmdline').read_bytes();assert Path('/proc/'+str(pid)+'/stat').read_text().split()[21]==identity;os.kill(pid,signal.SIGKILL)
if not (root/'reports/production-before.json').exists():raise SystemExit(0)
spec=importlib.util.spec_from_file_location('window',root/'files-native-window.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
with (m.switch.OPS/'switch.lock').open('a') as lock:
 m.fcntl.flock(lock,m.fcntl.LOCK_EX);m.restore(json.loads((root/'reports/production-before.json').read_text()));m.save('watchdog-restored',{'status':'PASS','r4Restored':True})
