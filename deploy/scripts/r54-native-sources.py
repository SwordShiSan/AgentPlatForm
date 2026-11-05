"""Bounded real PDF extraction and immutable file-version input; no supplier calls.

Uses the existing R54 fixture and its original journal. Uncertain mutations are
queried, never retried with a new identity.
"""
from pathlib import Path
import hashlib, importlib.util, json, time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('r54', ROOT/'deploy/scripts/r54-native-acceptance.py')
r54 = importlib.util.module_from_spec(spec); spec.loader.exec_module(r54)
common, api, DATA = r54.common, r54.api, r54.DATA


def pdf_fixture():
    """One fixed, non-active test document, without external assets."""
    stream = b'BT /F1 12 Tf 40 700 Td (R54 PDF source 666) Tj ET'
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>',
               b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
               b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
               b'<< /Length '+str(len(stream)).encode()+b' >>\nstream\n'+stream+b'\nendstream']
    data = b'%PDF-1.4\n'; offsets = []
    for number, value in enumerate(objects, 1):
        offsets.append(len(data)); data += str(number).encode()+b' 0 obj\n'+value+b'\nendobj\n'
    start = len(data); data += b'xref\n0 6\n0000000000 65535 f \n'
    data += b''.join(f'{offset:010d} 00000 n \n'.encode() for offset in offsets)
    return data+b'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n'+str(start).encode()+b'\n%%EOF\n'


def file_upload(client, journal, workspace, label, content, base=None):
    body = {'path':'fixed-source.txt','bytes':len(content),'sha256':common.sha(content)}
    headers = {}
    if base:
        body.update(fileId=base['id'],baseVersionId=base['versionId'])
        headers['If-Match']='"'+str(base['revision'])+'"'
    upload = journal.once(client,label+':create',{'method':'POST','path':'/api/v1/workspaces/'+workspace+'/uploads','body':body},body,headers)
    path = '/api/v1/uploads/'+upload['id']
    current = common.get(client,path)[1]
    transport = journal.freeze(label+':transport',{'uploadId':upload['id'],'bytes':len(content),'sha256':common.sha(content)})
    if current['state']=='CREATED':
        assert transport['phase']=='FROZEN','Query the original uncertain transfer'
        transport['phase']='SENT'; journal.write()
        client.request('PUT',path+'/content',content,{'Content-Type':'application/octet-stream'})
        transport['phase']='ACKNOWLEDGED'; journal.write()
    operation = journal.once(client,label+':complete',{'method':'POST','path':path+'/complete'})
    assert api.wait_operation(client,operation['id'])['state']=='COMMITTED'
    return common.get(client,'/api/v1/files/'+upload['fileId'])[1]


def main():
    client=api.Client(); journal=common.Journal()
    original=r54.attachment(client,journal,'r54-generated.pdf',pdf_fixture())
    extracted=r54.extraction(client,journal,'r54-pdf',{'attachmentId':original['id']})
    workspace=journal.once(client,'fixed:workspace',{'method':'POST','path':'/api/v1/workspaces','body':{'name':'R54 fixed source'}},{'name':'R54 fixed source'})
    for _ in range(90):
        view=common.get(client,'/api/v1/workspaces/'+workspace['id'])[1]
        if view['state']=='READY': break
        assert view['state'] not in ['ERROR','BLOCKED']; time.sleep(1)
    else: raise RuntimeError('Query the original workspace operation')
    old_body=b'R54_FIXED_SOURCE_666 original immutable file\n'
    first_key=journal.rows.get('fixed:v1:metadata')
    if first_key:
        first=first_key['request']
    else:
        first=file_upload(client,journal,workspace['id'],'fixed:v1',old_body)
        journal.freeze('fixed:v1:metadata',first)
    text=r54.extraction(client,journal,'fixed:v1',{'fileId':first['id'],'versionId':first['versionId']})
    second=file_upload(client,journal,workspace['id'],'fixed:v2',b'R54_CHANGED_CURRENT_999 replacement\n',first)
    assert first['id']==second['id'] and first['versionId']!=second['versionId']
    refs=[{'kind':'FILE_VERSION','fileId':first['id'],'versionId':first['versionId'],'extractionId':text['id']}]
    body={'title':'R54 immutable file source','initialTask':{'input':{'text':'fixture:r54-document read the fixed original','attachments':refs},'options':{'modelId':'0199e000-0000-7000-8000-000000000002','credentialSource':'PLATFORM','planMode':False,'permissionMode':'FULL'}}}
    conversation=journal.once(client,'fixed:conversation',{'method':'POST','path':'/api/v1/conversations','body':body},body)
    task=r54.wait(client,'/api/v1/tasks/'+conversation['initialTask']['taskId'],terminal=('SUCCEEDED','FAILED','BLOCKED','CANCELLED'))
    assert task['status']=='SUCCEEDED',task.get('reasonCode')
    snapshot=common.get(client,'/api/v1/conversations/'+conversation['id']+'/snapshot')[1]
    assert 'source-marker=R54_FIXED_SOURCE_666' in json.dumps(snapshot)
    actual=common.get(client,'/api/v1/files/'+first['id']+'/content?versionId='+first['versionId'],binary=True)[1]
    assert actual==old_body
    common.atomic(DATA/'native-fixed-source-result.json',{'status':'PASS','supplierCalls':0,'pdfExtractionId':extracted['id'],'workspaceId':workspace['id'],'fileId':first['id'],'pinnedVersionId':first['versionId'],'replacementVersionId':second['versionId'],'taskId':task['taskId'],'originalSha256':hashlib.sha256(actual).hexdigest(),'scope':'Actual PDF extraction and SDK model input using old fixed file version after replacement; no supplier calls'})
    print('R54_NATIVE_PDF_FIXED_SOURCE_PASS')


if __name__=='__main__':main()
