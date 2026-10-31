# Дополнение к руководству для ISL-1 (лупер): python3 build_isl.py -> manual_isl.html, manual_isl_en.html.
# PDF печатает headless Chrome. Снимок панели лежит в приватной папке branding/manual.
from parts import btn, crop, legend, page, table, write


def tip(t, label):
    return f'<div class="note tip"><b>{label}.</b> {t}</div>'


MARKS = [(1, 748, 665), (2, 748, 747), (3, 390, 834), (4, 272, 834), (5, 390, 760), (6, 1853, 640), (7, 1890, 774), (8, 1240, 540)]


def pages_ru():
    t = lambda s: tip(s, "Совет")
    return [
        page(f'''
<h2>ISL-1: дополнение к руководству IS-1</h2>
<p class="lead"><b>ISL-1</b> — полная версия IS-1 плюс лупер: петли записываются прямо на пады и играют вместе с секвенсором.
Всё остальное работает как в руководстве IS-1. Здесь — только то, что отличается.</p>
<div class="fig">{crop(0, 0, 2000, 900, MARKS, src="isl_panel.png")}</div>
{legend([
    (1, "LOOPER", "включает режим лупера: пустой пад записывает петлю."),
    (2, "CAPTURE", "превращает сыгранное при остановленном воспроизведении в паттерн."),
    (3, "SRC", "источник записи: микрофон, кассета (INT) или круг со стрелкой (SELF)."),
    (4, "SAMPL", "запись семпла на пад; после записи кнопка гаснет сама (последняя страница)."),
    (5, "KAOSS", "экран эффектов. При входе SRC переключается с MIC на INT."),
    (6, "Фейдеры", "MAIN — общая громкость, SEQ — секвенсор, LOOPER — все петли, TRIM — порог тишины. Уровень входа — в SETUP."),
    (7, "SETUP", "настройки петель (последняя страница)."),
    (8, "Пады", "кольцо на паде показывает фазу петли, запись и ожидание такта."),
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
    ["Касание готовой петли", "включает или выключает её с первой доли следующего такта; повторное касание до старта — отмена"],
    ["<b>STOP</b>", "останавливает всё и сбрасывает сетку тактов"],
], "plain")}
<h4>Что ещё важно</h4>
{table(["Действие", "Как сделать"], [
    ["Перезаписать петлю", "сначала удалите её: <b>ERASE</b>, затем пад"],
    ["Сыграть one-shot в режиме LOOPER", "просто коснитесь пада с семплом: он играет, а не записывается"],
    ["Сделать из петли one-shot", "выберите пад и переключите <b>LOOP</b> на <b>ONE</b>"],
    ["Выйти из лупера", "нажмите <b>LOOPER</b>; петли на падах по-прежнему включаются касанием"],
    ["Длина паттерна", "задаётся только кнопкой <b>LEN</b>, петля её не меняет"],
    ["Самая длинная петля", "30 секунд"],
], "plain")}'''),
        page(f'''
<h2>Источники: кнопка SRC</h2>
<p class="lead">Кнопка переключает источник по кругу. Переключать можно везде, кроме момента самой записи.</p>
{table(["Значок", "Источник", "Что пишется"], [
    ["микрофон", "<b>MIC</b>", "встроенный микрофон"],
    ["кассета", "<b>INT</b>", "звук <b>других</b> приложений телефона. Свой звук ISL-1 сюда не попадает"],
    ["круг со стрелкой", "<b>SELF</b>", "всё, что звучит в ISL-1: ваншоты, секвенция, петли и эффекты"],
], "plain")}
<h4>SELF: семплировать самого себя</h4>
<p>Луп из идущей секвенции или из того, что вы играете ваншотами: выберите <b>SELF</b>, включите <b>LOOPER</b> и коснитесь пустого пада.
Если идёт воспроизведение, запись начнётся со следующего такта. Пока она не началась, кольцо показывает ожидание, а повторное касание отменяет запись.
Второе касание закрывает петлю сразу на пройденной черте такта. Следующую черту запись ждёт, только если до неё меньше четверти такта. Самая короткая петля — один такт.</p>
<p>После такой записи другие петли выключаются: они уже звучат в новой. Включить их можно снова касанием.</p>
<p>С <b>SAMPL</b> источник SELF тоже работает: держите пад — на него пишется звук приложения, а другие пады в это время играют.</p>
<h4>KAOSS</h4>
<p>При входе в KAOSS источник переключается с MIC на INT и обратно сам не возвращается. Выбранный SELF не меняется. Кнопка <b>RESMPL</b> в KAOSS при включённом лупере включает и выключает SELF.</p>
{t("Если при записи с INT появилось сообщение о тишине, значит другие приложения ничего не играют. Для своего звука выберите SELF.")}
<h2>Части звука и меню пада</h2>
{table(["Действие", "Результат"], [
    ["Удержание пада-петли в LOOPER, 0,5 с", "окно частей"],
    ["Удержание пада в PAD, 2 с", "меню: <b>COPY</b>, <b>SLICE</b>, <b>PTCH ENV</b> (у one-shot), <b>CLOSE</b>"],
    ["Касание части в окне", "выключает или включает часть; всего частей 4"],
    ["Круглые ручки внизу", "двигают границы частей, по умолчанию части равные"],
    ["<b>RVS</b>", "звук задом наперёд"],
    ["<b>PTCH ENV</b>", "огибающая высоты one-shot, ±24 полутона; <b>FLAT</b> — сброс, <b>OFF</b> — выключить"],
], "plain")}'''),
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
    ["Удержание <b>ERASE</b>", "касание сразу удаляет пад, свайп — целый ряд или столбец"],
], "plain")}
<h2>SETUP: настройки петель</h2>
{table(["Настройка", "Что делает"], [
    ["<b>LOOP OFFSET</b>", "сдвиг петель: запаздывают — вправо (+), спешат — влево (−). Задержка телефона учитывается сама"],
    ["<b>LOOP QUANTIZE</b>", "сдвигает удары новой петли к сетке. На записанные петли не влияет"],
    ["<b>Сила квантизации</b>", "0–100 %, на какую часть расстояния до сетки сдвинуть удар. По умолчанию 60 %"],
    ["<b>Сетка</b>", "1/4, 1/8, 1/16 или 1/32. По умолчанию 1/16"],
    ["<b>CLICK</b>", "громкость метронома"],
    ["<b>Уровень входа</b>", "усиление записи, отдельно для MIC и INT"],
], "plain")}
{t("Метроном не попадает в запись SELF и в экспорт. Если вход лупера не открылся, выключите и снова включите LOOPER.")}'''),
    ]


def pages_en():
    t = lambda s: tip(s, "Tip")
    return [
        page(f'''
