# Usage: python3 -I ui_rows.py dump.xml... -> "label: enabled|DISABLED" for the tracked Settings rows.
import sys, xml.etree.ElementTree as ET
TRACK = ["Resilient recording", "Offline recording (no Wi-Fi)", "Keep Wireless debugging on for recording",
         "Never turn Wireless debugging off",
         "Record VoIP calls", "Record app calls automatically", "Also skip ignored contacts"]
found = {}
for path in sys.argv[1:]:
    root = ET.parse(path).getroot()
    parent = {c: p for p in root.iter() for c in p}
    for n in root.iter("node"):
        t = n.get("text")
        if t in TRACK and t not in found:
            # The label's own row: its direct parent. A disabled row is neither clickable nor focusable,
            # so searching upward for those would skip it and land on an enabled outer container.
            a = parent.get(n)
            if a is not None:
                found[t] = a.get("enabled") == "true"
for t in TRACK:
    print(f"  {t}: {'-' if t not in found else ('enabled' if found[t] else 'DISABLED')}")
