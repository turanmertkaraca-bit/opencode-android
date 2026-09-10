#!/usr/bin/env python3
"""axml_parse.py — pure-python binary AndroidManifest.xml reader.

The build gate (scripts/build_apk.sh) uses manifest_info() to prove the
APK was assembled by gradle: a hand-built APK without a proper uses-sdk
element reports minSdkVersion/targetSdkVersion None and the gate refuses
to ship it. Zero dependencies, no androguard needed.
"""
import struct
import sys
import zipfile
import io

RES_STRING_POOL_TYPE = 0x0001
RES_XML_START_ELEMENT_TYPE = 0x0102
UTF8_FLAG = 1 << 8


def _utf16_pool(buf, base, off):
    p = base + off
    (n,) = struct.unpack_from("<H", buf, p)
    if n & 0x8000:
        (n,) = struct.unpack_from("<I", buf, p)
        p += 2
    s = buf[p + 2:p + 2 + n * 2].decode("utf-16-le", "replace")
    return s


def _utf8_pool(buf, base, off):
    p = base + off
    n = u8 = buf[p]
    if n & 0x80:
        n = ((n & 0x7F) << 8) | buf[p + 1]
        u8 = 2
    p += u8
    nb = buf[p]
    if nb & 0x80:
        nb = ((nb & 0x7F) << 8) | buf[p + 1]
        p += 2
    else:
        p += 1
    return buf[p:p + nb].decode("utf-8", "replace")


def _parse_strings(buf, chunk_off, header_size):
    string_count, _style_count, flags, strings_start, _styles_start = \
        struct.unpack_from("<IIIII", buf, chunk_off + 8)
    base = chunk_off + strings_start
    is_utf8 = bool(flags & UTF8_FLAG)
    out = []
    for i in range(string_count):
        (off,) = struct.unpack_from("<I", buf,
                                    chunk_off + header_size + i * 4)
        out.append(_utf8_pool(buf, base, off) if is_utf8
                   else _utf16_pool(buf, base, off))
    return out


def _typed(buf, val_off):
    size, _res0, dtype = struct.unpack_from("<HBB", buf, val_off)
    (data,) = struct.unpack_from("<I", buf, val_off + 4)
    return dtype, data


def manifest_info(apk_path):
    """Read the fused manifest from an APK. Returns a dict with package,
    versionCode, versionName, minSdkVersion, targetSdkVersion, debuggable.
    Missing values are None — the build gate treats a missing uses-sdk as
    a hard failure."""
    with zipfile.ZipFile(apk_path) as z:
        buf = z.read("AndroidManifest.xml")
    if struct.unpack_from("<H", buf, 0)[0] != 0x0003:
        raise ValueError("not binary XML (no RES_XML_TYPE header)")
    strings = []
    off = 8  # skip the outer RES_XML_TYPE chunk header
    end = struct.unpack_from("<I", buf, 4)[0]
    attrs_by_elem = []
    while off < end:
        ctype, chdr, csize = struct.unpack_from("<HHI", buf, off)
        if ctype == RES_STRING_POOL_TYPE:
            strings = _parse_strings(buf, off, chdr)
        elif ctype == RES_XML_START_ELEMENT_TYPE:
            name_idx = struct.unpack_from("<I", buf, off + 20)[0]
            attr_start, attr_size, attr_count = struct.unpack_from(
                "<HHH", buf, off + 24)
            elem = strings[name_idx] if name_idx < len(strings) else "?"
            amap = {}
            # attributeStart is relative to the ResXMLTree_attrExt struct,
            # which starts AFTER the 16-byte node header — so the real
            # base is off + 16 + attributeStart (typically 36 absolute).
            for i in range(attr_count):
                a = off + 16 + attr_start + i * attr_size
                ansi, aname, _raw = struct.unpack_from("<III", buf, a)
                dtype, data = _typed(buf, a + 12)
                key = strings[aname] if aname < len(strings) else "?"
                if dtype == 0x03:                      # STRING
                    amap[key] = strings[data] if data < len(strings) else None
                elif dtype in (0x10, 0x11):            # INT_DEC / INT_HEX
                    amap[key] = data
                elif dtype == 0x12:                    # BOOL
                    amap[key] = bool(data)
                else:                                  # ref/float/etc.
                    amap[key] = None
            attrs_by_elem.append((elem, amap))
        off += csize
    info = {"package": None, "versionCode": None, "versionName": None,
            "minSdkVersion": None, "targetSdkVersion": None,
            "debuggable": None}
    for elem, amap in attrs_by_elem:
        if elem == "manifest":
            info["package"] = amap.get("package")
            vc = amap.get("versionCode")
            info["versionCode"] = str(vc) if vc is not None else None
            vn = amap.get("versionName")
            info["versionName"] = vn if isinstance(vn, str) else None
        elif elem == "uses-sdk":
            info["minSdkVersion"] = amap.get("minSdkVersion")
            info["targetSdkVersion"] = amap.get("targetSdkVersion")
        elif elem == "application":
            info["debuggable"] = amap.get("debuggable", False)
    return info


if __name__ == "__main__":
    i = manifest_info(sys.argv[1])
    for k in ("package", "versionCode", "versionName",
              "minSdkVersion", "targetSdkVersion", "debuggable"):
        print("%-16s %s" % (k, i[k]))
