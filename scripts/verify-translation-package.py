"""Validate the optional translation ZIP and exact SDK/native/Chinese payload pins."""
import io
import json
import hashlib
from pathlib import Path
import zipfile
from host_dex import dex_classes

root=Path(__file__).resolve().parents[1]
manifest=json.loads((root/'translation-plugin/plugin.json').read_text(encoding='utf8'))
package=root/f'dist/Tcrrry-Translation-Plugin-{manifest["versionName"]}.zip'
sha=lambda data:hashlib.sha256(data).hexdigest()
with zipfile.ZipFile(package) as outer:
    assert len(outer.namelist())==len(set(outer.namelist()))
    assert all(name in ['plugin.json','code.jar'] or name.startswith('assets/') for name in outer.namelist())
    assert json.loads(outer.read('plugin.json'))==manifest
    with zipfile.ZipFile(io.BytesIO(outer.read('code.jar'))) as code:
        classes={}
        for name in code.namelist():
            if name.endswith('.dex'):classes.update(dex_classes(code.read(name)))
    assert 'L'+manifest['entryClass'].replace('.','/')+';' in classes
    assert not any(c.startswith(('Ldev/amenhancer/','Lcom/google/','Lcom/apple/')) for c in classes)
    runtime=outer.read('assets/pure-offline/runtime.apk')
    pins=json.loads(outer.read('assets/pure-offline/runtime.json'))
    assert len(runtime)==pins['bytes'] and sha(runtime)==pins['sha256']
    model=outer.read('assets/bundled-models/zh.zip')
    model_pin=json.loads(outer.read('assets/bundled-models/zh.json'))
    assert sha(model)==model_pin['sha256']=='ab077c810b001d16667877d9fcda565a206718128a5163428ab5b6ace08936c4'
    assert len(model)==model_pin['bytes']
    with zipfile.ZipFile(io.BytesIO(runtime)) as payload:
        for abi,pin in pins['native'].items():
            native=payload.read(f'lib/{abi}/libtranslate_jni.so')
            assert len(native)==pin['bytes'] and sha(native)==pin['sha256']
        sdk_metadata=[]
        for name in payload.namelist():
            if name.startswith('res/') and name.endswith('.json'):
                value=json.loads(payload.read(name))
                if 'PKG_HIGH' in value:sdk_metadata.append(value)
        assert any(meta['PKG_HIGH']['en_zh']['HASH']==sha(model) for meta in sdk_metadata)
    assert package.stat().st_size<128*1024**2
    print(json.dumps({'sdkZip':True,'entryDefined':True,'noSdkOrHostDefinitions':True,'payloadPinsMatch':True,
        'bundledChineseMatchesSdkMetadata':True,'nativeAbis':sorted(pins['native']),
        'bytes':package.stat().st_size,'sha256':sha(package.read_bytes())},indent=2))
