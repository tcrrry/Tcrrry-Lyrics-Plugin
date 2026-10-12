"""Check that an embedded AM++ module still defines the SDK v1 methods we compile against."""
import argparse
import json
import re
import subprocess
import zipfile
from pathlib import Path
from host_dex import dex_classes

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('module', type=Path)
parser.add_argument('--javap', required=True)
args = parser.parse_args()
classes = {}
with zipfile.ZipFile(args.module) as archive:
    for name in archive.namelist():
        if name.endswith('.dex'):
            classes.update(dex_classes(archive.read(name)))
jar = Path(__file__).resolve().parents[1] / 'lib/ampp-plugin-api-v1.jar'
with zipfile.ZipFile(jar) as archive:
    names = [name[:-6].replace('/', '.') for name in archive.namelist() if name.endswith('.class')]
output = subprocess.check_output([args.javap, '-p', '-s', '-classpath', str(jar), *names], text=True)
owner = ''
method = None
checks = 0
failures = []
for line in output.splitlines():
    header = re.search(r'\b(?:class|interface|enum) ([\w.$]+)', line)
    if header:
        owner = header[1]
    elif line.strip().startswith('descriptor:'):
        if method:
            signature = method + line.split('descriptor:')[1].strip()
            checks += 1
            if signature not in classes.get('L'+owner.replace('.', '/')+';', {}).get('methods', []):
                failures.append(owner + ' ' + signature)
        method = None
    elif '(' in line:
        method = line.split('(')[0].split()[-1] if any(word in line for word in ['public ', 'protected ']) else None
        if method == owner:
            method = '<init>'
assert checks > 0, 'No SDK methods were checked'
print(json.dumps({'sdkApiMethodsChecked': checks, 'missing': failures}, indent=2))
if failures:
    raise SystemExit(1)
