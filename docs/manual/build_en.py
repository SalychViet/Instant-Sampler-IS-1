# English IS-1 manual: python3 build_en.py -> manual_en.html; the PDF is printed with headless Chrome.
from parts import btn, crop, fx_tables, legend, mock_pads, page, pads_order, table, write


def tip(t, kind="tip"):
    label = {"tip": "Tip", "warn": "Important", "hold": "Hold"}[kind]
    return f'<div class="note {kind}"><b>{label}.</b> {t}</div>'


# ---------------------------------------------------------------- effects

FX = [
    ("1 · FILTER", "Filters (F01–F10)", [
        ("F01", "MORPH SWEEP", "Frequency; the shape morphs LPF → band → HPF", "Resonance"),
        ("F02", "LOWPASS", "Cutoff", "Resonance"),
        ("F03", "HIGHPASS", "Cutoff", "Resonance"),
        ("F04", "BANDPASS", "Band frequency", "Dry/filter balance"),
        ("F05", "STEEP LOWPASS", "Cutoff (very steep)", "Resonance"),
        ("F06", "DARK ECHO", "Delay time (tempo-synced)", "LPF cutoff and resonance"),
        ("F07", "THIN ECHO", "Delay time (tempo-synced)", "HPF cutoff and resonance"),
        ("F08", "BAND ECHO", "Delay time (tempo-synced)", "Overdriven band"),
        ("F09", "DARK HALL", "Reverb depth", "LPF cutoff and resonance"),
        ("F10", "AIR HALL", "Reverb depth", "HPF cutoff and resonance"),
    ]),
    ("2 · SPACE", "Delays, reverbs and special programs", [
        ("S01", "SYNC DELAY", "Time (tempo-synced)", "Depth: repeats and level"),
        ("S02", "ECHO DROP", "Time (tempo-synced)", "Repeats. While you hold, the dry sound is cut and only the echo plays"),
        ("S03", "TAPE DELAY", "Time; changing it bends the pitch like tape", "Depth"),
        ("S04", "DUB SPACE", "Time (tempo-synced), long repeats", "Tone of the repeats"),
        ("S05", "PING PONG", "Time (tempo-synced), the echo bounces L↔R", "Depth"),
        ("S06", "PUMP VERB", "Reverb length", "Pumping depth on every beat"),
        ("S07", "ICE VERB", "Tone", "Balance. While you hold, the tail freezes"),
        ("S08", "GATED VERB", "Gate time", "Reverb depth"),
        ("S09", "JET SWOOSH", "Delay (manual jet flanger)", "Feedback"),
        ("S10", "GRAIN SPRAY", "Grain speed (tempo-synced)", "Grain length"),
    ]),
    ("3 · REPEAT", "Loopers (R01–R10). X is the loop length: 4 beats on the left, 1/16 on the right", [
        ("R01", "FLIP LOOP", "Loop length", "Bottom: reversed, top: forward"),
        ("R02", "BRAKE LOOP", "Loop length", "Brake time: every pass slows down"),
        ("R03", "MIRROR LOOP", "Loop length", "HPF; the loop's mirror image is mixed in"),
        ("R04", "HOLD LOOP", "Loop length", "Live sound / loop balance"),
        ("R05", "OCTAVE LOOP", "Loop length", "Balance; the loop plays an octave up"),
        ("R06", "SHUTTLE LOOP", "Loop length", "LPF; the loop runs back and forth"),
        ("R07", "SLICE PICK", "Slice length", "Which slice of the last bar plays"),
        ("R08", "SCRATCH LOOP", "Loop length", "Scratch: move your finger up and down"),
        ("R09", "WARM LOOP", "Loop length", "LPF (open at the top)"),
        ("R10", "VINYL LOOP", "Loop length", "Speed: bottom reverse, centre stop, top ×2"),
    ]),
    ("4 · MOD", "Noise, record stops, modulation and compression", [
        ("M01", "NOISE RISER", "Noise frequency, rising to the right", "Tail: how much noise goes into delay + reverb"),
        ("M02", "RECORD STOP", "Record brake time", "Scratch with finger movement"),
        ("M03", "STOP &amp; VERB", "Brake time (tempo-synced)", "Scratch; plus reverb with a tail"),
        ("M04", "METAL RING", "Modulation frequency: wobble → metal → ring", "HPF cutoff and resonance"),
        ("M05", "HAND PHASER", "Phaser frequency", "Resonance"),
        ("M06", "TALK BOX", "First formant (the vowel)", "Second formant"),
        ("M07", "ROBO TALK", "First formant", "Second formant; digital sound"),
        ("M08", "SIDE PUMP", "Sensitivity", "Pumping depth from the hits"),
        ("M09", "SQUASH", "Sensitivity (amount of squash)", "Attack: at the top the hit keeps its click"),
        ("M10", "PITCH ECHO", "Pitch −12…+12 semitones", "Delay depth"),
    ]),
    ("5 · LFO", "Moving filters, combo effects and grit", [
        ("L01", "DIRTY ECHO", "Delay time (tempo-synced)", "Band cutoff and resonance"),
        ("L02", "WOBBLE LP", "Sweep speed (tempo-synced)", "Resonance"),
        ("L03", "CRUSH STUTTER", "Repeat length", "Bit-crush grit and ping-pong depth"),
        ("L04", "WOBBLE BAND", "Sweep speed", "Resonance"),
        ("L05", "SWEEP ECHO", "Delay time", "HPF level and delay depth"),
        ("L06", "ENDLESS RISE", "Speed of the endless rise", "Resonance"),
        ("L07", "RANDOM STEP", "Speed of the random jumps", "Depth"),
        ("L08", "RHYTHM GATE", "One of 8 gate patterns in 1/16 steps", "LPF cutoff and resonance"),
        ("L09", "BIT CRUSH", "Sample-rate reduction", "Fewer bits, dirtier sound"),
        ("L10", "DRIVE", "Drive amount (level is kept)", "Tone"),
    ]),
]

