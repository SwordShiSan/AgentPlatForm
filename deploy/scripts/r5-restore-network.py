"""Choose one bounded, non-overlapping private restore pair before maintenance."""
import ipaddress, json, subprocess

PAIRS=[['10.236.'+str(n)+'.0/24','10.236.'+str(n+1)+'.0/24'] for n in range(120,160,2)]

def used_networks():
    used=[]
    for identity in subprocess.check_output(['docker','network','ls','-q'],text=True).split():
        row=json.loads(subprocess.check_output(['docker','network','inspect',identity]))[0]
        used.extend(ipaddress.ip_network(x['Subnet']) for x in (row.get('IPAM',{}).get('Config') or []) if x.get('Subnet') and ':' not in x['Subnet'])
    used.extend(ipaddress.ip_network(x['dst'],strict=False) for x in json.loads(subprocess.check_output(['ip','-j','route'])) if x.get('dst') not in [None,'default'] and ':' not in x['dst'])
    return used

def choose(used):
    for pair in PAIRS:
        if all(not ipaddress.ip_network(net).overlaps(existing) for net in pair for existing in used):return pair
    raise RuntimeError('No free restore pair in the bounded candidate pool; no network changed')

def validate(pair,used):
    assert pair in PAIRS and all(not ipaddress.ip_network(net).overlaps(existing) for net in pair for existing in used),'Persisted restore pair now overlaps; refuse before copying data'
