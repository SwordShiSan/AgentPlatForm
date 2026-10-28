"""Offline archive-chain guards; no Docker, network or production resources."""
from pathlib import Path
import hashlib
import importlib.util
import io
import json
import tarfile
import unittest

spec=importlib.util.spec_from_file_location('image_export',Path(__file__).resolve().parents[1]/'scripts/export-project-image.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
SOURCE='a'*40


def raw(value):return json.dumps(value,sort_keys=True).encode()
def sha(value):return 'sha256:'+hashlib.sha256(value).hexdigest()
def archive(entries):
    output=io.BytesIO()
    with tarfile.open(fileobj=output,mode='w') as package:
        for name,body in entries.items():
            header=tarfile.TarInfo(name);header.size=len(body);package.addfile(header,io.BytesIO(body))
    output.seek(0);return tarfile.open(fileobj=output,mode='r')


class ExportIdentityTest(unittest.TestCase):
    def fixture(self,oci=True,extra=False,tamper=False):
        config=raw({'architecture':'amd64','os':'linux','rootfs':{'diff_ids':['sha256:'+'1'*64]},'config':{'Labels':{'org.opencontainers.image.revision':SOURCE}}})
        config_id=sha(config);name='blobs/sha256/'+config_id.split(':')[1];entries={name:config};manifest=[{'Config':name,'Layers':[]}]
        actual={'Id':config_id,'RootFS':{'Layers':['sha256:'+'1'*64]}}
        if extra:
            other=raw({'architecture':'amd64','os':'linux','rootfs':{'diff_ids':['sha256:'+'2'*64]},'config':{'Labels':{'org.opencontainers.image.revision':'b'*40}}})
            other_name='blobs/sha256/'+sha(other).split(':')[1];entries[other_name]=other;manifest.append({'Config':other_name,'Layers':[]})
        if oci:
            platform=raw({'config':{'digest':config_id}});platform_id=sha(platform);entries['blobs/sha256/'+platform_id.split(':')[1]]=platform
            index=raw({'manifests':[{'digest':platform_id,'platform':{'architecture':'amd64','os':'linux'}}]});index_id=sha(index)
            entries['blobs/sha256/'+index_id.split(':')[1]]=b'corrupt' if tamper else index
            actual.update(Id=index_id,Descriptor={'digest':index_id})
        entries['manifest.json']=raw(manifest)
        return archive(entries),actual,config_id
    def test_legacy_archive_keeps_config_identity(self):
        package,actual,expected=self.fixture(oci=False)
        with package:result=module.exported_identity(package,actual,SOURCE)
        self.assertEqual(expected,result['configId']);self.assertIsNone(result['indexDigest'])
    def test_desktop_index_is_not_compared_directly_to_platform_config(self):
        package,actual,expected=self.fixture(extra=True)
        with package:result=module.exported_identity(package,actual,SOURCE)
        self.assertNotEqual(actual['Id'],expected);self.assertEqual(expected,result['configId']);self.assertEqual(1,len(result['exportManifest']))
    def test_wrong_original_source_cannot_relabel_an_older_image(self):
        package,actual,_=self.fixture()
        with package,self.assertRaises(AssertionError):module.exported_identity(package,actual,'b'*40)
    def test_tampered_descriptor_blob_is_rejected(self):
        package,actual,_=self.fixture(tamper=True)
        with package,self.assertRaises(AssertionError):module.exported_identity(package,actual,SOURCE)


if __name__=='__main__':unittest.main()
