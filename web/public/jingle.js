/**
 * เพลงร้าน — the stall's jingle, played over the board's speaker on a timer.
 *
 * A module of its own, loaded by its own `<script>`, so that a fault in here cannot stop the
 * board drawing orders. The board is the thing the kitchen cannot work without; the music is
 * not, and the two should not be able to take each other down.
 *
 * **Off / 1 / 3 / 5 is one setting, not two.** The obvious shape — a switch plus an interval —
 * can hold "on, with no interval chosen", which is a state nobody could read off the screen and
 * which the two halves would then have to agree about. ปิด is simply the fourth choice, so the
 * control always says exactly what will happen and when.
 *
 * ⚠️ **A browser will not play sound until somebody has tapped the page**, and that is the whole
 * reason every tap on this control plays the song at once rather than only arming the timer: the
 * tap is both the setting and the permission, and hearing it is how the person who set it knows
 * it works. It is also why there is **one** `<audio>` element, reused for the life of the page
 * and never recreated — the permission a gesture grants belongs to that element, so a fresh
 * `new Audio()` on each play would be blocked from the second play onward.
 *
 * The setting is **per device** (`localStorage`), like the sales page's bookmark and unlike
 * anything the terminal owns: the iPad at the stall wants music and a phone checking the queue
 * from across the ground does not.
 */

/** The choices, in the order they are drawn. 0 is ปิด. */
const MINUTES = [0, 1, 3, 5];
const STORE_KEY = 'eiamjun-event.jingleMinutes';

const el = {
  label: document.getElementById('jingle-label'),
  opts: document.getElementById('jingle-opts'),
  audio: document.getElementById('jingle-audio')
};

/** 0 = ปิด. */
let minutes = load();
/** When the next play is due, as an epoch ms. 0 while off. */
let nextAt = 0;
/** Set when the browser refused to play, cleared by the next tap. */
let needsTap = false;
/**
 * Whether the mp3 itself cannot be loaded — missing, or a format this browser will not take.
 *
 * ⚠️ **Asked of the element, never remembered from its `error` event.** `preload="auto"` starts
 * the download while the page is still parsing, and this module is deferred like every ES module,
 * so a 404 has already fired and gone by the time a listener could be attached — which is exactly
 * how it behaved when the file was first missing: a countdown ran happily against silence. The
 * listener below is only there to repaint when it fails *later*.
 */
function noFile() {
  return Boolean(el.audio.error) || el.audio.networkState === HTMLMediaElement.NETWORK_NO_SOURCE;
}

function load() {
  try {
    const saved = Number(localStorage.getItem(STORE_KEY));
    return MINUTES.includes(saved) ? saved : 0;
  } catch {
    // Private browsing, or storage turned off. Starting silent is the safe way to be wrong.
    return 0;
  }
}

function save() {
  try {
    localStorage.setItem(STORE_KEY, String(minutes));
  } catch {
    /* Not worth telling anybody about: the setting still holds for this page. */
  }
}

function choose(value) {
  minutes = value;
  save();
  needsTap = false;
  if (minutes === 0) {
    stop();
  } else {
    // Plays now, rather than in N minutes. The tap is the browser's permission to make sound at
    // all, so spending it immediately is what proves the thing works — and somebody who has just
    // set "ทุก 5 นาที" should not have to wait five minutes to find out it does not.
    play();
  }
  paint();
}

function play() {
  // Reschedule first, and from now rather than from the end of the song: "ทุก 5 นาที" is five
  // minutes between starts. Doing it before the `play()` means a refused play still leaves a
  // timer running, so the next one can succeed once somebody has tapped.
  nextAt = Date.now() + minutes * 60_000;

  // Already singing — two copies of a jingle over each other is the thing nobody debugs calmly
  // during a service.
  //
  // ⚠️ It **retries in a second rather than skipping to the next interval**, and the stall's own
  // song is why: at 61 seconds it is longer than the shortest setting, so a skip would make
  // "ทุก 1 นาที" play every two minutes while the button went on saying one. Retrying means the
  // next one starts the moment this one ends, which is what that setting is asking for.
  if (!el.audio.paused && !el.audio.ended) {
    nextAt = Date.now() + 1000;
    return;
  }

  el.audio.volume = 1; // The loudest a web page may be. The rest is the device's own volume.
  // Rewinding throws on a stream the browser cannot seek. It should never be one — the server
  // answers byte ranges precisely so that it is not — but a jingle that stops because a seek
  // threw would be a silent board, and `play()` on a finished element starts it over anyway.
  try {
    el.audio.currentTime = 0;
  } catch {
    /* not seekable; play() restarts a finished element on its own */
  }
  const started = el.audio.play();
  if (started && typeof started.catch === 'function') {
    started.catch(() => {
      // Autoplay refused — the page has been reloaded and nobody has touched it yet. Said on the
      // control rather than swallowed, because silence looks identical to a jingle that is off.
      needsTap = true;
      paint();
    });
  }
}

function stop() {
  nextAt = 0;
  el.audio.pause();
  el.audio.currentTime = 0;
}

/** mm:ss, because a bare count of seconds past a minute is read as the wrong thing. */
function countdown(ms) {
  const total = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}

function paint() {
  for (const button of el.opts.children) {
    button.classList.toggle('on', Number(button.dataset.min) === minutes);
  }

  el.label.classList.toggle('needs-tap', needsTap || noFile());
  if (noFile()) {
    // Said out loud rather than failing quietly. A stall that has swapped the jingle for a file
    // the browser cannot read would otherwise see a countdown running against silence.
    el.label.textContent = 'เพลงร้าน · ไม่พบไฟล์เพลง';
  } else if (minutes === 0) {
    el.label.textContent = 'เพลงร้าน · ปิด';
  } else if (needsTap) {
    el.label.textContent = 'เพลงร้าน · แตะปุ่มอีกครั้ง';
  } else if (!el.audio.paused && !el.audio.ended) {
    el.label.textContent = 'เพลงร้าน · กำลังเล่น';
  } else {
    el.label.textContent = `เพลงร้าน · อีก ${countdown(nextAt - Date.now())}`;
  }
}

/**
 * One tick a second, checking a timestamp rather than counting down a `setInterval`.
 *
 * A browser throttles timers in a tab that is not in front, and this board does sit unattended —
 * so an interval that fires N times behind schedule would make up the difference in a burst. A
 * due-time is caught up with exactly once, whether the page was asleep for one minute or twenty.
 */
function tick() {
  if (minutes > 0 && !needsTap && !noFile() && nextAt && Date.now() >= nextAt) play();
  paint();
}

for (const value of MINUTES) {
  const button = document.createElement('button');
  button.type = 'button';
  button.dataset.min = String(value);
  button.textContent = value === 0 ? 'ปิด' : `${value} น.`;
  // Every tap goes through `choose`, the active one included: tapping what is already chosen
  // plays the song again, which is the way back from a refused autoplay and from "I did not hear
  // anything".
  button.addEventListener('click', () => choose(value));
  el.opts.append(button);
}

el.audio.addEventListener('ended', paint);
el.audio.addEventListener('play', paint);
el.audio.addEventListener('error', paint);

// Restores the saved interval and arms the timer, but plays nothing: no gesture has been made on
// this page yet, so the first due play may well be refused — which the label then says.
if (minutes > 0) nextAt = Date.now() + minutes * 60_000;
paint();
setInterval(tick, 1000);
