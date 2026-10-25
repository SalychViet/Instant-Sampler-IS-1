# Общие куски руководства IS-1 (вёрстка, скриншоты с выносками) для русской и английской версий.
# Координаты выносок — в пикселях скриншота, приведённого к 2000x900.
import html

W, H = 2000, 900


def pct(v, total):
    return f"{v / total * 100:.3f}%"


def crop(x, y, w, h, marks=(), cls="", src="panel.png", overlays=()):
    """Кусок скриншота (x, y, w, h в координатах 2000x900) с номерами-выносками.

    overlays — надписи поверх скриншота (когда текст в приложении поменялся после съёмки):
    (ox, oy, ow, oh, текст, размер шрифта в единицах 2000x900, CSS-стиль).
    """
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


def write(path, lang, title, body_cls, pages):
    css = open("style.css", encoding="utf-8").read()
    doc = (f'<!doctype html><html lang="{lang}"><head><meta charset="utf-8"><title>{title}</title>'
           f'<style>{css}</style></head><body class="{body_cls}">{"".join(pages)}</body></html>')
    open(path, "w", encoding="utf-8").write(doc)
    print(path, "pages:", len(pages))
