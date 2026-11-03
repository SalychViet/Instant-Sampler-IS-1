# Дополнение к руководству для ISL-1 (лупер): python3 build_isl.py -> manual_isl.html, manual_isl_en.html.
# PDF печатает headless Chrome. Снимок панели лежит в приватной папке branding/manual.
from parts import btn, crop, legend, page, table, write


def tip(t, label):
    return f'<div class="note tip"><b>{label}.</b> {t}</div>'


MARKS = [(1, 748, 626), (2, 748, 708), (3, 390, 834), (4, 272, 834), (5, 390, 760), (6, 1853, 640), (7, 1890, 774),
         (8, 1240, 540), (9, 748, 786), (10, 1890, 838), (11, 612, 330)]


def pages_ru():
    t = lambda s: tip(s, "Совет")
    return [
        page(f'''
<h2>ISL-1: лупер и новые функции</h2>
<p class="lead"><b>ISL-1</b> — семплер с лупером: петли записываются прямо на пады и играют вместе с секвенсором.
Основные функции описаны в руководстве ISL-1. Здесь — лупер и всё, что с ним связано.</p>
<div class="fig">{crop(0, 0, 2000, 900, MARKS, src="isl_panel.png")}</div>
{legend([
    (1, "LOOPER", "три положения по кругу: <b>LOOPER</b> (жёлтый), <b>iLOOPER</b> (голубой), выкл. Пустой пад записывает петлю."),
    (2, "CAPTURE", "превращает сыгранное при остановленном воспроизведении в паттерн."),
    (3, "SRC", "источник записи: микрофон, кассета (INT) или круг со стрелкой (SELF)."),
    (4, "SAMPL", "запись семпла на пад; после записи кнопка гаснет сама."),
    (5, "KAOSS", "экран эффектов. При входе SRC переключается на SELF; выключается только этой кнопкой."),
    (6, "Фейдеры", "<b>PreLC</b> — срез низа у записываемого звука, <b>SEQ</b> — секвенсор и one-shot, <b>LOOPER</b> — все петли, <b>TRIM</b> — порог тишины."),
    (7, "SETUP", "настройки петель, папка LIVE REC, банки."),
    (8, "Пады", "кольцо на паде показывает фазу петли, запись и ожидание такта. Нажатый пад светится своим цветом, красный — запись."),
    (9, "MUTE", "глушит выбранные пады."),
    (10, "LIVE REC", "запись всего, что слышно, в файл WAV."),
    (11, "Длина", "внизу справа на экране: <b>P</b> — такты паттерна, <b>L</b> — такты петли (во время записи L считает такты)."),
])}'''),
        page(f'''
<h2>Запись петель</h2>
<div class="flow">
  <div class="fstep"><span class="fn">1</span>{btn("LOOPER", on=True)}<p>Включите <b>LOOPER</b> и выберите источник кнопкой <b>SRC</b>.</p></div>
  <div class="farrow">→</div>
  <div class="fstep"><span class="fn">2</span><div class="mpad big rec">●</div><p>Коснитесь <b>пустого</b> пада — идёт запись. Держать палец не нужно.</p></div>
  <div class="farrow">→</div>
  <div class="fstep"><span class="fn">3</span><div class="mpad big has">▶</div><p>Коснитесь того же пада ещё раз — петля закрылась и сразу играет.</p></div>
</div>
<h4>Первая и следующие петли</h4>
{table(["Что происходит", "Как это работает"], [
    ["Первая петля", "её длина задаёт темп и сетку тактов: выбирается 1, 2, 4… такта с темпом ближе к 120 BPM"],
    ["Секвенсор уже играет", "первая петля не меняет темп, а встаёт в его такты"],
    ["Следующие петли", "начало и конец встают на ближайшие черты тактов, длина — целое число тактов"],
    ["Заиграла петля", "секвенсор запускается сам с первой доли такта"],
    ["Касание готовой петли", "включает её с первой доли следующего такта; выключает сразу, а с <b>LOOP TRANSPORT QUANTIZE</b> — в конце такта. Повторное касание до этого — отмена"],
    ["<b>STOP</b>", "останавливает всё и сбрасывает сетку тактов"],
], "plain")}
<h4>Что ещё важно</h4>
{table(["Действие", "Как сделать"], [
    ["Перезаписать петлю", "сначала удалите её: <b>ERASE</b>, затем пад"],
    ["Сыграть one-shot в режиме LOOPER", "просто коснитесь пада с семплом: он играет, а не записывается"],
    ["Сделать из петли one-shot", "выберите пад и переключите <b>LOOP</b> на <b>ONE</b>"],
    ["Выйти из лупера", "нажимайте <b>LOOPER</b>, пока не погаснет; петли на падах по-прежнему включаются касанием"],
    ["Длина паттерна", "задаётся только кнопкой <b>LEN</b>, петля её не меняет"],
    ["Самая длинная петля", "30 секунд"],
], "plain")}
{t("Новая петля всегда начинается с чистыми настройками пада: PITCH, громкость, реверс и другие параметры прошлого звука на неё не переходят.")}'''),
        page(f'''
<h2>Источники: кнопка SRC</h2>
<p class="lead">Кнопка переключает источник по кругу. Переключать можно везде, кроме момента самой записи.</p>
{table(["Значок", "Источник", "Что пишется"], [
    ["микрофон", "<b>MIC</b>", "встроенный микрофон"],
    ["кассета", "<b>INT</b>", "звук <b>других</b> приложений телефона. Свой звук ISL-1 сюда не попадает"],
    ["круг со стрелкой", "<b>SELF</b>", "всё, что звучит в ISL-1: ваншоты, секвенция, петли и эффекты"],
], "plain")}
<h4>SELF: семплировать самого себя</h4>
<p>Выберите <b>SELF</b>, включите <b>LOOPER</b> и коснитесь пустого пада. Если идёт воспроизведение, запись начнётся со следующего такта;
пока она не началась, повторное касание отменяет запись. Второе касание закрывает петлю на пройденной черте такта. Самая короткая петля — один такт.</p>
<p>После записи другие петли выключаются: они уже звучат в новой. Если в петлю попала идущая секвенция, фейдер <b>SEQ</b> сам опускается до нуля, чтобы она не звучала дважды.
Громкость вернётся, когда вы выберете другой паттерн, выключите эту петлю или тронете фейдер.</p>
<h4>iLOOPER: только новая партия</h4>
<p>Второе нажатие <b>LOOPER</b> включает <b>iLOOPER</b> (кнопка голубая). С источником SELF в новую петлю пишется только то, что вы играете падами или секвенсором,
а уже играющие петли звучат, но в запись не попадают. Эффекты <b>KAOSS</b> в iLOOPER действуют только на one-shot и секвенсор, петли идут без эффекта.
С MIC и INT iLOOPER пишет вход, как обычный лупер.</p>
<h4>KAOSS</h4>
<p>При входе в KAOSS источник переключается на SELF и обратно сам не возвращается. KAOSS выключается только своей кнопкой: смена режимов лупера его не выключает.</p>
{t("Если при записи с INT появилось сообщение о тишине, значит другие приложения ничего не играют. Для своего звука выберите SELF.")}'''),
        page(f'''
<h2>Меню пада, части и EQ</h2>
{table(["Действие", "Результат"], [
    ["Удержание one-shot, 1,5&nbsp;с", "меню: <b>COPY</b>, <b>SLICE</b>, <b>PTCH ENV</b> и EQ пада"],
    ["Удержание петли, 1,5&nbsp;с", "окно частей; петля продолжает играть. Короткое касание включает или выключает её"],
    ["<b>MENU</b> в окне частей петли", "меню петли: <b>COPY</b> и EQ"],
    ["Касание части в окне", "выключает или включает часть; всего частей 4"],
    ["Круглые ручки внизу окна", "двигают границы частей"],
    ["<b>RVS</b>", "звук задом наперёд"],
    ["<b>PTCH ENV</b>", "огибающая высоты one-shot, ±24 полутона; <b>FLAT</b> — сброс, <b>OFF</b> — выключить"],
], "plain")}
<h4>EQ пада</h4>
{table(["Ручка или кнопка", "Что делает"], [
    ["<b>HP</b>, <b>HP HZ</b>", "срез низа (12 дБ на октаву); кнопка включает, ручка — частота"],
    ["<b>LP</b>, <b>LP HZ</b>", "срез верха (12 дБ на октаву)"],
    ["<b>BELL HZ</b>, <b>BELL DB</b>", "колокол ±18 дБ между включёнными срезами, а если они выключены — по всему диапазону. На 0 дБ колокол не работает и не нагружает телефон"],
], "plain")}
<p>EQ слышен сразу, даже на играющей петле, и сохраняется в проекте и банке.</p>
<h4>Копии и новый звук</h4>
<p><b>COPY</b> делает самостоятельную копию: свои части, EQ и настройки. Правки копии не меняют оригинал, в группу CHOKE оригинала копия не входит.
Новый звук на паде (запись, SELF, файл) и стирание пада сбрасывают его настройки: громкость, PITCH, EQ, режим и CHOKE.</p>'''),
        page(f'''
<h2>CAPTURE</h2>
<p class="lead">Сыграли удачно, но запись не была включена? ISL-1 помнит, что вы играли на падах при остановленном воспроизведении.</p>
<div class="flow">
  <div class="fstep"><span class="fn">1</span><div class="mpad big has">♪</div><p>Играйте на падах. <b>CAPTURE</b> горит жёлтым: сыгранное запомнено.</p></div>
  <div class="farrow">→</div>
  <div class="fstep"><span class="fn">2</span>{btn("CAPTURE", on=True)}<p>Нажмите <b>CAPTURE</b> — последняя фраза станет паттерном длиной 1–4 такта и заиграет.</p></div>
</div>
<p>Темп определяется по сыгранному; если играют петли, темп остаётся их. Удержание <b>CAPTURE</b> 2 секунды очищает память.</p>
<h2>Запись семплов и удаление</h2>
{table(["Что", "Как в ISL-1"], [
    ["<b>SAMPL</b>", "нажмите и держите пад; когда семпл готов, кнопка гаснет. Кнопка <b>PAD</b> тоже выключает SAMPL"],
    ["Удержание <b>SAMPL</b>", "пока держите, записывайте пады один за другим; отпустили — запись выключена"],
    ["<b>T-HEAD</b>, <b>T-TAIL</b>", "если включены, срабатывают сразу после записи"],
    ["Удержание <b>ERASE</b>", "удаляются только пады, которых вы коснулись или по которым провели пальцем"],
    ["<b>SOUND ON/OFF</b> в EDIT", "OFF: правки и ручки не озвучиваются, слушать кнопкой <b>▶ PREVIEW</b>"],
], "plain")}
<h2>MUTE</h2>
{table(["Действие", "Результат"], [
    ["Нажать <b>MUTE</b>", "кнопка красная; касание пада глушит его или открывает, заглушённый пад красный"],
    ["Нажать <b>MUTE</b> ещё раз", "выключено, все пады открыты"],
    ["Удерживать <b>MUTE</b>", "касание и свайп: пад, ряд или столбец. После отпускания заглушённые молчат; нажатие открывает все"],
], "plain")}
<p>Заглушённая петля идёт беззвучно и после открытия вступает в такт. Заглушённые пады не пишутся в SELF и EXPORT и не сохраняются в проекте.</p>'''),
        page(f'''
<h2>Микс и запись игры</h2>
{table(["Что", "Как работает"], [
    ["<b>PreLC</b>", "срезает низ у звука, <b>который вы записываете</b>, до 1000 Гц (24 дБ на октаву). Внизу фейдер выключен. На MIC, INT и SELF; уже записанные звуки не меняются"],
    ["<b>SEQ</b> и <b>LOOPER</b>", "секвенсор с one-shot и петли. Если вместе они упираются в потолок громкости, тише становятся только петли — барабаны не проседают"],
    ["Дозапись паттерна", "если в паттерне уже есть такая же нота рядом с ударом (до 60 мс), живой удар не звучит, чтобы не было фазинга, но записывается и играет из паттерна"],
], "plain")}
<h2>LIVE REC</h2>
<p>Нажмите <b>LIVE REC</b> — экран на мгновение вспыхнет белым, и ISL-1 начнёт писать всё, что вы слышите: пады, петли, секвенсор и KAOSS. Длина не ограничена.
Нажмите ещё раз — файл <b>LIVE_дата_время.wav</b> (24 бит) появится в папке. По умолчанию это «Музыка/ISL-1 LIVE», другую папку можно выбрать в <b>SETUP</b>.</p>
{t("Белая вспышка — метка начала: по ней запись звука можно точно совместить с видео экрана.")}
<h2>SETUP: петли и запись</h2>
{table(["Настройка", "Что делает"], [
    ["<b>LIVE REC — папка</b>", "куда сохранять записи LIVE REC; <b>ПО УМОЛЧАНИЮ</b> возвращает «Музыка/ISL-1 LIVE»"],
    ["<b>LOOP OFFSET</b>", "сдвиг петель: запаздывают — вправо (+), спешат — влево (−). Задержка телефона учитывается сама"],
    ["<b>LOOP QUANTIZE</b>", "сдвигает удары новой петли к сетке; сила 0–100 % и сетка 1/4–1/32"],
    ["<b>LOOP TRANSPORT QUANTIZE</b>", "выключенная касанием петля доигрывает до конца такта"],
    ["<b>CLICK</b>, <b>уровень входа</b>", "громкость метронома; усиление записи отдельно для MIC и INT"],
    ["<b>IMPORT / SAVE BANK</b>", "банк из 16 падов в файл <b>.isbank</b> и обратно, вместе с петлями, частями и EQ"],
], "plain")}'''),
    ]


