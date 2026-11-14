#!/usr/bin/env python3
"""Check the pinned modern potion icon constants against Mojang's live server.

Reads JAR_URL, SPAN_FROM, SPAN_TO, PATH_PREFIX and the icon table out of
common/src/main/java/com/bedwarsqol/hud/EffectIcons.java, makes the same single
HTTP Range request the mod makes, and checks every icon: local zip header, file
name, raw-deflate data of the pinned compressed size, SHA-1 of the inflated PNG.

Prints "23/23 verified" (or each failure) and exits non-zero on any failure.
Writes nothing to disk. Standard library only; run with `python3 -I`.
"""

import hashlib
import os
import re
import struct
import sys
import urllib.error
import urllib.request
import zlib

SOURCE = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir,
                      "common", "src", "main", "java", "com", "bedwarsqol", "hud", "EffectIcons.java")
USER_AGENT = "BedwarsQol/0.1 EffectIcons"
MAX_PNG = 65536


def constants(text):
    def one(pattern):
        m = re.search(pattern, text, re.S)
        if not m:
            sys.exit("cannot find %s in EffectIcons.java" % pattern)
        return m.group(1)

    url = one(r'JAR_URL\s*=\s*"([^"]+)"')
    span_from = int(one(r"SPAN_FROM\s*=\s*(\d+)L"))
    span_to = int(one(r"SPAN_TO\s*=\s*(\d+)L"))
    prefix = one(r'PATH_PREFIX\s*=\s*"([^"]+)"')
    table = [(n, int(o), int(c), h) for n, o, c, h in
             re.findall(r'new Entry\("([a-z_]+)",\s*(\d+)L,\s*(\d+),\s*"([0-9a-f]{40})"\)', text)]
    return url, span_from, span_to, prefix, table


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch(url, start, end):
    req = urllib.request.Request(url, headers={
        "Range": "bytes=%d-%d" % (start, end),
        "User-Agent": USER_AGENT,
        "Accept-Encoding": "identity",
    })
    opener = urllib.request.build_opener(NoRedirect)
    try:
        resp = opener.open(req, timeout=20)
    except urllib.error.HTTPError as e:
        sys.exit("FAIL: HTTP %d (want 206)" % e.code)
    with resp:
        if resp.status != 206:
            sys.exit("FAIL: HTTP %d (want 206)" % resp.status)
        cr = resp.headers.get("Content-Range", "")
        if not cr.startswith("bytes %d-%d/" % (start, end)):
            sys.exit("FAIL: Content-Range %r" % cr)
        want = end - start + 1
        body = resp.read(want)
    if len(body) != want:
        sys.exit("FAIL: got %d bytes, want %d" % (len(body), want))
    return body


def check(span, span_from, prefix, name, offset, csize, sha1):
    at = offset - span_from
    if at < 0 or at + 30 > len(span):
        return "local header outside the span"
    sig, _ver, _flags, method = struct.unpack_from("<IHHH", span, at)
    if sig != 0x04034B50:
        return "no local header signature"
    if method != 8:
        return "method %d, want deflate" % method
    name_len, extra_len = struct.unpack_from("<HH", span, at + 26)
    got_name = span[at + 30:at + 30 + name_len].decode("utf-8", "replace")
    if got_name != prefix + name + ".png":
        return "entry is %r" % got_name
    data = at + 30 + name_len + extra_len
    if data + csize > len(span):
        return "data runs past the span"
    d = zlib.decompressobj(-15)
    png = d.decompress(span[data:data + csize], MAX_PNG + 1)
    if not d.eof:
        return "deflate stream does not end within its compressed size"
    if len(png) > MAX_PNG:
        return "inflates past %d bytes" % MAX_PNG
    got = hashlib.sha1(png).hexdigest()
    if got != sha1:
        return "sha1 %s, want %s" % (got, sha1)
    return None


def main():
    with open(SOURCE, encoding="utf-8") as f:
        url, span_from, span_to, prefix, table = constants(f.read())
    if not table:
        sys.exit("no icon table found in EffectIcons.java")
    span = fetch(url, span_from, span_to)
    failures = 0
    for name, offset, csize, sha1 in table:
        problem = check(span, span_from, prefix, name, offset, csize, sha1)
        if problem:
            failures += 1
            print("FAIL %s: %s" % (name, problem))
    print("%d/%d verified" % (len(table) - failures, len(table)))
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
