"""把 d8 产出的 classes.dex 追加进 aapt2 link 生成的 base.apk。

用法:
    python scripts/add_dex.py <build-dir>

build.sh 会自动传入中间产物目录；单独运行时默认使用 ./build。
"""
import shutil
import sys
import zipfile
from pathlib import Path

build = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build")
base = build / "base.apk"
unsigned = build / "unsigned.apk"
dex = build / "dex" / "classes.dex"

for f in (base, dex):
    if not f.is_file():
        raise SystemExit("缺少构建产物: %s" % f)

shutil.copyfile(base, unsigned)
with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
print("  dex packed")