def pages_en():
    t = lambda s: tip(s, "Tip")
    return [
        page(f'''
<h2>ISL-1: the looper and new features</h2>
<p class="lead"><b>ISL-1</b> is a sampler with a looper: loops are recorded straight onto pads and play along with the sequencer.
The main features are in the ISL-1 manual. This guide covers the looper and everything around it.</p>
<div class="fig">{crop(0, 0, 2000, 900, MARKS, src="isl_panel.png")}</div>
{legend([
    (1, "LOOPER", "three states in turn: <b>LOOPER</b> (yellow), <b>iLOOPER</b> (blue), off. An empty pad records a loop."),
    (2, "CAPTURE", "turns what you played while stopped into a pattern."),
    (3, "SRC", "recording source: microphone, cassette (INT) or circle with an arrow (SELF)."),
    (4, "SAMPL", "records a sample to a pad and turns off after the take."),
    (5, "KAOSS", "the effects screen. Entering it switches SRC to SELF; only this button turns it off."),
    (6, "Faders", "<b>PreLC</b> cuts the lows of the sound being recorded, <b>SEQ</b> the sequencer and one-shots, <b>LOOPER</b> all loops, <b>TRIM</b> the silence threshold."),
    (7, "SETUP", "loop settings, the LIVE REC folder, banks."),
    (8, "Pads", "the ring shows loop phase, recording and waiting for the bar. A pressed pad lights in its own colour; red means recording."),
    (9, "MUTE", "silences chosen pads."),
    (10, "LIVE REC", "records everything you hear to a WAV file."),
    (11, "Length", "bottom right of the display: <b>P</b> is pattern bars, <b>L</b> loop bars (L counts up while recording)."),
])}'''),
        page(f'''
<h2>Recording loops</h2>
<div class="flow">
  <div class="fstep"><span class="fn">1</span>{btn("LOOPER", on=True)}<p>Turn on <b>LOOPER</b> and pick a source with <b>SRC</b>.</p></div>
  <div class="farrow">→</div>
  <div class="fstep"><span class="fn">2</span><div class="mpad big rec">●</div><p>Tap an <b>empty</b> pad to start recording. No need to hold it.</p></div>
  <div class="farrow">→</div>
  <div class="fstep"><span class="fn">3</span><div class="mpad big has">▶</div><p>Tap the same pad again: the loop closes and plays right away.</p></div>
</div>
<h4>First and later loops</h4>
{table(["What happens", "How it works"], [
    ["First loop", "its length sets the tempo and bar grid: 1, 2, 4… bars, whichever gives a tempo closest to 120 BPM"],
    ["Sequencer already playing", "the first loop keeps the tempo and fits its bars"],
    ["Later loops", "start and end snap to the nearest bar lines; the length is a whole number of bars"],
    ["A loop starts", "the sequencer starts by itself on the first beat of a bar"],
    ["Tapping a finished loop", "starts it on the next bar; stops it at once, or at the end of the bar with <b>LOOP TRANSPORT QUANTIZE</b>. Tapping again before that cancels"],
    ["<b>STOP</b>", "stops everything and resets the bar grid"],
], "plain")}
<h4>Also worth knowing</h4>
{table(["Action", "How"], [
    ["Re-record a loop", "delete it first: <b>ERASE</b>, then the pad"],
    ["Play a one-shot in LOOPER mode", "just tap a pad with a sample: it plays and does not record"],
    ["Turn a loop into a one-shot", "select the pad and switch <b>LOOP</b> to <b>ONE</b>"],
    ["Leave the looper", "press <b>LOOPER</b> until it goes dark; loop pads still switch on and off by tapping"],
    ["Pattern length", "set only with <b>LEN</b>; a loop does not change it"],
    ["Longest loop", "30 seconds"],
], "plain")}
{t("A new loop always starts with clean pad settings: PITCH, volume, reverse and the rest of the old sound's settings are not carried over.")}'''),
        page(f'''
<h2>Sources: the SRC button</h2>
<p class="lead">The button cycles through the sources. You can switch anywhere except during a recording.</p>
{table(["Icon", "Source", "What is recorded"], [
    ["microphone", "<b>MIC</b>", "the built-in microphone"],
    ["cassette", "<b>INT</b>", "the sound of <b>other</b> apps on the phone. ISL-1's own sound is not included"],
    ["circle with arrow", "<b>SELF</b>", "everything ISL-1 plays: one-shots, the sequence, loops and effects"],
], "plain")}
<h4>SELF: sampling itself</h4>
<p>Choose <b>SELF</b>, turn on <b>LOOPER</b> and tap an empty pad. During playback the recording starts on the next bar;
until then another tap cancels it. The next tap closes the loop on the bar line just passed. The shortest loop is one bar.</p>
<p>After the take the other loops switch off, because they are already in the new one. If the running sequence went into the loop, the <b>SEQ</b> fader drops to zero by itself so it is not heard twice.
It comes back when you pick another pattern, stop that loop or touch the fader.</p>
<h4>iLOOPER: only the new part</h4>
<p>A second press of <b>LOOPER</b> turns on <b>iLOOPER</b> (the button is blue). With SELF, the new loop records only what you play on the pads or the sequencer;
loops already playing are heard but not recorded. In iLOOPER, <b>KAOSS</b> effects apply only to one-shots and the sequencer; loops stay dry.
With MIC and INT, iLOOPER records the input like the normal looper.</p>
<h4>KAOSS</h4>
<p>Entering KAOSS switches the source to SELF, and it does not switch back by itself. Only the KAOSS button turns KAOSS off: changing looper modes does not.</p>
{t("If a recording from INT reports silence, no other app is playing. To record ISL-1's own sound, choose SELF.")}'''),
        page(f'''
<h2>Pad menu, parts and EQ</h2>
{table(["Action", "Result"], [
    ["Hold a one-shot, 1.5 s", "menu: <b>COPY</b>, <b>SLICE</b>, <b>PTCH ENV</b> and the pad EQ"],
    ["Hold a loop, 1.5 s", "the parts window; the loop keeps playing. A short tap switches it on or off"],
    ["<b>MENU</b> in a loop's parts window", "the loop menu: <b>COPY</b> and EQ"],
    ["Tap a part in the window", "mutes or unmutes it; there are 4 parts"],
    ["Round handles at the bottom", "move the part boundaries"],
    ["<b>RVS</b>", "plays the sound backwards"],
    ["<b>PTCH ENV</b>", "pitch envelope for one-shots, ±24 semitones; <b>FLAT</b> resets, <b>OFF</b> disables"],
], "plain")}
<h4>Pad EQ</h4>
{table(["Knob or button", "What it does"], [
    ["<b>HP</b>, <b>HP HZ</b>", "low cut (12 dB per octave); the button turns it on, the knob sets the frequency"],
    ["<b>LP</b>, <b>LP HZ</b>", "high cut (12 dB per octave)"],
    ["<b>BELL HZ</b>, <b>BELL DB</b>", "a ±18 dB bell between the enabled cuts, or over the whole range when they are off. At 0 dB the bell is off and costs no CPU"],
], "plain")}
<p>EQ is heard at once, even on a playing loop, and is saved in projects and banks.</p>
<h4>Copies and new sounds</h4>
<p><b>COPY</b> makes an independent copy with its own parts, EQ and settings. Editing the copy does not change the original, and the copy is not in the original's CHOKE group.
A new sound on a pad (recording, SELF, a file) and erasing a pad reset its settings: volume, PITCH, EQ, mode and CHOKE.</p>'''),
        page(f'''
<h2>CAPTURE</h2>
<p class="lead">Played something good without recording? ISL-1 remembers what you played on the pads while playback was stopped.</p>
<div class="flow">
  <div class="fstep"><span class="fn">1</span><div class="mpad big has">♪</div><p>Play the pads. <b>CAPTURE</b> lights yellow when it has something.</p></div>
  <div class="farrow">→</div>
  <div class="fstep"><span class="fn">2</span>{btn("CAPTURE", on=True)}<p>Press <b>CAPTURE</b>: the last phrase becomes a pattern of 1–4 bars and starts playing.</p></div>
</div>
<p>The tempo is detected from your playing; if loops are playing, their tempo stays. Hold <b>CAPTURE</b> for 2 seconds to clear the memory.</p>
<h2>Sampling and erasing</h2>
{table(["What", "In ISL-1"], [
    ["<b>SAMPL</b>", "press it and hold a pad; it turns off when the sample is ready. The <b>PAD</b> key turns SAMPL off too"],
    ["Hold <b>SAMPL</b>", "record pads one after another while holding; release to stop"],
    ["<b>T-HEAD</b>, <b>T-TAIL</b>", "when on, they apply right after recording"],
    ["Hold <b>ERASE</b>", "only the pads you touch or swipe over are deleted"],
    ["<b>SOUND ON/OFF</b> in EDIT", "OFF: edits and knobs make no sound; listen with <b>▶ PREVIEW</b>"],
], "plain")}
<h2>MUTE</h2>
{table(["Action", "Result"], [
    ["Press <b>MUTE</b>", "it turns red; touching a pad mutes or unmutes it, a muted pad is red"],
    ["Press <b>MUTE</b> again", "off, all pads open"],
    ["Hold <b>MUTE</b>", "touch and swipe: a pad, row or column. After release muted pads stay silent; a press opens them all"],
], "plain")}
<p>A muted loop keeps running silently and comes back in time. Muted pads are not recorded by SELF or EXPORT, and muting is not saved in the project.</p>'''),
        page(f'''
<h2>Mix and recording your play</h2>
{table(["What", "How it works"], [
    ["<b>PreLC</b>", "cuts the lows of the sound <b>you are recording</b>, up to 1000 Hz (24 dB per octave). At the bottom it is off. Works for MIC, INT and SELF; existing sounds are not changed"],
    ["<b>SEQ</b> and <b>LOOPER</b>", "the sequencer with one-shots, and the loops. If together they hit the volume ceiling, only the loops get quieter, so the drums do not dip"],
    ["Overdubbing a pattern", "if the pattern already has the same note near your hit (within 60 ms), the live hit is silent to avoid phasing, but it is recorded and plays from the pattern"],
], "plain")}
<h2>LIVE REC</h2>
<p>Press <b>LIVE REC</b>: the screen flashes white for a moment and ISL-1 records everything you hear — pads, loops, sequencer and KAOSS. There is no length limit.
Press it again and <b>LIVE_date_time.wav</b> (24-bit) appears in the folder. By default it is “Music/ISL-1 LIVE”; choose another folder in <b>SETUP</b>.</p>
{t("The white flash marks the start, so the audio can be lined up exactly with a screen video.")}
<h2>SETUP: loops and recording</h2>
{table(["Setting", "What it does"], [
    ["<b>LIVE REC folder</b>", "where LIVE REC files go: <b>ВЫБРАТЬ ПАПКУ</b> (choose folder); <b>ПО УМОЛЧАНИЮ</b> (default) returns to “Music/ISL-1 LIVE”"],
    ["<b>LOOP OFFSET</b>", "shifts loops: move right (+) if they lag, left (−) if they rush. Phone latency is handled automatically"],
    ["<b>LOOP QUANTIZE</b>", "moves the hits of a new loop towards the grid; strength 0–100 %, grid 1/4–1/32"],
    ["<b>LOOP TRANSPORT QUANTIZE</b>", "a loop you switch off plays to the end of the bar"],
    ["<b>CLICK</b>, <b>input level</b>", "metronome volume; recording gain, separate for MIC and INT"],
    ["<b>IMPORT / SAVE BANK</b>", "a bank of 16 pads to an <b>.isbank</b> file and back, with loops, parts and EQ"],
], "plain")}'''),
    ]


write("looper.html", "ru", "ISL-1 — лупер", "ru isl", pages_ru())
write("looper_en.html", "en", "ISL-1 — looper guide", "en isl", pages_en())
