#!/usr/bin/env python3
"""Yamalardan (patches/*.patch) çapa tabanlı düzenleme listesi (edits.json) üretir.
kullanım: make_edits.py <patches-klasörü> <upstream-klasörü> <edits.json>
Upstream klasörünün kopyası üzerinde yamalar sırayla simüle edilir; her hunk için
tek eşleşen EN KISA metin bloğu (çapa) seçilir."""
import json, re, sys, os, glob

def parse(path):
    files, cur = [], None
    for line in open(path, encoding="utf-8").read().split("\n"):
        if line.startswith("+++ b/"):
            cur = {"file": line[6:], "hunks": []}; files.append(cur)
        elif line.startswith("@@") and cur is not None:
            cur["hunks"].append([])
        elif cur and cur["hunks"] and line[:1] in (" ", "+", "-"):
            if line.startswith("--- ") and False: continue
            cur["hunks"][-1].append((line[0], line[1:]))
    return files

def blocks(h):
    idx = [i for i, (t, _) in enumerate(h) if t != " "]
    a, b = idx[0], idx[-1]
    pre = [x for _, x in h[:a]]; post = [x for _, x in h[b+1:]]
    mid = h[a:b+1]
    mo = [x for t, x in mid if t in (" ", "-")]
    mn = [x for t, x in mid if t in (" ", "+")]
    return pre, mo, mn, post

def candidates(pre, mo, mn, post):
    pure_ins = not mo
    combos = sorted(((p, q) for p in range(len(pre)+1) for q in range(len(post)+1)),
                    key=lambda c: (c[0]+c[1], c[1]))
    for p, q in combos:
        o = (pre[len(pre)-p:] if p else []) + mo + post[:q]
        n = (pre[len(pre)-p:] if p else []) + mn + post[:q]
        if not o: continue
        yield "\n".join(o) + "\n", "\n".join(n) + "\n"

def main(pdir, tdir, out):
    result = []
    for pf in sorted(glob.glob(os.path.join(pdir, "*.patch"))):
        entry = {"patch": os.path.basename(pf), "edits": []}
        for f in parse(pf):
            path = os.path.join(tdir, f["file"])
            text = open(path, encoding="utf-8").read()
            for h in f["hunks"]:
                pre, mo, mn, post = blocks(h)
                for o, n in candidates(pre, mo, mn, post):
                    if text.count(o) == 1:
                        entry["edits"].append({"file": f["file"], "old": o, "new": n})
                        text = text.replace(o, n); break
                else:
                    sys.exit("çapa bulunamadı: %s" % pf)
            open(path, "w", encoding="utf-8").write(text)
        result.append(entry)
    json.dump(result, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("edits:", sum(len(e["edits"]) for e in result))

main(*sys.argv[1:4])
