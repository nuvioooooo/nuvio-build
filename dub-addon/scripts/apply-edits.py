#!/usr/bin/env python3
"""edits.json'daki çapa tabanlı düzenlemeleri uygular (çalışma dizini = upstream klasörü).
Her yama için: önce çapa yöntemi (her 'old' metin dosyada TAM 1 kez geçmeli); olmazsa
özgün .patch dosyası `git apply --3way` ile denenir. İkisi de olmazsa hata verir (exit 2)."""
import json, os, subprocess, sys

addon = sys.argv[1]
entries = json.load(open(os.path.join(addon, "edits.json"), encoding="utf-8"))
failed = False
for e in entries:
    name = e["patch"]
    texts, ok = {}, True
    for ed in e["edits"]:
        t = texts.get(ed["file"])
        if t is None:
            t = open(ed["file"], encoding="utf-8").read()
        if t.count(ed["old"]) != 1:
            ok = False; break
        texts[ed["file"]] = t.replace(ed["old"], ed["new"])
    if ok:
        for f, t in texts.items():
            open(f, "w", encoding="utf-8").write(t)
        print("patch OK   %s (çapa)" % name)
        continue
    r = subprocess.run(["git", "apply", "--3way", os.path.join(addon, "patches", name)],
                       capture_output=True)
    if r.returncode == 0:
        print("patch OK   %s (yama, 3-way)" % name)
    else:
        print("::error::patch ÇAKIŞTI  %s" % name); failed = True
sys.exit(2 if failed else 0)