<h2>ISL-1: addendum to the IS-1 manual</h2>
<p class="lead"><b>ISL-1</b> is the full IS-1 plus a looper: loops are recorded straight onto pads and play along with the sequencer.
Everything else works as in the IS-1 manual. This addendum covers only what is different.</p>
<div class="fig">{crop(0, 0, 2000, 900, MARKS, src="isl_panel.png")}</div>
{legend([
    (1, "LOOPER", "turns the looper on: an empty pad records a loop."),
    (2, "CAPTURE", "turns what you played while stopped into a pattern."),
    (3, "SRC", "recording source: microphone, cassette (INT) or circle with an arrow (SELF)."),
    (4, "SAMPL", "records a sample to a pad and turns off after the take (last page)."),
    (5, "KAOSS", "the effects screen. Entering it switches SRC from MIC to INT."),
    (6, "Faders", "MAIN is the master, SEQ the sequencer, LOOPER all loops, TRIM the silence threshold. Input level is in SETUP."),
    (7, "SETUP", "loop settings (last page)."),
    (8, "Pads", "the ring on a pad shows loop phase, recording and waiting for the bar."),
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
    ["Tapping a finished loop", "switches it on or off on the next bar; tapping again before that cancels"],
    ["<b>STOP</b>", "stops everything and resets the bar grid"],
], "plain")}
<h4>Also worth knowing</h4>
{table(["Action", "How"], [
    ["Re-record a loop", "delete it first: <b>ERASE</b>, then the pad"],
    ["Play a one-shot in LOOPER mode", "just tap a pad with a sample: it plays and does not record"],
    ["Turn a loop into a one-shot", "select the pad and switch <b>LOOP</b> to <b>ONE</b>"],
    ["Leave the looper", "press <b>LOOPER</b>; loop pads still switch on and off by tapping"],
    ["Pattern length", "set only with <b>LEN</b>; a loop does not change it"],
    ["Longest loop", "30 seconds"],
], "plain")}'''),
        page(f'''
