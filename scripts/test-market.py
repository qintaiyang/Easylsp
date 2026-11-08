#!/usr/bin/env python3
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('market', Path(__file__).resolve().parents[1] / 'market/scripts/market.py')
market = importlib.util.module_from_spec(spec)
spec.loader.exec_module(market)

class MarketTest(unittest.TestCase):
    def test_signature_rejects_payload_context_and_key_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private, public = root / 'private.pem', root / 'public.pem'
            subprocess.run(['openssl', 'genpkey', '-algorithm', 'ED25519', '-out', str(private)], check=True)
            subprocess.run(['openssl', 'pkey', '-in', str(private), '-pubout', '-out', str(public)], check=True)
            payload = root / 'payload'; payload.write_bytes(b'fixed release bytes')
            metadata = root / 'metadata.json'
            context = dict(catalogPluginId='fixture', publisherId='author', releaseId='one', versionCode=1)
            market.sign(payload, private, 'test-key', 'release', context, metadata)
            self.assertEqual(context['releaseId'], market.verify(payload, metadata, public)['releaseId'])
            payload.write_bytes(b'changed bytes')
            with self.assertRaises(subprocess.CalledProcessError): market.verify(payload, metadata, public)
            payload.write_bytes(b'fixed release bytes')
            data = json.loads(metadata.read_text()); data['publisherId'] = 'other'; metadata.write_text(json.dumps(data))
            with self.assertRaises(subprocess.CalledProcessError): market.verify(payload, metadata, public)
    def test_catalog_rejects_unregistered_owners_and_invalid_state(self):
        row = dict(catalogPluginId='fixture', publisherId='author', releaseId='one', versionCode=1, state='PUBLISHED', downloadUrl='https://github.com/owner/repo/releases/download/v1/plugin.zip', signatureUrl='https://github.com/official/repo/releases/download/v1/signature.json', reportUrl='https://github.com/official/repo/issues/new')
        publishers = [dict(publisherId='author', githubAccountId=123)]
        self.assertEqual(1, len(market.catalog([row], publishers, 'one')['releases']))
        with self.assertRaises(ValueError): market.catalog([row], [], 'one')
        with self.assertRaises(ValueError): market.catalog([dict(row, state='UNKNOWN')], publishers, 'one')
        with self.assertRaises(ValueError): market.catalog([row, row], publishers, 'one')
    def test_joint_artifacts_and_dependency_pins(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            source=root/'plugin.c';source.write_text('int easylsp_plugin_init_v1(const void *h,const void *c) { (void)h;(void)c;return 0; }')
            library=root/'plugin.so'
            sdk=Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '/opt/android-sdk')
            compiler=sdk/'ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang'
            subprocess.run([str(compiler),'-shared','-fPIC','-Wl,-z,max-page-size=16384','-Wl,--hash-style=both',str(source),'-o',str(library)],check=True)
            manifest=dict(formatVersion=3,catalogPluginId='joint',publisherId='author',releaseId='one',versionCode=1,versionName='1',name='Joint',description='',license='MIT',sourceUrl='https://example.com/source',channel='test',changelog='',publishedAt='2026-11-06',runtime='JAVA_NATIVE',pluginAbi=1,sdkVersion='m7',minHostVersion=9,requiredCapabilities=['release.joint','release.dependencies'],artifacts=[dict(abi='arm64-v8a',minApi=31,artifactPath='native/arm64-v8a/plugin.so'),dict(abi='any',minApi=31,artifactPath='java/main.java')],target=dict(packageName='fixture.demo',processScope='main'),dependencies=[dict(sourceId='third',publisherId='author',catalogPluginId='dependency',releaseId='fixed')])
            archive=root/'joint.zip'
            def write():
                with zipfile.ZipFile(archive,'w') as z:
                    for name,data in {'manifest.json':json.dumps(manifest),'README.md':'fixture','LICENSE':'MIT fixture','native/arm64-v8a/plugin.so':library.read_bytes(),**({'java/main.java':'void onLoad() {}'} if manifest['runtime']=='JAVA_NATIVE' else {})}.items():z.writestr(name,data)
            write();self.assertEqual('JAVA_NATIVE',market.read_release(archive)['runtime'])
            manifest['runtime']='NATIVE';manifest['artifacts'].pop()
            manifest['target'].update(processScope='exact',processType='isolated')
            for process in ['com.google.android.webview:sandboxed_process0','fixture.demo','fixture.demo:']:
                manifest['target']['processName']=process;write()
                with self.assertRaises(ValueError):market.read_release(archive)
            manifest['target']['processName']='fixture.demo:isolated:fixture.demo.Service';write()
            self.assertEqual('NATIVE',market.read_release(archive)['runtime'])
            manifest['runtime']='JAVA_NATIVE';manifest['artifacts'].append(dict(abi='any',minApi=31,artifactPath='java/main.java'))
            manifest['target']=dict(packageName='fixture.demo',processScope='main')
            manifest['dependencies'][0]['releaseId']='../latest';write()
            with self.assertRaises(ValueError):market.read_release(archive)
            manifest['dependencies'][0]['releaseId']='fixed';manifest['artifacts'].pop();write()
            with self.assertRaises(ValueError):market.read_release(archive)

    def test_component_release_capabilities_and_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / 'program.zip'
            manifest = dict(formatVersion=3,catalogPluginId='program',publisherId='author',releaseId='one',versionCode=1,versionName='1',name='Program',description='',license='MIT',sourceUrl='https://example.com/source',channel='test',changelog='',publishedAt='2026-11-06',runtime='BEANSHELL',pluginAbi=1,sdkVersion='v2',minHostVersion=10,requiredCapabilities=['java.beanshell','java.components','java.services'],artifacts=[dict(abi='any',minApi=31,artifactPath='java/main.java')],target=dict(packageName='fixture.demo',processScope='main'))
            program = dict(schema=1, components=[dict(id='echo',path='src/echo.bsh')], services=dict(provides={'example.echo':1}))
            files = {'README.md':'fixture','LICENSE':'MIT','java/main.java':'','java/plugin.json':json.dumps(program),'java/src/echo.bsh':'return this;'}
            def read():
                with zipfile.ZipFile(archive,'w') as z:
                    for name,data in dict(files, **{'manifest.json':json.dumps(manifest)}).items(): z.writestr(name,data)
                return market.read_release(archive)
            self.assertEqual('BEANSHELL', read()['runtime'])
            manifest['requiredCapabilities'].remove('java.services')
            with self.assertRaises(ValueError): read()
            manifest['requiredCapabilities'].append('java.services')
            manifest['minHostVersion'] = 9
            with self.assertRaises(ValueError): read()
            manifest['minHostVersion'] = 10
            files['java/info.prop'] = 'reserved'
            with self.assertRaises(ValueError): read()
            del files['java/info.prop']; del files['java/src/echo.bsh']
            with self.assertRaises(ValueError): read()

    def test_config_contract(self):
        schema = dict(type='object', properties=dict(count=dict(type='integer', minimum=1, maximum=5)), required=['count'], additionalProperties=False)
        market.validate_config(schema, dict(count=2))
        for value in [dict(count=True), dict(count=0), dict(count=2, other=1), {}]:
            with self.assertRaises(ValueError): market.validate_config(schema, value)

if __name__ == '__main__': unittest.main()
