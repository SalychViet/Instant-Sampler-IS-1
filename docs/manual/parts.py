# Общие куски руководства IS-1 (вёрстка, скриншоты с выносками) для русской и английской версий.
# Координаты выносок — в пикселях скриншота, приведённого к 2000x900.
import html
import os
import re

# IS1_EDITION=try python3 build_install.py  ->  инструкция по установке ISL-1 TRY, без него — ISL-1.
TRY = os.environ.get("IS1_EDITION") == "try"
NAME = "ISL-1 TRY" if TRY else "ISL-1"

# Скриншоты с фирменными логотипами лежат в приватной папке branding/manual (её нет в открытом коде).
BRANDING = "../../branding/manual"


def image_path(src):
    if src == "panel.png":
        src = "isl_panel.png"
    if os.path.exists(os.path.join(BRANDING, src)):
        return f"{BRANDING}/{src}"
    return src


W, H = 2000, 900


def pct(v, total):
    return f"{v / total * 100:.3f}%"


def crop(x, y, w, h, marks=(), cls="", src="panel.png", overlays=()):
    """Кусок скриншота (x, y, w, h в координатах 2000x900) с номерами-выносками.

    overlays — надписи поверх скриншота (когда текст в приложении поменялся после съёмки):
    (ox, oy, ow, oh, текст, размер шрифта в единицах 2000x900, CSS-стиль).
    """
    src = image_path(src)
    img = (f'<img src="{src}" style="width:{W / w * 100:.3f}%;'
           f'left:{-x / w * 100:.3f}%;top:{-y / h * 100:.3f}%">')
    m = "".join(
        f'<span class="mark" style="left:{pct(mx - x, w)};top:{pct(my - y, h)}">{n}</span>'
        for n, mx, my in marks)
    o = "".join(
        f'<span class="patch" style="left:{pct(ox - x, w)};top:{pct(oy - y, h)};width:{pct(ow, w)};height:{pct(oh, h)};'
        f'font-size:{fs / w * 100:.3f}cqw;{st}">{t}</span>'
        for ox, oy, ow, oh, t, fs, st in overlays)
    return f'<div class="crop {cls}" style="aspect-ratio:{w}/{h}">{img}{o}{m}</div>'


def legend(items):
    return '<ol class="legend">' + "".join(
        f'<li><b class="num">{n}</b><div><b>{html.escape(t)}</b> — {d}</div></li>' for n, t, d in items) + "</ol>"


def table(head, rows, cls=""):
    h = "".join(f"<th>{c}</th>" for c in head)
    b = "".join("<tr>" + "".join(f"<td>{c}</td>" for c in r) + "</tr>" for r in rows)
    return f'<table class="{cls}"><thead><tr>{h}</tr></thead><tbody>{b}</tbody></table>'


def btn(t, on=False, red=False, w=None):
    st = f' style="min-width:{w}px"' if w else ""
    return f'<span class="btn{" on" if on else ""}{" red" if red else ""}"{st}>{t}</span>'


# ---------------------------------------------------------------- иллюстрации

def mock_pads(cells, title=None):
    """Сетка 4x4 падов: cells — 16 кортежей (текст, класс) сверху вниз (13..16 первым рядом)."""
    out = '<div class="padgrid">'
    for t, c in cells:
        out += f'<div class="mpad {c}">{t}</div>'
    out += "</div>"
    if title:
        out = f'<div class="mockcap">{title}</div>' + out
    return out


def pads_order():
    return [13, 14, 15, 16, 9, 10, 11, 12, 5, 6, 7, 8, 1, 2, 3, 4]


def page(content, cls=""):
    return f'<section class="page {cls}">{content}</section>'


