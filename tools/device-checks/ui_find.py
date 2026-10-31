# Prints the centre "x y" of the first UI node whose text contains argv[2] (equals it when argv[3] is
# "exact"); exits 1 if none.
import re, sys
s = open(sys.argv[1]).read()
for m in re.finditer(r'(?:text|content-desc)="([^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s):
    exact = len(sys.argv) > 3 and sys.argv[3] == "exact"
    if (m.group(1) == sys.argv[2]) if exact else (sys.argv[2] in m.group(1)):
        x1, y1, x2, y2 = map(int, m.group(2, 3, 4, 5)); print((x1 + x2) // 2, (y1 + y2) // 2); sys.exit(0)
sys.exit(1)
