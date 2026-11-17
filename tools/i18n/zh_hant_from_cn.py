"""Regenerate values-zh-rHK and values-zh-rTW from values-zh-rCN with OpenCC.

Run from the repo root, in a throwaway venv:
    python3 -m venv /tmp/opencc && /tmp/opencc/bin/pip install opencc-python-reimplemented==0.1.7
    /tmp/opencc/bin/python -I tools/i18n/zh_hant_from_cn.py
s2hk = Hong Kong standard characters, s2twp = Taiwan with Taiwan phrasing.
Hand fixes made after conversion must be re-applied (list them in the commit).
"""
import pathlib
import opencc

RES = pathlib.Path("app/src/main/res")
TARGETS = {"values-zh-rHK": "s2hk", "values-zh-rTW": "s2twp"}

for folder, config in TARGETS.items():
    cc = opencc.OpenCC(config)
    out = RES / folder
    out.mkdir(exist_ok=True)
    for src in sorted((RES / "values-zh-rCN").glob("*.xml")):
        (out / src.name).write_text(cc.convert(src.read_text(encoding="utf-8")), encoding="utf-8")
        print(out / src.name)
