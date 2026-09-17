"""Original small tile textures and validation of maintained trilingual labels; does not touch other packs."""
from pathlib import Path
import json, random
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
assets = ROOT / 'src/main/resources/assets/mtr_railway_point_advanced'
(assets / 'textures').mkdir(parents=True, exist_ok=True)
rng = random.Random(403)
for name, base in [('steel', (143, 151, 153)), ('timber', (81, 65, 46)), ('concrete', (137, 140, 132))]:
    im = Image.new('RGB', (32, 32))
    for y in range(32):
        for x in range(32):
            noise = rng.randrange(-8, 9)
            if name == 'timber': noise += ((x * 7 + y // 12) % 9 - 4) * 3
            if name == 'steel': noise += 9 if x in (2, 3, 4) else 0
            im.putpixel((x, y), tuple(max(0, min(255, c + noise)) for c in base))
    im.save(assets / 'textures' / f'{name}.png')

# Translations are maintained directly in lang/*.json; do not overwrite them.
translations = [json.loads((assets / 'lang' / f'{locale}.json').read_text(encoding='utf-8')) for locale in ('zh_cn', 'ja_jp', 'en_us')]
assert all(set(t) == set(translations[0]) and all(t.values()) for t in translations)
print('Generated original textures; validated Chinese/Japanese/English labels:', assets)
