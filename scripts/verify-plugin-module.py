"""Check compiled plugin method references and reflective Liquid ABIs against an embedded module."""
import argparse
import io
import json
from pathlib import Path
import struct
import zipfile
from host_dex import dex_classes, _uleb


def method_references(data):
    u32 = lambda offset: struct.unpack_from('<I', data, offset)[0]
    u16 = lambda offset: struct.unpack_from('<H', data, offset)[0]
    strings = []
    for index in range(u32(56)):
        _, offset = _uleb(data, u32(u32(60) + index * 4))
        strings.append(data[offset:data.index(0, offset)].decode('utf8', errors='replace'))
    types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]
    protos = []
    for i in range(u32(72)):
        base = u32(76) + i * 12
        args = u32(base + 8)
        params = '' if not args else ''.join(types[u16(args + 4 + j * 2)] for j in range(u32(args)))
        protos.append('(' + params + ')' + types[u32(base + 4)])
    for i in range(u32(88)):
        owner, proto, name = struct.unpack_from('<HHI', data, u32(92) + i * 8)
        yield types[owner], strings[name] + protos[proto]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('module', type=Path)
    parser.add_argument('plugin', type=Path, help='Compiler APK or plugin ZIP')
    args = parser.parse_args()
    classes = {}
    with zipfile.ZipFile(args.module) as archive:
        for name in archive.namelist():
            if name.endswith('.dex'):
                classes.update(dex_classes(archive.read(name)))

    def has_method(owner, signature, visited=None):
        visited = set() if visited is None else visited
        if owner in visited or owner not in classes:
            return False
        visited.add(owner)
        cls = classes[owner]
        return signature in cls['methods'] or any(has_method(base, signature, visited)
            for base in [cls['super'], *cls['interfaces']])

    references = set()
    with zipfile.ZipFile(args.plugin) as archive:
        if 'code.jar' in archive.namelist():
            archive = zipfile.ZipFile(io.BytesIO(archive.read('code.jar')))
        for name in archive.namelist():
            if name.endswith('.dex'):
                references.update((owner, signature) for owner, signature in method_references(archive.read(name))
                    if owner.startswith(('Lcom/kyant/backdrop/', 'Ldev/amenhancer/plugin/api/')))
    failures = [owner + '.' + signature for owner, signature in sorted(references)
        if not has_method(owner, signature)]

    f0 = 'Lkotlin/jvm/functions/Function0;'
    f1 = 'Lkotlin/jvm/functions/Function1;'
    f3 = 'Lkotlin/jvm/functions/Function3;'
    composer = 'Landroidx/compose/runtime/Composer;'
    tabs_base = f0 + f1 + 'Lcom/kyant/backdrop/Backdrop;ILandroidx/compose/ui/Modifier;J' + f1 * 2 + 'FFF' + f3
    variants = {
        '1012': ('LiquidBottomTabs-AKObu6Q(' + tabs_base + f3 + composer + 'III)V',
            'LiquidBottomTab(Landroidx/compose/foundation/layout/RowScope;' + f0 + 'Landroidx/compose/ui/Modifier;' + f3 + composer + 'II)V'),
        '1017': ('LiquidBottomTabs-g4fP9js(' + tabs_base + f0 + 'Lkotlin/jvm/functions/Function2;' + f0 + 'FFZ' + f3 + composer + 'III)V',
            'LiquidBottomTab(Landroidx/compose/foundation/layout/RowScope;' + f0 + 'Landroidx/compose/ui/Modifier;Landroidx/compose/ui/graphics/Shape;' + f3 + composer + 'II)V'),
    }
    owners = ['Lcom/kyant/backdrop/catalog/components/LiquidBottomTabsKt;',
        'Lcom/kyant/backdrop/catalog/components/LiquidBottomTabKt;']
    matched = [variant for variant, signatures in variants.items() if all(
        signature in classes.get(owner, {}).get('methods', []) and
        classes[owner]['method_access'][signature] & 9 == 9
        for owner, signature in zip(owners, signatures))]
    if not matched:
        failures.append('No supported public static Liquid ABI pair')
    report = {'compiledHostMethodReferences': len(references), 'liquidAbi': matched, 'missing': failures}
    print(json.dumps(report, indent=2))
    if failures:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