def fx_tables(fx, tails, head, tail_label):
    """Таблицы эффектов: fx — [(набор, подзаголовок, [(код, имя, X, Y)])]."""
    out = ""
    for bank, sub, progs in fx:
        rows = [(f'<b>{i + 1}</b>', f'<span class="code">{c}</span>',
                 f"<b>{n}</b>" + (f' <span class="tail">{tail_label}</span>' if c in tails else ""), x, y)
                for i, (c, n, x, y) in enumerate(progs)]
        out += f'<h3 class="fxh"><span class="bank">{bank}</span> {sub}</h3>'
        out += table(head, rows, "fx")
    return out


def cover_logo():
    if not image_path("panel.png").startswith(BRANDING):
        return ""
    return f'<div class="cover-logo">{crop(1676, 28, 290, 104)}</div>'


# Замены в общем тексте руководства: python3 build.py -> manual_isl.html.
# Лупер описан в отдельном дополнении (build_isl.py).
ISL_TEXT = {
    "ru": [
        ("MAIN, CLICK, GAIN (или LOOP), TRIM.", "MAIN, SEQ, LOOPER, TRIM."),
        ("<b>CLICK</b>", "<b>SEQ</b>"), ("<b>GAIN / LOOP</b>", "<b>LOOPER</b>"),
        ("громкость метронома.", "громкость секвенсора."),
        ("усиление записи (−12…+24 дБ). Если выбранный пад в режиме LOOP, фейдер становится <b>LOOP</b> — длина петли по темпу.", "громкость всех петель. Уровень входа и громкость метронома — в SETUP."),
        ("готовность к записи: потом держите пад — пока держите, идёт запись. В KAOSS — кнопка <b>RESMP</b>.", "запись на пад: нажмите и держите пад, после записи кнопка гаснет сама. <b>Удерживайте SAMPLE</b> — можно записать несколько падов подряд. В KAOSS — кнопка <b>RESMP</b>."),
        ("<b>SRC:MIC / SRC:INT</b>", "<b>SRC</b>"),
        ("откуда писать: микрофон или звук других приложений телефона (на кнопке видно SRC:MI или SRC:IN).", "откуда писать. Значок на кнопке: микрофон — MIC, кассета — звук других приложений (INT), круг со стрелкой — звук самого приложения (SELF)."),
        ("Выберите источник: <b>SRC:MIC</b> — микрофон, <b>SRC:INT</b> — звук телефона.", "Выберите источник по значку: микрофон — <b>MIC</b>, кассета — звук телефона (<b>INT</b>), круг со стрелкой — <b>SELF</b>."),
        ("SRC:MIC", "SRC"),
        ("Отпустили — звук уже на паде.", "Отпустили — звук на паде, <b>SAMPLE</b> погас."),
        ("Где кончается «тишина»", "T-HEAD и T-TAIL срабатывают сразу после записи. Где кончается «тишина»"),
        ("Громкость записи — фейдер <b>GAIN</b>. Во время записи уровень видно на экране.", "Уровень записи настраивается в <b>SETUP</b>. <b>Удерживайте SAMPLE</b>, чтобы записать несколько падов подряд."),
        ("<em>0,7 с</em>", "<em>2 с</em>"),
        ("В режиме PAD <b>удерживайте</b> пад со звуком ~1 секунду.", "В режиме PAD <b>удерживайте</b> пад со звуком 2 секунды и нажмите <b>COPY</b> в меню."),
        ("<b>LOOP</b> — звук повторяется по кругу, пока держите. Фейдер <b>LOOP</b> задаёт длину петли по темпу: FREE, 1/32 … 1/4.", "<b>LOOP</b> — звук идёт по кругу; пад включается и выключается касанием, со следующего такта."),
        ("громкость — фейдер CLICK", "громкость — в SETUP"),
        ("в PAD держать пад ~1 с", "в PAD держать пад 2 с → COPY"),
        ("удерживайте RESMP", "удерживайте RESMPL"),
        ("IS-1_20261024", "ISL-1_20261024"),
    ],
    "en": [
        ("MAIN, CLICK, GAIN (or LOOP), TRIM.", "MAIN, SEQ, LOOPER, TRIM."),
        ("<b>CLICK</b>", "<b>SEQ</b>"), ("<b>GAIN / LOOP</b>", "<b>LOOPER</b>"),
        ("metronome volume.", "sequencer volume."),
        ("recording gain (−12…+24 dB). If the selected pad is in LOOP mode, the fader becomes <b>LOOP</b>: the loop length in tempo divisions.", "volume of all loops. Input level and metronome volume are in SETUP."),
        ("arm recording, then hold a pad: it records while you hold. In KAOSS this button is <b>RESMP</b>.", "record to a pad: press it, then hold a pad; it turns off after the take. <b>Hold SAMPLE</b> to record several pads in a row. In KAOSS this button is <b>RESMP</b>."),
        ("<b>SRC:MIC / SRC:INT</b>", "<b>SRC</b>"),
        ("record from the microphone or from other apps on the phone (the button may read SRC:MI or SRC:IN).", "where to record from. The icon shows it: microphone is MIC, cassette is other apps (INT), circle with an arrow is the app itself (SELF)."),
        ("Pick the source: <b>SRC:MIC</b> is the microphone, <b>SRC:INT</b> is the phone's own sound.", "Pick the source by its icon: microphone is <b>MIC</b>, cassette is the phone's sound (<b>INT</b>), circle with an arrow is <b>SELF</b>."),
        ("SRC:MIC", "SRC"),
        ("Let go and the sound is on the pad.", "Let go: the sound is on the pad and <b>SAMPLE</b> turns off."),
        ("The <b>TRIM</b> fader decides", "T-HEAD and T-TAIL apply right after recording. The <b>TRIM</b> fader decides"),
        ("Recording level is the <b>GAIN</b> fader. You can see the level on the display while recording.", "Set the recording level in <b>SETUP</b>. <b>Hold SAMPLE</b> to record several pads in a row."),
        ("<em>0.7 s</em>", "<em>2 s</em>"),
        ("In PAD mode, <b>hold</b> a pad with a sound for about a second.", "In PAD mode, <b>hold</b> a pad with a sound for 2 seconds and tap <b>COPY</b> in the menu."),
        ("<b>LOOP</b>: the sound repeats while you hold. The <b>LOOP</b> fader sets the loop length in tempo divisions: FREE, 1/32 … 1/4.", "<b>LOOP</b>: the sound repeats; a tap switches the pad on or off on the next bar."),
        ("volume on the CLICK fader", "volume in SETUP"),
        ("in PAD hold the pad ~1 s", "in PAD hold the pad 2 s → COPY"),
        ("hold RESMP", "hold RESMPL"),
        ("IS-1_20261024", "ISL-1_20261024"),
    ],
}


def isl_manual(pages, lang):
    """Подставить в текст руководства название и особенности ISL-1."""
    out = []
    for p in pages:
        for a, b in ISL_TEXT[lang]:
            p = p.replace(a, b)
        p = p.replace("<b>RESMP</b>", "<b>RESMPL</b>").replace(">RESMP<", ">RESMPL<")
        out.append(re.sub(r"(?<![/\w-])IS-1(?![\w-])", "ISL-1", p))
    return out


def write(path, lang, title, body_cls, pages):
    if not path.startswith("looper"):
        path = path.replace(".html", "_try.html" if TRY else "_isl.html")
        title = title.replace("IS-1", NAME, 1)
        body_cls += " isl"
    if path.startswith("manual"):
        pages = isl_manual(pages, lang)
        body_cls += " man"
    css = open("style.css", encoding="utf-8").read()
    doc = (f'<!doctype html><html lang="{lang}"><head><meta charset="utf-8"><title>{title}</title>'
           f'<style>{css}</style></head><body class="{body_cls}">{"".join(pages)}</body></html>')
    open(path, "w", encoding="utf-8").write(doc)
    print(path, "pages:", len(pages))
