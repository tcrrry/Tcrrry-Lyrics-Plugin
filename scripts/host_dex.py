"""Standard-library DEX descriptor reader, derived from the validated host verifier."""
import struct

def _uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, offset
    raise ValueError("Invalid ULEB128")


def dex_classes(data):
    """Return class descriptors with superclass, interfaces, methods and fields."""
    if not data.startswith(b"dex\n"):
        raise ValueError("Not a DEX file")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def u16(offset):
        return struct.unpack_from("<H", data, offset)[0]

    strings = []
    for i in range(u32(56)):
        _, offset = _uleb(data, u32(u32(60) + 4 * i))
        strings.append(data[offset:data.index(0, offset)].decode("utf8", errors="replace"))
    types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]
    protos = []
    for i in range(u32(72)):
        base = u32(76) + i * 12
        parameters = u32(base + 8)
        params = "" if not parameters else "".join(
            types[u16(parameters + 4 + j * 2)] for j in range(u32(parameters))
        )
        protos.append("(" + params + ")" + types[u32(base + 4)])
    field_defs = []
    for i in range(u32(80)):
        _, type_idx, name_idx = struct.unpack_from("<HHI", data, u32(84) + i * 8)
        field_defs.append((strings[name_idx], types[type_idx]))
    method_defs = []
    for i in range(u32(88)):
        _, proto_idx, name_idx = struct.unpack_from("<HHI", data, u32(92) + i * 8)
        method_defs.append(strings[name_idx] + protos[proto_idx])
    classes = {}
    for i in range(u32(96)):
        base = u32(100) + i * 32
        class_name = types[u32(base)]
        superclass = u32(base + 8)
        offset = u32(base + 24)
        methods = set()
        fields = {}
        method_access = {}
        field_access = {}
        if offset:
            counts = []
            for _ in range(4):
                count, offset = _uleb(data, offset)
                counts.append(count)
            for section in counts[:2]:
                index = 0
                for _ in range(section):
                    delta, offset = _uleb(data, offset)
                    index += delta
                    flags, offset = _uleb(data, offset)
                    fields[field_defs[index][0]] = field_defs[index][1]
                    field_access[field_defs[index][0]] = flags
            for section in counts[2:]:
                index = 0
                for _ in range(section):
                    delta, offset = _uleb(data, offset)
                    index += delta
                    flags, offset = _uleb(data, offset)
                    _, offset = _uleb(data, offset)
                    methods.add(method_defs[index])
                    method_access[method_defs[index]] = flags
        classes[class_name] = {
            "super": types[superclass] if superclass != 0xFFFFFFFF else None,
            "interfaces": [types[u16(u32(base + 12) + 4 + j * 2)]
                           for j in range(u32(u32(base + 12)))] if u32(base + 12) else [],
            "methods": methods,
            "fields": fields,
            "method_access": method_access,
            "field_access": field_access,
        }
    return classes