TAILS = {"F06", "F07", "F08", "F09", "F10", "S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "M01", "M03", "M10", "L01", "L03", "L05"}


def fx_pages():
    return fx_tables(FX, TAILS, ["#", "Code", "Program", "X axis (left → right)", "Y axis (bottom → top)"], "tail")


# ---------------------------------------------------------------- pages

pages = []
STARTS = {'1': 3, '2': 4, '3': 5, '4': 6, '5': 7, '6': 8, '7': 9, '8': 10, '9': 11, '10': 12, '11': 13, '12': 14, '13': 15, '14': 17, '15': 20, '16': 21, '17': 22, '18': 23}

# Cover
pages.append(page(f'''
<div class="cover">
  <div class="cover-logo">{crop(1638, 40, 300, 115)}</div>
  <h1>IS-1</h1>
  <div class="cover-sub">Sampler · sequencer · KAOSS effects</div>
  <div class="cover-img">{crop(0, 0, 2000, 900)}</div>
  <div class="cover-foot">User Manual · version of 24 October 2026</div>
</div>''', "coverpage"))

# Contents
toc = [
    ("1", "Meet the panel"), ("2", "The display"), ("3", "Modes: keys 1–5"), ("4", "Sound knobs"),
    ("5", "Control buttons"), ("6", "Banks, faders and the right column"), ("7", "Recording samples"),
    ("8", "Copy, paste, delete"), ("9", "How a pad plays: ONE / GATE / LOOP and CHOKE"),
    ("10", "Sequencer: patterns (SEQ)"), ("11", "Step input (STEP)"), ("12", "Song (SONG) and UNDO"),
    ("13", "KAOSS: effects on the whole mix"), ("14", "All 50 effects"), ("15", "The EDIT window"),
    ("16", "EXPORT: save your track"), ("17", "SETUP: Ableton Link and latency"), ("18", "Cheat sheet"),
]
pages.append(page('<h2>Contents</h2><ol class="toc">' + "".join(
    f'<li><span class="tn">{n}</span>{t}<span class="tp">{STARTS[n]}</span></li>' for n, t in toc) + '</ol>' + tip(
    "Button labels in the app are shown in <b>bold</b> in this manual, for example <b>PLAY</b> or <b>ERASE</b>.") + tip(
    "Your project (pads, patterns, song, effects) is saved automatically when you minimise or close the app.", "warn")))

# 1. Panel
pages.append(page(f'''
<h2><span class="sn">1</span>Meet the panel</h2>
<p class="lead">IS-1 is laid out like a hardware sampler: the display on the left with knobs and buttons below it, the banks and 16 pads
in the middle, and the faders on the right. Hold your phone in landscape.</p>
<div class="fig">{crop(0, 0, 2000, 900, [(1, 420, 230), (2, 86, 482), (3, 86, 600), (4, 86, 760), (5, 742, 292),
                                            (6, 742, 800), (7, 1120, 455), (8, 1632, 430), (9, 1632, 805)])}</div>
{legend([
    (1, "Display", "what is selected, tempo, the waveform, parameters and hints. In KAOSS mode the effects touch pad appears here."),
    (2, "Keys 1–5", "switch modes: PAD, SEQ, STEP, EDIT, IMPORT. In KAOSS they pick the effect sets."),
    (3, "Sound knobs", "volume, pan, pitch, start/end, attack and release of the selected pad."),
    (4, "Control buttons", "three rows: transport, sequencer settings, sample recording."),
    (5, "DRY/WET", "how much of the KAOSS effect you hear (each effect remembers its own value)."),
    (6, "Banks A–D", "four banks of 16 pads = 64 sounds. In STEP they are bar pages, in KAOSS they are MOTION memories."),
    (7, "Pads", "play sounds, pick patterns in SEQ, set steps in STEP."),
    (8, "Faders", "MAIN, CLICK, GAIN (or LOOP), TRIM."),
    (9, "Right column", "CHOKE, EXPORT, FULL, SETUP."),
])}'''))

# 2. Display
pages.append(page(f'''
<h2><span class="sn">2</span>The display</h2>
<div class="fig narrow">{crop(108, 52, 570, 400, [(1, 360, 85), (2, 530, 85), (3, 600, 230), (4, 495, 378), (5, 520, 418)])}</div>
{legend([
    (1, "Selection line", "in PAD mode: pad and bank (TRACK 01 A); in SEQ: pattern, position and bar."),
    (2, "Tempo", "the current BPM. <b>Hold</b> the tempo digits to open a keypad for exact entry (30–300, in 0.1 steps)."),
    (3, "Waveform", "the sound of the selected pad. The parts before START and after END are dimmed."),
    (4, "Pad parameters", "V volume, C/L/R pan, P pitch, A and R attack/release, ONE/GATE/LOOP play mode, CHK choke group."),
    (5, "Status line", "short hints after every action, plus flags: T:H+T means T-HEAD/T-TAIL are on, LNK means Ableton Link, FXA means an effects recording exists."),
])}
<div class="row2">
  <div>
    <h4>Typing in the tempo</h4>
    <div class="keypad">
      <div class="kp-lcd"><small>TEMPO BPM</small><b>124.5</b></div>
      <div class="kgrid">{"".join(btn(k) for k in ["7","8","9","4","5","6","1","2","3",".","0","⌫"])}</div>
      <div class="ctrlrow">{btn("CANCEL")}{btn("OK", on=True)}</div>
    </div>
  </div>
  <div>
    {tip("There are three ways to set the tempo: <b>hold</b> the tempo digits on the display and type a number, tap <b>TAP</b> several times in time, or turn on Ableton Link so the shared session sets it.")}
    {tip("The bottom line of the display is your best helper: after every press it says what happened and what to do next (for example <b>TOUCH EMPTY PAD</b>).")}
  </div>
</div>'''))

# 3. Modes
pages.append(page(f'''
<h2><span class="sn">3</span>Modes: keys 1–5</h2>
<div class="fig narrow">{crop(108, 455, 570, 80, [(1, 120, 466), (2, 236, 466), (3, 351, 466), (4, 466, 466), (5, 581, 466)])}</div>
{legend([
    (1, "PAD", "the pads play sounds. This is where you record, copy and delete samples."),
    (2, "SEQ", "each pad is a pattern (A01…D16, 64 in total). A touch selects a pattern; while playing, it is queued."),
    (3, "STEP", "16 pads = 16 steps of the selected sound. Press key 3 again (it becomes <b>SOUND</b>) to pick the sound by touching a pad."),
    (4, "EDIT", "opens the sample editor for the selected pad (see section 15)."),
    (5, "IMPORT", "loads an audio file (WAV, MP3, AIFF…) from the phone straight onto the selected pad. Silence at the start and end is trimmed automatically."),
])}
{tip("Each mode remembers its own bank: if you were on bank B in PAD and on A in SEQ, switching modes brings the right bank back.")}
<div class="row3">
  <div>{mock_pads([(f"{n}", "has" if n in (1, 2, 3, 5, 6, 9) else "") for n in pads_order()], "PAD: pads with a sound are marked")}</div>
  <div>{mock_pads([(f"A{n:02d}", ("cur" if n == 1 else "has") if n in (1, 2, 5) else "") for n in pads_order()], "SEQ: pads = patterns A01–A16")}</div>
  <div>{mock_pads([(f"{n}", "has" if n in (1, 5, 9, 13, 11, 15) else ("cur" if n == 7 else "")) for n in pads_order()], "STEP: pads = steps 1–16")}</div>
</div>'''))

# 4. Knobs
pages.append(page(f'''
<h2><span class="sn">4</span>Sound knobs</h2>
<div class="fig narrow">{crop(108, 540, 570, 105, [(1, 120, 632), (2, 201, 632), (3, 283, 632), (4, 365, 632), (5, 448, 632), (6, 529, 632), (7, 609, 632)])}</div>
{legend([
    (1, "VOL", "pad volume, 0–200 %."),
    (2, "PAN", "panning: left to the left channel, right to the right channel."),
    (3, "PITCH", "pitch in whole semitones, −12…+12. Fine-tune in cents with the DETUNE knob in the EDIT window."),
    (4, "START", "where the sample starts playing."),
    (5, "END", "where it stops. In LOOP mode, the loop boundary."),
    (6, "ATK", "attack: a smooth fade-in, 0–200 ms."),
    (7, "REL", "release after you let go, 5–2000 ms."),
])}
<div class="howto">
  <div class="step"><b>Turn</b>slide your finger up/down on the knob</div>
  <div class="step"><b>Double-tap</b>back to the default value</div>
  <div class="step"><b>In STEP</b>hold a step and turn: the value applies to that step only</div>
</div>
{tip("The knobs always control the <b>selected</b> pad, the one with the outline. To select another pad, just touch it in PAD mode.")}'''))

# 5. Buttons
pages.append(page(f'''
<h2><span class="sn">5</span>Control buttons</h2>
<div class="fig narrow">{crop(108, 655, 570, 210, [(1, 116, 662), (2, 232, 662), (3, 348, 662), (4, 463, 662), (5, 578, 662), (6, 116, 737), (7, 232, 737), (8, 348, 737), (9, 463, 737), (10, 578, 737), (11, 116, 808), (12, 232, 808), (13, 348, 808), (14, 463, 808), (15, 578, 808)])}</div>
<div class="cols2">
{legend([
    (1, "PLAY", "start the sequencer (pattern or song)."),
    (2, "STOP", "stop and silence all sounds."),
    (3, "REC", "record: in PAD/STEP, live playing into the pattern; in SEQ, the song; in KAOSS, the effects."),
    (4, "TAP", "tap in time and the tempo follows."),
    (5, "UNDO", "undo the last action (up to 3 steps back)."),
    (6, "QUANT", "recording grid: OFF, 1/4, 1/8, 1/8T, 1/16, 1/16T, 1/32."),
    (7, "METRO", "metronome: OFF → recording only → always."),
    (8, "KAOSS", "open the effects screen."),
])}
{legend([
    (9, "LEN", "pattern length: 1 → 2 → 3 → 4 bars."),
    (10, "ONE / GATE / LOOP", "how the selected pad plays (section 9)."),
    (11, "ERASE", "delete: depends on the mode (section 8). In SEQ, <b>holding</b> it erases the song."),
    (12, "SAMPLE", "arm recording, then hold a pad: it records while you hold. In KAOSS this button is <b>RESMP</b>."),
    (13, "SRC:MIC / SRC:INT", "record from the microphone or from other apps on the phone (the button may read SRC:MI or SRC:IN)."),
    (14, "T-HEAD", "automatically skip silence at the start of samples."),
    (15, "T-TAIL", "automatically cut silence at the end of samples."),
])}
</div>'''))

# 6. Banks and faders
pages.append(page(f'''
<h2><span class="sn">6</span>Banks, faders and the right column</h2>
<div class="row2">
  <div class="fig">{crop(678, 160, 135, 600, [(1, 700, 238), (2, 702, 318), (3, 702, 662)], "tall")}</div>
  <div>{legend([
    (1, "DRY/WET", "how much of the KAOSS effect you hear. Each of the 50 effects has its own value. <b>Three quick taps</b> set every effect back to 100 %."),
    (2, "A–D", "pad banks: 4 × 16 = 64 sounds. The current bank is lit."),
    (3, "Other roles of A–D", "in STEP, the pages (bars 1–4) of a long pattern; in KAOSS, four MOTION memories."),
  ])}</div>
</div>
<div class="row2">
  <div class="fig">{crop(1630, 150, 310, 720, [(1, 1680, 655), (2, 1750, 655), (3, 1820, 655), (4, 1890, 655), (5, 1652, 754), (6, 1795, 754), (7, 1652, 818), (8, 1795, 818)], "tall")}</div>
  <div>{legend([
    (1, "MAIN", "master volume."),
    (2, "CLICK", "metronome volume."),
    (3, "GAIN / LOOP", "recording gain (−12…+24 dB). If the selected pad is in LOOP mode, the fader becomes <b>LOOP</b>: the loop length in tempo divisions."),
    (4, "TRIM", "silence threshold for T-HEAD/T-TAIL and auto-trim (−72…−20 dB)."),
    (5, "CHOKE", "hold it and touch pads to assign choke groups."),
    (6, "EXPORT", "record your track to WAV/MP3 or onto a pad."),
    (7, "FULL", "every pad plays at full volume, however hard you touch."),
    (8, "SETUP", "Ableton Link, latency, count-in. Lit while Link is on."),
  ])}</div>
</div>'''))

# 7. Recording
pages.append(page(f'''
<h2><span class="sn">7</span>Recording samples</h2>
<p class="lead">You can record straight onto a pad, from the microphone or from any app on the phone (YouTube, a player…).</p>
<div class="flow">
  <div class="fstep"><span class="fn">1</span>{btn("SRC:MIC")}<p>Pick the source: <b>SRC:MIC</b> is the microphone, <b>SRC:INT</b> is the phone's own sound.</p></div>
  <div class="farrow">→</div>
  <div class="fstep"><span class="fn">2</span>{btn("SAMPLE", on=True, red=True)}<p>Press <b>SAMPLE</b> and it lights up. Display: <b>HOLD A PAD TO RECORD</b>.</p></div>
  <div class="farrow">→</div>
  <div class="fstep"><span class="fn">3</span><div class="mpad big rec">●</div><p><b>Hold</b> a pad: it records while your finger is on it. Let go and the sound is on the pad.</p></div>
</div>
<div class="row2">
  <div>
    <h4>Auto-trimming silence</h4>
    <div class="trimfig">
      <div class="tw"><i class="sil"></i><i class="snd"></i><i class="sil r"></i></div>
      <div class="tl"><span>T-HEAD cuts</span><span>sound</span><span>T-TAIL cuts</span></div>
    </div>
    <p>The <b>TRIM</b> fader decides where "silence" ends: the higher it is, the louder a sound can be and still count as silence.</p>
  </div>
  <div>
    {tip("To record other apps, Android asks for screen/audio capture permission. That is normal: IS-1 records only the sound.")}
    {tip("Recording level is the <b>GAIN</b> fader. You can see the level on the display while recording.")}
    {tip("Some apps block recording of their audio (DRM protection). If there are 3 seconds of silence, the editor tells you.", "warn")}
  </div>
</div>'''))

# 8. Copy
pages.append(page(f'''
<h2><span class="sn">8</span>Copy, paste, delete</h2>
<h4>Copy a sound to another pad</h4>
<div class="flow">
  <div class="fstep"><span class="fn">1</span><div class="mpad big has">3<em>0.7 s</em></div><p>In PAD mode, <b>hold</b> a pad with a sound for about a second.</p></div>
  <div class="farrow">→</div>
  <div class="fstep"><span class="fn">2</span><div class="lcdline">COPY A03 → TOUCH EMPTY PAD</div><p>The sound is copied together with its knob settings.</p></div>
  <div class="farrow">→</div>
  <div class="fstep"><span class="fn">3</span><div class="mpad big empty">+</div><p>Touch an <b>empty</b> pad and the copy lands there.</p></div>
</div>
<h4>Deleting: the ERASE button in each mode</h4>
{table(["Where you are", "What to do", "What is deleted"], [
    ["PAD", "<b>ERASE</b>, then touch a pad", "the sample on that pad"],
    ["SEQ", "<b>ERASE</b>, then touch a pattern", "all notes of the pattern"],
    ["SEQ", "<b>hold ERASE</b>", "the song (pattern chain)"],
    ["STEP", "<b>ERASE</b>", "all steps of the selected sound in the pattern"],
    ["STEP, holding a step", "<b>ERASE</b>", "only that step's parameters (volume, pitch…)"],
    ["KAOSS", "<b>ERASE</b>", "the effects recording (FX AUTOMATION)"],
], "plain")}
{tip("Made a mistake? <b>UNDO</b> brings back a deleted sample, pattern or song, up to three actions back.")}'''))

# 9. Play modes and CHOKE
pages.append(page(f'''
<h2><span class="sn">9</span>How a pad plays: ONE / GATE / LOOP and CHOKE</h2>
<p class="lead">The button at the right end of the second row shows the selected pad's play mode. Each press switches to the next one.</p>
<div class="modes">
  <div class="mode"><div class="mh">{btn("ONE")}</div><svg viewBox="0 0 200 60"><rect x="10" y="38" width="40" height="14" class="fin"/><path d="M50 12 L60 12 L190 50" class="wv"/><text x="12" y="34">finger</text></svg><p><b>ONE SHOT</b>: the sound plays to the end even if you let go. Good for drums.</p></div>
  <div class="mode"><div class="mh">{btn("GATE", on=True)}</div><svg viewBox="0 0 200 60"><rect x="10" y="38" width="90" height="14" class="fin"/><path d="M10 12 L100 12 L115 50" class="wv"/><text x="12" y="34">finger</text></svg><p><b>GATE</b>: plays while you hold the pad. Let go and it fades out (REL knob).</p></div>
  <div class="mode"><div class="mh">{btn("LOOP", on=True)}</div><svg viewBox="0 0 200 60"><rect x="10" y="38" width="160" height="14" class="fin"/><path d="M10 12 L50 30 L50 12 L90 30 L90 12 L130 30 L130 12 L170 30" class="wv"/><text x="12" y="34">finger</text></svg><p><b>LOOP</b>: the sound repeats while you hold. The <b>LOOP</b> fader sets the loop length in tempo divisions: FREE, 1/32 … 1/4.</p></div>
</div>
<h4>CHOKE: mute groups</h4>
<div class="row2">
  <div class="choke">
    {mock_pads([("1", "chk1") if n == 1 else ("2", "chk1") if n == 2 else ("5", "chk2") if n == 5 else ("6", "chk2") if n == 6 else (str(n), "") for n in pads_order()])}
    <div class="chokebtn">{btn("CHOKE", on=True, red=True)}<small>hold</small></div>
  </div>
  <div>
    <p>Pads in the same group <b>cut each other off</b>: a new sound stops the previous one. The classic case is open and closed hi-hats.</p>
    <ol class="plain">
      <li><b>Hold CHOKE</b>: pads that are already in groups light up.</li>
      <li>Keep holding and touch pads: each touch changes the group <b>OFF → 1 → 2 → 3 → 4 → OFF</b>.</li>
      <li>Let go of CHOKE.</li>
    </ol>
    {tip("Each bank A–D has its own four groups: pads in different banks never cut each other off.")}
  </div>
</div>'''))

# 10. SEQ
pages.append(page(f'''
<h2><span class="sn">10</span>Sequencer: patterns (SEQ)</h2>
<div class="row2">
  <div>{mock_pads([(f"A{n:02d}", "cur" if n == 2 else ("has" if n in (1, 3, 4) else ("next" if n == 5 else ""))) for n in pads_order()], "Key 2 SEQ: the bank's 16 patterns")}
    <div class="keyrow"><span class="kc cur"></span>playing <span class="kc has"></span>has notes <span class="kc next"></span>queued</div></div>
  <div>
    <h4>Recording a beat live</h4>
    <ol class="plain">
      <li>In <b>SEQ</b>, select a pattern by touching a pad.</li>
      <li>Press <b>LEN</b> for 1–4 bars and <b>QUANT</b> for the grid.</li>
      <li>Go to <b>PAD</b> and press <b>REC</b>: there is a count-in (1 bar by default), then recording starts.</li>
      <li>Play the pads. The notes go into the looping pattern, so you can add layers.</li>
      <li>Press <b>REC</b> again: recording stops and the pattern keeps playing.</li>
    </ol>
  </div>
</div>
{table(["Action", "How"], [
    ["Select a pattern", "touch a pad. While playing, the pattern starts at the end of the current cycle"],
    ["Copy a pattern", "<b>hold</b> the pattern pad → touch the pad to copy to"],
    ["Clear a pattern", "<b>ERASE</b> → touch the pattern"],
    ["Length", "<b>LEN</b>: 1 → 2 → 3 → 4 bars"],
    ["Metronome", "<b>METRO</b>: off → recording only → always; volume on the CLICK fader"],
    ["Count-in before recording", "in <b>SETUP</b>: off, 1, 2 or 4 bars"],
], "plain")}'''))

# 11. STEP
pages.append(page(f'''
<h2><span class="sn">11</span>Step input (STEP)</h2>
<div class="row2">
  <div>
    {mock_pads([(str(n), "has" if n in (1, 5, 9, 13) else ("held" if n == 11 else ("cur" if n == 7 else ""))) for n in pads_order()], "Key 3 STEP: the 16 sixteenths of one bar")}
    <div class="keyrow"><span class="kc has"></span>step set <span class="kc held"></span>step held <span class="kc cur"></span>playhead</div>
  </div>
  <div>
    <ol class="plain">
      <li>In <b>PAD</b> mode, touch the sound you want to program (or in STEP press <b>SOUND</b> and touch a pad).</li>
      <li>Press <b>STEP</b>. Pads 1–16 are the steps of the bar.</li>
      <li>Touch an empty step to add a note; touch a set step to remove it.</li>
      <li>Pattern longer than a bar? Buttons <b>A–D</b> are the pages: bars 1–4.</li>
    </ol>
  </div>
</div>
<div class="plock">
  <div class="plock-l">
    <div class="mpad big held">11<em>hold</em></div>
    <div class="plus">+</div>
    <div class="knobpic"><span>PITCH</span><em></em><small>+5 ST</small></div>
  </div>
  <div class="plock-r">
    <h4>Parameters for a single step</h4>
    <p><b>Hold</b> a step and turn any knob (VOL, PAN, PITCH, START, END, ATK, REL): the value is stored <b>for that step only</b>. Use it to play a melody with one sound or to bring hi-hats to life.</p>
    <p>Hold a step and press <b>ERASE</b> to reset that step to the pad's values. Deleting the step or the whole pattern deletes its parameters too.</p>
  </div>
</div>'''))

# 12. SONG and UNDO
pages.append(page(f'''
<h2><span class="sn">12</span>Song (SONG) and UNDO</h2>
<p class="lead">A song is a chain of patterns that play one after another, for example: A01 A01 A02 A03.</p>
<div class="flow">
  <div class="fstep"><span class="fn">1</span>{btn("2<small>SEQ</small>", on=True)}<p>Open <b>SEQ</b>.</p></div>
  <div class="farrow">→</div>
  <div class="fstep"><span class="fn">2</span>{btn("● REC", red=True, on=True)}<p>Press <b>REC</b>. Display: <b>SONG REC: TOUCH PATTERNS IN ORDER</b>.</p></div>
  <div class="farrow">→</div>
  <div class="fstep"><span class="fn">3</span><div class="chain"><span>A01</span><span>A01</span><span>A02</span><span>A03</span></div><p>Touch the patterns in order.</p></div>
  <div class="farrow">→</div>
  <div class="fstep"><span class="fn">4</span>{btn("● REC", red=True)}<p><b>REC</b> again to finish. <b>PLAY</b> plays the whole song.</p></div>
</div>
{tip("<b>Hold ERASE</b> in SEQ mode to erase the song; a single pattern plays again.", "hold")}
{tip("The song length matters: KAOSS effects recording (REC in KAOSS) and EXPORT both use it.")}
<h4>UNDO</h4>
<p><b>UNDO</b> reverts up to the last <b>three</b> actions: recording, deleting a sample, clearing or copying a pattern, steps, length, the song, pasting, CHOKE, play mode, effects recording. The display says what was undone, for example <b>UNDO: CLEAR A02</b>.</p>'''))

# 13. KAOSS
pages.append(page(f'''
<h2><span class="sn">13</span>KAOSS: effects on the whole mix</h2>
<p class="lead">Press <b>KAOSS</b> and the display turns into an effects touch pad, like a hardware X/Y pad. Touch it to switch the effect on; move your finger to change two parameters at once: horizontally (X) and vertically (Y).</p>
<div class="fig">{crop(70, 0, 1930, 900, [(1, 610, 80), (2, 545, 150), (3, 600, 300), (4, 662, 405), (5, 92, 482),
                                            (6, 224, 806), (7, 343, 733), (8, 756, 292), (9, 756, 792), (10, 343, 658)], src="kaoss.png", cls="small", overlays=[
    (118, 56, 450, 48, "3·REPEAT  8/10  R08", 33, "color:#29b6ff"),
    (118, 118, 400, 66, "SCRATCH LOOP", 50, "color:#b3e5fc;font-weight:700"),
    (118, 383, 545, 46, "X LOOP LENGTH  68   Y SCRATCH", 29, "color:rgba(179,229,252,.75)"),
])}</div>
<div class="cols2">
{legend([
    (1, "Set, number and code", "3·REPEAT 8/10 R08. The status appears on the right of this line: <b>● FX REC 42%</b>, <b>▶ MOTION B</b> or <b>▶ FX AUTO</b>."),
    (2, "Effect name", "in large letters."),
    (3, "Finger", "a bright dot and crosshair; the lights around it show the intensity."),
    (4, "Axes", "what X and Y do right now and their values 0–100."),
    (5, "Keys 1–5", "the sets FILTER, SPACE, REPEAT, MOD, LFO. Press again to step through the set's 10 effects."),
])}
{legend([
    (6, "RESMP", "resampling: records everything you hear (next page)."),
    (7, "KAOSS", "lit red while the effects screen is open. Press again to return to the normal display."),
    (8, "DRY/WET", "how much of the effect you hear."),
    (9, "A–D", "four MOTION memories."),
    (10, "REC / ERASE", "<b>REC</b> records the effects over the song length, <b>ERASE</b> deletes that recording."),
])}
</div>
</div>'''))

pages.append(page(f'''
<h2><span class="sn">13</span>KAOSS: motions, effects recording, resampling</h2>
<div class="feat">
  <div class="fh">{btn("A")}{btn("B", on=True)}{btn("C")}{btn("D")}<span>MOTION</span></div>
  <div><b>Motion memory.</b> <b>Hold</b> one of A–D and move your finger on the pad to record the movement. Let go and it loops right away (the button lights green). A short press switches the motion on/off. Touching the pad overrides the motion.</div>
</div>
<div class="feat">
  <div class="fh">{btn("● REC", red=True, on=True)}<span>FX REC</span></div>
  <div><b>Recording effects over the song.</b> In KAOSS press <b>REC</b>: the sequencer starts and everything you do with the effects (which effect, X, Y, touch) is recorded for exactly one song length (or pattern length). Progress shows on the display: <b>● FX REC 42%</b>. After that the recording always plays with the sequencer, even outside the KAOSS screen (flag <b>FXA</b>). Your finger overrides it. <b>ERASE</b> in KAOSS deletes it.</div>
</div>
<div class="feat">
  <div class="fh">{btn("RESMP", on=True)}<span>RESAMPLE</span></div>
  <div><b>Resampling.</b> Press <b>RESMP</b> to record the whole IS-1 output with effects (up to 30 seconds). Press again to stop. Then <b>hold RESMP</b>: the recording is copied and the app switches to PAD. Touch any pad to put it there.</div>
</div>
<h4>Effect tails</h4>
<div class="tailfig">
  <svg viewBox="0 0 600 90"><rect x="20" y="58" width="220" height="16" class="fin"/><text x="24" y="52">finger on the pad</text>
  <path d="M20 30 L240 30" class="wv"/><path d="M240 30 C 300 32, 330 60, 580 78" class="wv dash"/><text x="300" y="30">echo and reverb ring out</text></svg>
</div>
<p>Effects marked <span class="tail">tail</span> in the table (delays, reverbs, Noise Riser…) let their repeats and reverb fade out naturally after you lift your finger. The others stop at once.</p>
{tip("<b>DRY/WET</b> is stored per effect. Three quick taps on the knob set every effect to 100 %.")}'''))

# 14. Effects
pages.append(page('<h2><span class="sn">14</span>All 50 effects</h2>'
                  '<p class="lead">A code is the set letter plus a number (R08 = REPEAT, effect 8). '
                  'X is your finger left/right, Y is down/up. "Tempo-synced" means the time follows the BPM.</p>'
                  + fx_pages().split('<h3 class="fxh"><span class="bank">3')[0], "fxpage"))
rest = fx_pages()
i3 = rest.index('<h3 class="fxh"><span class="bank">3')
i5 = rest.index('<h3 class="fxh"><span class="bank">5')
pages.append(page('<h2><span class="sn">14</span>All 50 effects (continued)</h2>' + rest[i3:i5], "fxpage"))
pages.append(page('<h2><span class="sn">14</span>All 50 effects (end)</h2>' + rest[i5:] + tip(
    "The loopers (REPEAT) don't repeat straight away: the first pass after you touch is recorded, then the sound loops. "
    "So touch slightly before the beat you want.") + tip(
    "Want to repeat something that already played? <b>SLICE PICK</b> (R07) takes a slice of the last bar <b>before</b> your touch."), "fxpage"))

# 15. EDIT
pages.append(page(f'''
<h2><span class="sn">15</span>The EDIT window</h2>
<p class="lead">Key <b>4 EDIT</b> opens the sample editor for the selected pad: recording, trimming, processing and slicing onto pads.</p>
<div class="fig">{crop(70, 0, 1930, 900, [(1, 100, 212), (2, 300, 600), (3, 1850, 640), (4, 100, 687), (5, 100, 802)], src="edit.png")}</div>
{legend([
    (1, "Recording", "<b>● REC</b>, source MIC/INTERNAL, mono/stereo, <b>THRESHOLD</b> starts on signal, <b>COUNT 3 s</b> is a count-in."),
    (2, "Waveform and selection", "the orange handles at the edges are the selection boundaries; drag them with your finger. Let go and the selection plays right away."),
    (3, "PITCH and DETUNE", "the pad's pitch: ±12 semitones and ±50 cents. Let go of the knob and you hear it at once."),
    (4, "Processing", "PREVIEW, stop, TRIM, NORMALIZE, FADE IN/OUT, REVERSE, SILENCE, UNDO; after every edit the result plays."),
    (5, "Slicing and assigning", "MARKERS + (tap the waveform to place markers, shown as blue lines), AUTO (by hits), ÷4/÷8/÷16, RESET, SLICES → slice onto the bank's pads, → PAD puts the selection on the selected pad, IMPORT, RECORDINGS lists past recordings (the row scrolls sideways)."),
])}
{tip("Trimming and processing in EDIT change the copy in the editor. To change the sound on the pad, press <b>→ PAD</b>. PITCH and DETUNE change the pad immediately.", "warn")}'''))

# 16. EXPORT
pages.append(page(f'''
<h2><span class="sn">16</span>EXPORT: save your track</h2>
<p class="lead"><b>EXPORT</b> records the whole IS-1 output (every pad plus the KAOSS effects) for exactly the song length, or the current pattern if there is no song. A one-bar pattern gives a file exactly one bar long.</p>
<div class="flow3">
  <div><span class="fn">1</span>{crop(662, 266, 746, 380, src="export1.png")}<p>You see what will be recorded: song or pattern and how many bars. Press <b>● RECORD</b>.</p></div>
  <div><span class="fn">2</span>{crop(662, 266, 746, 380, src="export2.png")}<p>The song plays from the start in real time. The metronome is not recorded. <b>STOP</b> cancels.</p></div>
  <div><span class="fn">3</span>{crop(662, 266, 746, 380, src="export3.png")}<p>Choose where the finished recording goes.</p></div>
</div>
{table(["Button", "What it does"], [
    ["<b>WAV</b>", "save lossless (16-bit) to the phone's <b>Music/IS-1</b> folder"],
    ["<b>MP3</b>", "save a 320 kbps MP3 to the same folder, handy for messaging apps"],
    ["<b>TO PAD</b>", "put the recording on any pad: the window closes, then touch a pad"],
    ["<b>AGAIN</b>", "record again"],
], "plain")}
{tip("You can save both a WAV and an MP3 from one recording. Files are named by date: <b>IS-1_20261024_153012.mp3</b>.")}
{tip("Exports can be up to 10 minutes long. Ableton Link sync pauses while exporting.", "warn")}'''))

# 17. SETUP
pages.append(page(f'''
<h2><span class="sn">17</span>SETUP: Ableton Link and latency</h2>
<div class="row2">
  <div class="fig">{crop(95, 150, 1300, 720, [(1, 1170, 240), (2, 1150, 576)], src="setup1.png")}</div>
  <div class="fig">{crop(95, 30, 1300, 840, [(3, 880, 712)], src="setup2.png")}</div>
</div>
{tip("The SETUP screen is in Russian: <b>ВКЛ</b> = On, <b>ВЫКЛ</b> = Off, <b>ЗАКРЫТЬ</b> = Close, <b>ТАКТ</b> = bar, <b>мс</b> = ms, «Отсчёт перед записью» = count-in before recording.")}
{legend([
    (1, "Ableton Link", "<b>ВКЛ/ВЫКЛ</b> (on/off): tempo and start sync with Ableton Live and other Link apps on the same Wi-Fi network. Below the button is the status: searching for peers, or connected with the peer count and tempo. Press PLAY in any app and everyone starts on the next bar."),
    (2, "Latency", "shifts IS-1 against the other peers, ±100 ms; the −1 / 0 / +1 ms buttons fine-tune it. If IS-1 sounds later than the computer, move right (+); if earlier, move left (−)."),
    (3, "Count-in", "how many bars the metronome counts after REC: off, 1, 2 or 4. Not needed with Link."),
])}
<h4>Connecting to Ableton Live</h4>
<ol class="plain">
  <li>Put the phone and the computer on the same Wi-Fi network.</li>
  <li>In Ableton Live, turn on <b>LINK</b> in the top left corner.</li>
  <li>In IS-1: <b>SETUP</b> → turn <b>Ableton Link</b> on. <b>LNK</b> appears on the display and the SETUP button lights up.</li>
  <li>Change the tempo in either app: it is shared.</li>
  <li>Adjust <b>Latency</b> by ear until the hits line up.</li>
</ol>'''))

# 18. Cheat sheet
pages.append(page(f'''
<h2><span class="sn">18</span>Cheat sheet</h2>
{table(["I want to…", "I do"], [
    ["record a sound onto a pad", "<b>SAMPLE</b> → hold a pad"],
    ["copy a sound", "in PAD hold the pad ~1 s → touch an empty pad"],
    ["delete a sound", "in PAD <b>ERASE</b> → touch the pad"],
    ["type an exact tempo", "hold the tempo digits on the display"],
    ["record a beat", "<b>REC</b> in PAD, play the pads"],
    ["program steps", "<b>STEP</b>, touch pads 1–16"],
    ["make one step higher/quieter", "hold the step + turn a knob"],
    ["copy a pattern", "in SEQ hold the pattern → touch another"],
    ["build a song", "in SEQ <b>REC</b> → touch patterns → <b>REC</b>"],
    ["erase the song", "in SEQ hold <b>ERASE</b>"],
    ["make hi-hats cut each other", "hold <b>CHOKE</b> + touch the pads"],
    ["record effects over the song", "<b>KAOSS</b> → <b>REC</b> → play the touch pad"],
    ["remember a finger movement", "in KAOSS hold <b>A–D</b> and move your finger"],
    ["put a sound with effects on a pad", "in KAOSS <b>RESMP</b> → <b>RESMP</b> → hold <b>RESMP</b> → pad"],
    ["save my track", "<b>EXPORT</b> → <b>● RECORD</b> → <b>WAV</b>/<b>MP3</b>"],
    ["undo a mistake", "<b>UNDO</b> (up to 3 times)"],
    ["reset a knob", "double-tap the knob"],
    ["set all effects to 100 %", "three taps on DRY/WET"],
], "plain cheat")}'''))

write("manual_en.html", "en", "IS-1 User Manual", "en", pages)
