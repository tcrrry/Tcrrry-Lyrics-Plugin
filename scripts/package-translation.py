"""Package an optional SDK translation ZIP with an uninstalled runtime and mandatory Chinese model."""
from pathlib import Path
import hashlib
import io
import json
import shutil
import zipfile

root=Path(__file__).resolve().parents[1]
module=root/'translation-plugin'
manifest=json.loads((module/'plugin.json').read_text(encoding='utf8'))
runtime=root/'offline-runtime/build/outputs/apk/release/offline-runtime-release-unsigned.apk'
model=root/'translation-plugin/assets/bundled-models/zh.zip'
assert model.is_file(), 'Stage the SHA-pinned official en_zh model before packaging'
expected='ab077c810b001d16667877d9fcda565a206718128a5163428ab5b6ace08936c4'
assert hashlib.sha256(model.read_bytes()).hexdigest()==expected
runtime_assets=module/'assets/pure-offline';runtime_assets.mkdir(parents=True,exist_ok=True)
shutil.copy2(runtime,runtime_assets/'runtime.apk')
native={}
with zipfile.ZipFile(runtime) as z:
    for name in z.namelist():
        if name.startswith('lib/') and name.endswith('/libtranslate_jni.so'):
            data=z.read(name);native[name.split('/')[1]]={'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
(runtime_assets/'runtime.json').write_text(json.dumps({'bytes':runtime.stat().st_size,'sha256':hashlib.sha256(runtime.read_bytes()).hexdigest(),'native':native},indent=2)+'\n',encoding='utf8')
(model.parent/'zh.json').write_text(json.dumps({'bytes':model.stat().st_size,'sha256':expected,'source':'https://dl.google.com/translate/offline/v5/high/r29/en_zh.zip','sdk':'ML Kit translate 17.0.3'},indent=2)+'\n',encoding='utf8')
apk=module/'build/outputs/apk/release/translation-plugin-release-unsigned.apk'
code=io.BytesIO()
with zipfile.ZipFile(apk) as a,zipfile.ZipFile(code,'w',zipfile.ZIP_DEFLATED) as jar:
    for name in a.namelist():
        if name.endswith('.dex'):jar.writestr(name,a.read(name))
    jar.writestr('META-INF/LICENSE.txt',(root/'LICENSE').read_bytes())
    jar.writestr('META-INF/NOTICE.txt',(root/'THIRD_PARTY_NOTICES.md').read_bytes())
out=root/'dist';out.mkdir(exist_ok=True)
target=out/f'Tcrrry-Translation-Plugin-{manifest["versionName"]}.zip'
with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as z:
    z.write(module/'plugin.json','plugin.json');z.writestr('code.jar',code.getvalue())
    for asset in sorted((module/'assets').rglob('*')):
        if asset.is_file():z.write(asset,'assets/'+asset.relative_to(module/'assets').as_posix())
    for file in [root/'LICENSE',root/'THIRD_PARTY_NOTICES.md',root/'docs/TRANSLATION-EXTENSION.md']:
        z.write(file,'assets/documentation/'+file.name)
assert target.stat().st_size<128*1024**2
print(json.dumps({'plugin':str(target),'bytes':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()},ensure_ascii=False))
