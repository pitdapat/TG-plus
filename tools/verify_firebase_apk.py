"""Verify this standalone APK contains the intended Firebase client setup."""
import glob
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

module = Path('TMessagesProj_AppStandalone')
config = json.loads((module / 'google-services.json').read_text())
assert config['project_info']['project_id'] == 'telegram-plus-bb466'
assert not {'private_key', 'private_key_id', 'client_email'} & config.keys()
clients = [c for c in config['client'] if c['client_info']['android_client_info']['package_name'] == 'org.patrick.tgplus.web']
assert len(clients) == 1
expected = {
    'project_id': config['project_info']['project_id'],
    'gcm_defaultSenderId': config['project_info']['project_number'],
    'google_app_id': clients[0]['client_info']['mobilesdk_app_id'],
}
generated = list(module.glob('build/generated/res/google-services/**/values.xml'))
assert len(generated) == 1, 'Expected one generated Firebase resource file'
values = {n.attrib['name']: n.text for n in ET.parse(generated[0]).getroot()}
assert all(values.get(k) == v for k, v in expected.items()), 'Wrong generated Firebase project'
apks = glob.glob(str(module / 'build/outputs/apk/afat/debug/*.apk'))
assert len(apks) == 1
aapt = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/aapt2'
resources = subprocess.check_output([str(aapt), 'dump', 'resources', apks[0]], text=True)
assert all(v in resources for v in expected.values()), 'Firebase resources missing from APK'
manifest = subprocess.check_output([str(aapt), 'dump', 'xmltree', apks[0], '--file', 'AndroidManifest.xml'], text=True)
# Sibling service nodes share their indentation; retain each node's children.
service_nodes = re.findall(r'(?m)^([ \t]*)E: service[^\n]*\n([\s\S]*?)(?=^\1E:|\Z)', manifest)
push = [body for _, body in service_nodes if 'org.telegram.messenger.GcmPushListenerService' in body]
assert len(push) == 1, 'Expected one Telegram Firebase message handler'
assert 'com.google.firebase.MESSAGING_EVENT' in push[0], 'FCM intent filter missing'
assert re.search(r'android:exported[^\n]*=(?:false|\(type 0x12\)0x0)', push[0]), 'FCM handler must not be exported'
with zipfile.ZipFile(apks[0]) as apk:
    for name in apk.namelist():
        assert not name.endswith(('.pem', '.p12', '.jks', '.keystore')), 'Private key material in APK'
        if name.endswith('.json'):
            assert b'-----BEGIN PRIVATE KEY-----' not in apk.read(name), 'Service-account key in APK'
print('Firebase project resources, private service, and absence of bundled private keys verified.')
