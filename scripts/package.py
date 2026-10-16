#!/usr/bin/env python3
"""Create the author's SDK v1 ZIP from compiled DEX; never import the compiler APK."""
import argparse, hashlib, io, json, re, shutil, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
args = argparse.ArgumentParser()
args.add_argument('--output', type=Path, default=ROOT/'dist')
args.add_argument('--offline-variant', default='release', choices=['debug','release'])
args.add_argument('--variant', default='release', choices=['debug','release'])
args.add_argument('--plugin-only', action='store_true', help='Package the plugin/source without rebuilding or renaming the existing offline companion')
options = args.parse_args()
manifest = json.loads((ROOT/'plugin.json').read_text())
version = manifest['versionName']
out = options.output; out.mkdir(parents=True, exist_ok=True)
apk = ROOT/f'engine/build/outputs/apk/{options.variant}/engine-{options.variant}.apk'
if options.variant=='release' and not apk.exists(): apk=apk.with_name('engine-release-unsigned.apk')
code = io.BytesIO()
with zipfile.ZipFile(apk) as compiled, zipfile.ZipFile(code, 'w', zipfile.ZIP_DEFLATED) as jar:
    dex = sorted(n for n in compiled.namelist() if re.fullmatch(r'classes(?:\d+)?\.dex', n))
    assert dex, 'No Android DEX found'
    for name in dex:
        data=compiled.read(name)
        assert data[:4] == b'dex\n'
        jar.writestr(name, data)
    jar.writestr('META-INF/NOTICE.txt',(ROOT/'THIRD_PARTY_NOTICES.md').read_bytes())
    jar.writestr('META-INF/LICENSE.txt',(ROOT/'LICENSE').read_bytes())
plugin = out/f'Tcrrry-Lyrics-Plugin-{version}.zip'
with zipfile.ZipFile(plugin,'w',zipfile.ZIP_DEFLATED) as package:
    package.write(ROOT/'plugin.json','plugin.json')
    package.writestr('code.jar',code.getvalue())
    for asset in sorted((ROOT/'assets').rglob('*')):
        if asset.is_file(): package.write(asset, asset.relative_to(ROOT).as_posix())
    for name in ['README.md','THIRD_PARTY_NOTICES.md','LICENSE','docs/INSTALL.md','docs/VALIDATION.md']:
        package.write(ROOT/name,'assets/documentation/'+Path(name).name)
    for name in [f'docs/RELEASE-{version}.md', 'docs/NO-LYRICS-SPINNER-1807802486.md', 'docs/QQ-INCOMPLETE-1669861760.md']:
        if (ROOT/name).exists():
            package.write(ROOT/name,'assets/documentation/'+Path(name).name)
    if '-ruby-' in version:
        package.write(ROOT/'docs/RUBY-TRIAL.md','assets/documentation/RUBY-TRIAL.md')
assert plugin.stat().st_size<128*1024**2
if not options.plugin_only:
    companion=ROOT/f'offline/build/outputs/apk/{options.offline_variant}/offline-{options.offline_variant}.apk'
    assert companion.exists(), "Build the signed offline APK first (or use --offline-variant debug for development)"
    shutil.copyfile(companion,out/f'Tcrrry-Lyrics-Offline-{version}.apk')
source=out/f'Tcrrry-Lyrics-Plugin-{version}-source.zip'
with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED) as archive:
    for file in sorted(ROOT.rglob('*')):
        rel=file.relative_to(ROOT)
        if file.is_file() and not any(part in {'.git','.gradle','.kotlin','build','dist','__pycache__'} for part in rel.parts) and file.name not in {'local.properties','keystore.properties','.env','offline-signing.jks'} and file.suffix not in {'.jks','.keystore'}:
            archive.write(file,Path(ROOT.name)/rel)
artifacts=sorted(p for p in out.iterdir() if p.suffix in {'.zip','.apk'})
(out/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in artifacts))
print(json.dumps({'plugin':str(plugin),'dexFiles':dex,'bytes':plugin.stat().st_size,'artifacts':[p.name for p in artifacts]},ensure_ascii=False))
