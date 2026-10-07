#!/usr/bin/env python3
"""Artifact validation without running plugin code or claiming device testing."""
import io, json, hashlib, struct, zipfile, sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
package=Path(sys.argv[1]) if len(sys.argv)>1 else root/'dist/Tcrrry-Lyrics-Plugin-1.0.2.zip'
manifest=json.loads((root/'plugin.json').read_text())
pins=json.loads((root/'docs/provenance.json').read_text())
with zipfile.ZipFile(package) as archive:
    assert len(archive.namelist())==len(set(archive.namelist()))
    assert all(name in ['plugin.json','code.jar'] or name.startswith('assets/') for name in archive.namelist())
    assert json.loads(archive.read('plugin.json'))==manifest
    for file, expected in pins['files'].items():
        assert hashlib.sha256((root/file).read_bytes()).hexdigest()==expected,file
        if file.startswith('assets/'):
            assert hashlib.sha256(archive.read(file)).hexdigest()==expected,file
    jar=zipfile.ZipFile(io.BytesIO(archive.read('code.jar')))
    classes=set();dexes=[]
    for name in jar.namelist():
        if not name.endswith('.dex'):assert name.startswith('META-INF/');continue
        dexes.append(name);data=jar.read(name);number=lambda offset:struct.unpack_from('<I',data,offset)[0]
        for i in range(number(96)):
            type_id=number(number(100)+i*32)
            string_id=number(number(68)+type_id*4)
            off=number(number(60)+string_id*4)
            while data[off]&128:off+=1
            off+=1;end=data.index(0,off);classes.add(data[off:end].decode())
    assert 'L'+manifest['entryClass'].replace('.','/')+';' in classes
    assert not any(c.startswith(('Ldev/amenhancer/plugin/api/','Ldev/amenhancer/module/','Lcom/google/mlkit/')) for c in classes)
    assert not any(c.startswith(('Lcom/apple/android/music/', 'Lq8/', 'Ldev/amenhancer/plugin/runtime/')) for c in classes), 'Test host/store class leaked into DEX'
    assert 'Landroidx/recyclerview/widget/RecyclerView$D;' not in classes
    assert 'Lcom/google/android/flexbox/R$styleable;' not in classes
    with zipfile.ZipFile(root/'dist/Tcrrry-Lyrics-Offline-1.0.2.apk') as apk:
        native=[name for name in apk.namelist() if name.startswith('lib/') and name.endswith('.so')]
        assert native,'ML Kit native libraries missing in companion'
    print(json.dumps({'entryDefined':True,'noTestHostClasses':True,'dexFiles':dexes,'definedClasses':len(classes),'noSdkDefinitions':True,'noOriginalModuleNamespace':True,'dictionaryPinsMatch':True,'nativeCodeOnlyInCompanion':True,'companionNativeLibraries':native},indent=2))
