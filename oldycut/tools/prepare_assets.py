from pathlib import Path
import zlib
root=Path(__file__).resolve().parents[1]
for file in (root/'assets').glob('*.ttf.zlib'):
    file.with_suffix('').write_bytes(zlib.decompress(file.read_bytes()))
