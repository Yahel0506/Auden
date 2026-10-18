#!/usr/bin/env python3
"""Adds a string to every app locale (LocaleStringsTest wants all base keys everywhere).

usage: tools/add-string.py [--shared] NAME "English text" [lang=text ...]
Locales without a given translation get the English text.
"""
import glob, re, sys
from xml.sax.saxutils import escape

args = sys.argv[1:]
shared = args and args[0] == "--shared"
if shared:
    args = args[1:]
name, english, *pairs = args
pattern = "sharedUi/src/commonMain/composeResources/values*/strings.xml" if shared else "app/src/main/res/values*/strings.xml"
tr = dict(p.split("=", 1) for p in pairs)
for path in sorted(glob.glob(pattern)):
    folder = path.split("/")[-2]
    lang = "en" if folder == "values" else folder[len("values-"):]
    text = tr.get(lang) or tr.get(lang.split("-")[0]) or english
    s = open(path, encoding="utf8").read()
    if re.search(rf'<string name="{re.escape(name)}"', s):
        continue
    text = escape(text).replace("'", "\\'")
    s = s.replace("</resources>", f'    <string name="{name}">{text}</string>\n</resources>', 1)
    open(path, "w", encoding="utf8").write(s)