<h2>Sources: the SRC button</h2>
<p class="lead">The button cycles through the sources. You can switch anywhere except during a recording.</p>
{table(["Icon", "Source", "What is recorded"], [
    ["microphone", "<b>MIC</b>", "the built-in microphone"],
    ["cassette", "<b>INT</b>", "the sound of <b>other</b> apps on the phone. ISL-1's own sound is not included"],
    ["circle with arrow", "<b>SELF</b>", "everything ISL-1 plays: one-shots, the sequence, loops and effects"],
], "plain")}
<h4>SELF: sampling itself</h4>
<p>To make a loop from a running sequence or from one-shots you play, choose <b>SELF</b>, turn on <b>LOOPER</b> and tap an empty pad.
During playback the recording starts on the next bar. Until then the ring shows it is waiting, and another tap cancels.
The next tap closes the loop at once on the bar line just passed. It waits for the next bar line only if that is less than a quarter of a bar away. The shortest loop is one bar.</p>
<p>After such a recording the other loops switch off, because they are already in the new one. Tap them to switch them back on.</p>
<p><b>SAMPL</b> works with SELF too: hold a pad and the app's sound records onto it while other pads keep playing.</p>
<h4>KAOSS</h4>
<p>Entering KAOSS switches the source from MIC to INT, and it does not switch back by itself. A selected SELF stays. With the looper on, <b>RESMPL</b> in KAOSS turns SELF on and off.</p>
{t("If a recording from INT reports silence, no other app is playing. To record ISL-1's own sound, choose SELF.")}
<h2>Sound parts and the pad menu</h2>
{table(["Action", "Result"], [
    ["Hold a loop pad in LOOPER, 0.5 s", "the parts window"],
    ["Hold a pad in PAD, 2 s", "menu: <b>COPY</b>, <b>SLICE</b>, <b>PTCH ENV</b> (one-shots), <b>CLOSE</b>"],
    ["Tap a part in the window", "mutes or unmutes it; there are 4 parts"],
    ["Round handles at the bottom", "move the part boundaries; parts start out equal"],
    ["<b>RVS</b>", "plays the sound backwards"],
    ["<b>PTCH ENV</b>", "pitch envelope for one-shots, ±24 semitones; <b>FLAT</b> resets, <b>OFF</b> disables"],
], "plain")}'''),
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
    ["Hold <b>ERASE</b>", "a touch deletes a pad at once, a swipe clears a row or column"],
], "plain")}
<h2>SETUP: loop settings</h2>
{table(["Setting", "What it does"], [
    ["<b>LOOP OFFSET</b>", "shifts loops: move right (+) if they lag, left (−) if they rush. Phone latency is handled automatically"],
    ["<b>LOOP QUANTIZE</b>", "moves the hits of a new loop towards the grid. Existing loops are not changed"],
    ["<b>Quantize strength</b>", "0–100 %, how far a hit moves towards the grid. Default 60 %"],
    ["<b>Grid</b>", "1/4, 1/8, 1/16 or 1/32. Default 1/16"],
    ["<b>CLICK</b>", "metronome volume"],
    ["<b>Input level</b>", "recording gain, separate for MIC and INT"],
], "plain")}
{t("The metronome is not recorded in SELF takes or exports. If the looper input fails to open, turn LOOPER off and on again.")}'''),
    ]


write("manual_isl.html", "ru", "ISL-1 — дополнение к руководству", "ru isl", pages_ru())
write("manual_isl_en.html", "en", "ISL-1 — manual addendum", "en isl", pages_en())
