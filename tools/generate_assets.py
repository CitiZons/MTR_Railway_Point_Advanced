"""Original small tile textures and bilingual labels; does not touch other packs."""
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

labels = {
'title':('道岔工坊 · 工程蓝图','POINT WORKSHOP / BLUEPRINT'),
'y':('Y 形双开道岔','Y TURNOUT'), 'diamond':('不互通平交口','DIAMOND CROSSING'),
'tab_geometry':('几何参数 → 截面参数','Geometry > Profile'), 'tab_profile':('截面参数 → 几何参数','Profile > Geometry'),
'length':('覆盖长度倍率','Length scale'), 'blade':('尖轨长 / 自动0','Blade / auto 0'), 'throw':('尖轨动程 m','Blade throw m'),
'flangeway':('轮缘槽宽 m','Flangeway m'), 'spacing':('岔枕间距 m','Tie spacing m'), 'sleeper_width':('岔枕宽 m','Tie width m'),
'sleeper_height':('岔枕厚 m','Tie depth m'), 'overhang':('枕端余量 m','Tie margin m'), 'gauge':('轨距 / 自动0','Gauge / auto 0'),
'rail_top':('轨高 / 自动0','Top / auto 0'), 'head_width':('轨头宽 / 自动0','Head / auto 0'), 'height':('整体高度 m','Height offset m'),
'frog_shift':('岔心偏移 m','Frog offset m'), 'duration':('转换时长 s','Throw time s'),
'frog_movable':('岔心：可动','Frog: movable'), 'frog_fixed':('岔心：固定','Frog: fixed'),
'enabled':('外观已启用','Enabled'), 'disabled':('外观已停用','Disabled'), 'profile':('切换轨型','Track profile'),
'isometric':('轴测视图','Isometric'), 'top':('俯视图','Top view'), 'test_motion':('试动尖轨','Test throw'), 'live':('实时','Live'),
'undo':('撤销','Undo'), 'reset':('恢复自动','Auto reset'), 'apply':('保存外观','Save'),
'help':('滚轮缩放 · 右键平移 · 点击岔枕后拖动','Wheel: zoom / Right-drag: pan / Drag a tie'),
'drag_sleeper':('拖动所选岔枕调整纵向位置','Drag selected tie to adjust its position'),
'idle':('空闲 · 保持上次位置','Idle / retaining last position'), 'waiting':('等待 BRsignal 只读状态','Waiting for BRsignal snapshot'),
'ambiguous':('通过方向不明确 · 保持位置','Ambiguous movement / holding position'),
'occupied':('列车正在通过','Train passing'), 'authorized':('跟随 BRsignal 获批进路','Following BRsignal authorization'),
'observed':('跟随列车路径 · 仅外观','Following observed path / visual only'),
'need_profile':('未识别轨型 · 点击切换轨型并校准','Unknown track / choose and calibrate profile'),
'no_point':('附近未发现 Y 道岔或不互通平交口','No nearby Y turnout or diamond crossing'),
'saved':('外观已保存','Appearance saved'), 'saving':('正在保存…','Saving...'), 'timeout':('保存超时 · 请重试','Save timed out / retry'),
'denied':('需要创造模式或管理员权限','Creative mode or operator required'), 'too_far':('请靠近道岔后保存','Move closer to save'),
'stale':('参数已由他人更新 · 已刷新','Changed by another editor / refreshed'), 'invalid':('参数无效','Invalid settings'),
'profile_native':('原生轨型','Native'), 'profile_missing':('资源缺失','Missing resource'), 'profile_attachment':('附属结构','Attachment'),
'profile_manual':('需要校准','Calibration required'), 'profile_descriptor':('轨型描述文件','Profile descriptor'), 'profile_inferred':('自动识别','Auto detected')}
(assets / 'lang').mkdir(exist_ok=True)
for locale, index in [('zh_cn', 0), ('en_us', 1)]:
    text = {'mtrpoint.' + key: value[index] for key, value in labels.items()}
    text['key.mtrpoint.blueprint'] = ('打开附近道岔蓝图', 'Open nearby point blueprint')[index]
    text['key.categories.mtrpoint'] = ('MTR 高级道岔', 'MTR Railway Points')[index]
    (assets / 'lang' / f'{locale}.json').write_text(json.dumps(text, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Generated original textures and labels:', assets)
