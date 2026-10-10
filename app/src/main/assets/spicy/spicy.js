/*
 * Standalone renderer for Spicy Lyrics' look and motion.
 *
 * Re-implements, outside Spotify, the parts of https://github.com/Spikerko/spicy-lyrics that draw
 * and animate lyrics: the DOM structure the stylesheet expects, the per-word/letter/dot spline +
 * spring animation, distance blur, line states, and the spring-driven scroll. Licensed under the
 * GNU AGPL v3 like the project it follows (see LICENSE-spicy-lyrics / NOTICE.txt).
 * The Spring class is a port of Fraktality's spr (MIT).
 *
 * Android drives it through window.SpicyLyrics:
 *   setLyrics(json), setAnchor(positionMs, isPlaying, speed), setFontSize(px), setOffsetMs(ms)
 * and receives callbacks through the optional `Android` interface:
 *   onSeek(ms), onUserScroll(bool)
 */
(function () {
  "use strict";

  /* ------------------------------------------------------------------------------------------
   * Spring — port of https://github.com/Fraktality/spr (MIT, Copyright (c) Fraktality)
   * ---------------------------------------------------------------------------------------- */
  var SLEEP_OFFSET_SQ_LIMIT = Math.pow(1 / 3840, 2);
  var SLEEP_VELOCITY_SQ_LIMIT = Math.pow(1e-2, 2);
  var EPS = 1e-5;

  function Spring(startPosition, frequency, dampingRatio) {
    this.d = dampingRatio;
    this.f = frequency;
    this.g = startPosition;
    this.p = startPosition;
    this.v = 0;
  }
  Spring.prototype.Step = function (dt) {
    var d = this.d, f = this.f * (2 * Math.PI), g = this.g, p = this.p, v = this.v;
    var exp = Math.exp, sin = Math.sin, cos = Math.cos, sqrt = Math.sqrt;
    if (d === 1) {
      var q = exp(-f * dt), w = dt * q;
      var c0 = q + w * f, c2 = q - w * f, c3 = w * f * f;
      var o = p - g;
      p = o * c0 + v * w + g;
      v = v * c2 - o * c3;
    } else if (d < 1) {
      var q2 = exp(-d * f * dt);
      var c = sqrt(1 - d * d);
      var i = cos(dt * f * c), j = sin(dt * f * c);
      var z;
      if (c > EPS) z = j / c;
      else { var a = dt * f; z = a + ((a * a) * (c * c) * (c * c) / 20 - c * c) * (a * a * a) / 6; }
      var y;
      if (f * c > EPS) y = j / (f * c);
      else { var b = f * c; y = dt + ((dt * dt) * (b * b) * (b * b) / 20 - b * b) * (dt * dt * dt) / 6; }
      var o2 = p - g;
      p = (o2 * (i + z * d) + v * y) * q2 + g;
      v = (v * (i - z * d) - o2 * (z * f)) * q2;
    } else {
      var c4 = sqrt(d * d - 1);
      var r1 = -f * (d + c4), r2 = -f * (d - c4);
      var ec1 = exp(r1 * dt), ec2 = exp(r2 * dt);
      var o3 = p - g;
      var co2 = (v - o3 * r1) / (2 * f * c4);
      var co1 = ec1 * (o3 - co2);
      p = co1 + co2 * ec2 + g;
      v = co1 * r1 + co2 * ec2 * r2;
    }
    this.p = p; this.v = v;
    return p;
  };
  Spring.prototype.CanSleep = function () {
    if (this.v * this.v > SLEEP_VELOCITY_SQ_LIMIT) return false;
    var o = this.p - this.g;
    return o * o <= SLEEP_OFFSET_SQ_LIMIT;
  };
  Spring.prototype.SetGoal = function (goal, replacePosition) {
    this.g = goal;
    if (replacePosition) { this.p = goal; this.v = 0; }
  };

  /* ------------------------------------------------------------------------------------------
   * Natural cubic spline (what Spicy's `cubic-spline` dependency computes)
   * ---------------------------------------------------------------------------------------- */
  function Spline(points) {
    var n = points.length;
    this.xs = points.map(function (p) { return p[0]; });
    this.ys = points.map(function (p) { return p[1]; });
    var xs = this.xs, ys = this.ys;
    var M = new Array(n).fill(0);
    if (n > 2) {
      var lower = new Array(n).fill(0), diag = new Array(n).fill(0), upper = new Array(n).fill(0), rhs = new Array(n).fill(0);
      for (var i = 1; i < n - 1; i++) {
        var h0 = xs[i] - xs[i - 1], h1 = xs[i + 1] - xs[i];
        lower[i] = h0; diag[i] = 2 * (h0 + h1); upper[i] = h1;
        rhs[i] = 6 * ((ys[i + 1] - ys[i]) / h1 - (ys[i] - ys[i - 1]) / h0);
      }
      for (var k = 2; k < n - 1; k++) {
        var w = lower[k] / diag[k - 1];
        diag[k] -= w * upper[k - 1];
        rhs[k] -= w * rhs[k - 1];
      }
      M[n - 2] = rhs[n - 2] / diag[n - 2];
      for (var m = n - 3; m >= 1; m--) M[m] = (rhs[m] - upper[m] * M[m + 1]) / diag[m];
    }
    this.M = M;
  }
  Spline.prototype.at = function (x) {
    var xs = this.xs, ys = this.ys, M = this.M, n = xs.length;
    x = Math.min(Math.max(x, xs[0]), xs[n - 1]);
    var i = n - 2;
    for (var k = 0; k < n - 1; k++) { if (x <= xs[k + 1]) { i = k; break; } }
    var h = xs[i + 1] - xs[i], a = (xs[i + 1] - x) / h, b = (x - xs[i]) / h;
    return a * ys[i] + b * ys[i + 1] + ((a * a * a - a) * M[i] + (b * b * b - b) * M[i + 1]) * h * h / 6;
  };

  /* ------------------------------------------------------------------------------------------
   * Animation constants (src/utils/Lyrics/Animator/*)
   * ---------------------------------------------------------------------------------------- */
  var ScaleSpline = new Spline([[0, 0.95], [0.7, 1.0505], [1, 1]]);
  var LetterScaleSpline = new Spline([[0, 0.95], [0.7, 1.175], [1, 1]]);
  var YOffsetSpline = new Spline([[0, 1 / 100], [0.9, -(1 / 60)], [1, 0]]);
  var LetterYOffsetSpline = new Spline([[0, 1 / 100], [0.9, -(1 / 56)], [1, 0]]);
  var GlowSpline = new Spline([[0, 0], [0.15, 1], [0.6, 1], [1, 0]]);
  var LineGlowSpline = new Spline([[0, 0], [0.5, 1], [1, 0]]);
  var DotScaleSpline = new Spline([[0, 0.75], [0.7, 1.05], [1, 1]]);
  var DotYOffsetSpline = new Spline([[0, 0], [0.9, -0.12], [1, 0]]);
  var DotGlowSpline = new Spline([[0, 0], [0.6, 1], [1, 1]]);
  var DotOpacitySpline = new Spline([[0, 0.35], [0.6, 1], [1, 1]]);

  var YOffsetDamping = 0.4, YOffsetFrequency = 1.45;
  var ScaleDamping = 0.64, ScaleFrequency = 0.88;
  var GlowDamping = 0.56, GlowFrequency = 1.18;
  var LineGlowDamping = 0.5, LineGlowFrequency = 1;
  var LetterGlowMultiplier_Opacity = 185;
  var SungLetterGlow = 0.2;
  var IdleLyricsScale = 0.95, IdleEmphasisLyricsScale = 0.95;
  var BlurMultiplier = 1.25;
  var BLUR_MAX = BlurMultiplier * 5 + BlurMultiplier * 0.465;
  var LETTER_TAIL_MS = 250;
  var LETTER_MIN_DURATION_MS = 1000;
  var LYRICS_BETWEEN_SHOW_MS = 3000;
  var PRE_HIDDEN_DOT_LINE_MS = 500;
  var INTERLUDE_PADDING_MS = -(PRE_HIDDEN_DOT_LINE_MS + 50);
  var GAP_NORMAL = 1, GAP_LINE_TO_BG = 0.2;
  var SCROLL_FREQUENCY = 2.2, SCROLL_DAMPING = 1, SCROLL_CENTER_OFFSET_PX = 30;
  var USER_SCROLL_HOLD_MS = 3000;
  var LAYOUT_GLIDE = "transform 0.32s cubic-bezier(0.22, 1, 0.36, 1)";

  function easeSinOut(x) { return Math.sin((Math.min(Math.max(x, 0), 1) * Math.PI) / 2); }

  function createWordSprings() {
    return {
      Scale: new Spring(ScaleSpline.at(0), ScaleFrequency, ScaleDamping),
      YOffset: new Spring(YOffsetSpline.at(0), YOffsetFrequency, YOffsetDamping),
      Glow: new Spring(GlowSpline.at(0), GlowFrequency, GlowDamping)
    };
  }
  function createLetterSprings() {
    return {
      Scale: new Spring(LetterScaleSpline.at(0), ScaleFrequency, ScaleDamping),
      YOffset: new Spring(LetterYOffsetSpline.at(0), YOffsetFrequency, YOffsetDamping),
      Glow: new Spring(GlowSpline.at(0), GlowFrequency, GlowDamping)
    };
  }
  function createDotSprings() {
    return {
      Scale: new Spring(DotScaleSpline.at(0), 0.7, 0.6),
      YOffset: new Spring(DotYOffsetSpline.at(0), 1.25, 0.4),
      Glow: new Spring(DotGlowSpline.at(0), 1, 0.5),
      Opacity: new Spring(DotOpacitySpline.at(0), 1, 0.5)
    };
  }
  function springsSleeping(s) {
    for (var k in s) { if (!s[k].CanSleep()) return false; }
    return true;
  }

  /* ------------------------------------------------------------------------------------------
   * Small helpers
   * ---------------------------------------------------------------------------------------- */
  var RTL_REGEX = /[֐-׿؀-ۿݐ-ݿࢠ-ࣿיִ-ﭏﭐ-﷿ﹰ-﻿]/;
  var NEUTRAL_REGEX = /[\d\s,.;:?!()[\]{}"'\\/<>@#$%^&*_=+-]/;
  function isRtl(text) {
    if (!text) return false;
    for (var i = 0; i < text.length; i++) {
      var ch = text[i];
      if (NEUTRAL_REGEX.test(ch)) continue;
      return RTL_REGEX.test(ch);
    }
    return false;
  }
  function stripZeroWidth(s) { return (s || "").replace(/[​-‍﻿]/g, ""); }

  function stateOf(pos, start, end) {
    if (pos < start) return "NotSung";
    if (pos >= end) return "Sung";
    return "Active";
  }
  function progress(pos, start, end) {
    if (pos <= start) return 0;
    if (pos >= end) return 1;
    return (pos - start) / (end - start);
  }
  function setClass(el, name, on) {
    if (on) { if (!el.classList.contains(name)) el.classList.add(name); }
    else if (el.classList.contains(name)) el.classList.remove(name);
  }

  // Cached style writes: skip when the value moved less than `thr`, like Spicy's setStyleIfChanged.
  function styleNum(o, prop, num, thr, text) {
    var c = o.c || (o.c = {});
    var prev = c[prop];
    if (prev !== undefined && Math.abs(prev - num) < thr) return;
    c[prop] = num;
    var el = o.el;
    if (prop === "scale") el.style.scale = text;
    else if (prop === "transform") el.style.transform = text;
    else if (prop === "opacity") el.style.opacity = text;
    else el.style.setProperty(prop, text);
  }
  function styleStr(o, prop, text) {
    var c = o.c || (o.c = {});
    if (c[prop] === text) return;
    c[prop] = text;
    o.el.style.setProperty(prop, text);
  }
  function translateY(o, y) {
    styleNum(o, "transform", y, 0.0001, "translate3d(0, calc(var(--DefaultLyricsSize) * " + y + "), 0)");
  }

  /* ------------------------------------------------------------------------------------------
   * State
   * ---------------------------------------------------------------------------------------- */
  var page = document.getElementById("SpicyLyricsPage");
  var scrollEl = page.querySelector(".LyricsContent");
  var scrollContainer = page.querySelector(".SpicyLyricsScrollContainer");
  var virtual = page.querySelector(".VirtualLyricsContainer");

  var lines = [];
  var lyricsType = "Syllable";
  var anchor = { pos: 0, perf: 0, playing: false, speed: 1 };
  var offsetMs = 0;
  var blurLast = null;
  var lastFrame = 0;
  var frameScheduled = false;
  var idleFrames = 0;
  var layoutDirty = true;
  var glideBlockedUntil = 0;
  var lastWidth = 0;

  var scrollSpring = new Spring(0, SCROLL_FREQUENCY, SCROLL_DAMPING);
  var scrollTarget = null;
  var scrollIdx = -1;
  var scrollSnap = true;
  var lastScrollWritten = 0;
  var userScrollUntil = 0;
  var userScrolling = false;

  function nowMs() { return performance.now(); }
  function currentPos(now) {
    var p = anchor.pos;
    if (anchor.playing) p += (now - anchor.perf) * anchor.speed;
    return p + offsetMs;
  }

  /* ------------------------------------------------------------------------------------------
   * DOM construction (mirrors Applyer/Synced/Line.ts and Syllable.ts)
   * ---------------------------------------------------------------------------------------- */
  function makeDot() {
    var s = document.createElement("span");
    s.classList.add("word");
    s.classList.add("dot");
    s.textContent = "•";
    return s;
  }

  function addDotLine(gapStart, gapEnd, opposite, isIntro) {
    var el = document.createElement("div");
    el.classList.add("line");
    el.classList.add("musical-line");
    if (opposite) el.classList.add("OppositeAligned");
    var total = gapEnd - gapStart;
    var base = total / 3;
    var pad = INTERLUDE_PADDING_MS / 3;
    var d1 = Math.max(gapStart, gapStart + base + pad);
    var d2 = Math.max(d1, gapStart + base * 2 + pad * 2);
    var d3 = Math.max(d2, gapStart + total + INTERLUDE_PADDING_MS);
    var bounds = [[gapStart, d1], [d1, d2], [d2, d3]];
    var group = document.createElement("div");
    group.classList.add("dotGroup");
    var words = [];
    for (var i = 0; i < 3; i++) {
      var dot = makeDot();
      group.appendChild(dot);
      words.push({ el: dot, start: bounds[i][0], end: bounds[i][1], dot: true });
    }
    el.appendChild(group);
    lines.push({ el: el, start: gapStart, end: gapEnd, dot: true, words: words, opposite: !!opposite });
  }

  function buildWords(lineEl, syllables, lineObj, isBg) {
    var items = syllables.filter(function (s) { return s.t && s.t.trim().length > 0; });
    var group = null;
    var fontVar = isBg ? "var(--font-size)" : "var(--DefaultLyricsSize)";
    items.forEach(function (s, i, arr) {
      var text = stripZeroWidth(s.t).trim();
      if (!text) return;
      var totalDuration = s.e - s.s;
      var capable = text.length > 0 && totalDuration >= LETTER_MIN_DURATION_MS && !isRtl(text);
      var node;
      if (capable) {
        node = document.createElement("div");
        var startT = s.s, endT = s.e - LETTER_TAIL_MS;
        var letters = text.split("");
        var letterDuration = (endT - startT) / letters.length;
        var letterObjs = [];
        letters.forEach(function (ch, idx) {
          var span = document.createElement("span");
          span.textContent = ch;
          span.classList.add("letter");
          span.classList.add("Emphasis");
          if (ch.trim().length === 0) span.classList.add("SpaceLetter");
          if (idx === letters.length - 1) span.classList.add("LastLetterInWord");
          var ls = startT + idx * letterDuration;
          span.style.setProperty("--gradient-position", "-20%");
          span.style.setProperty("--text-shadow-opacity", "0%");
          span.style.setProperty("--text-shadow-blur-radius", "4px");
          span.style.scale = String(IdleEmphasisLyricsScale);
          span.style.transform = "translateY(calc(" + fontVar + " * 0.02))";
          node.appendChild(span);
          letterObjs.push({ el: span, start: ls, end: ls + letterDuration, total: letterDuration });
        });
        node.classList.add("letterGroup");
        i === arr.length - 1 ? node.classList.add("LastWordInLine") : (s.part ? node.classList.add("PartOfWord") : null);
        node.style.setProperty("--text-shadow-opacity", "0%");
        node.style.setProperty("--text-shadow-blur-radius", "4px");
        node.style.scale = String(IdleEmphasisLyricsScale);
        node.style.transform = "translateY(calc(" + fontVar + " * 0.02))";
        lineObj.words.push({ el: node, start: startT, end: endT, total: endT - startT, letterGroup: true, letters: letterObjs, bg: !!isBg });
      } else {
        node = document.createElement("span");
        node.textContent = text;
        node.style.setProperty("--gradient-position", isBg ? "0%" : "-20%");
        node.style.setProperty("--text-shadow-opacity", "0%");
        node.style.setProperty("--text-shadow-blur-radius", "4px");
        node.style.scale = String(IdleLyricsScale);
        node.style.transform = "translateY(calc(" + fontVar + " * 0.01))";
        node.classList.add(isBg ? "bg-word" : "word");
        if (isBg) node.classList.add("word");
        i === arr.length - 1 ? node.classList.add("LastWordInLine") : (s.part ? node.classList.add("PartOfWord") : null);
        lineObj.words.push({ el: node, start: s.s, end: s.e, total: totalDuration, bg: !!isBg });
      }
      var prev = arr[i - 1];
      if (s.part || (prev && prev.part && group)) {
        if (!group) {
          group = document.createElement("span");
          group.classList.add("word-group");
          lineEl.appendChild(group);
        }
        group.appendChild(node);
        if (!s.part && prev && prev.part) group = null;
      } else {
        group = null;
        lineEl.appendChild(node);
      }
    });
  }

  function build(data) {
    lines = [];
    blurLast = null;
    virtual.innerHTML = "";
    var content = (data.lines || []).filter(function (l) { return l && (l.text || (l.words && l.words.length)); });
    lyricsType = data.type === "Line" ? "Line" : "Syllable";
    scrollContainer.setAttribute("data-lyrics-type", lyricsType);
    scrollContainer.classList.toggle("HasDuetLines", content.some(function (l) { return l.opposite; }));
    scrollContainer.classList.toggle("HasRtlLines", content.some(function (l) { return isRtl(l.text); }));

    if (content.length && content[0].start >= LYRICS_BETWEEN_SHOW_MS) {
      addDotLine(0, content[0].start, content[0].opposite, true);
    }

    content.forEach(function (line, index, arr) {
      var el = document.createElement("div");
      el.classList.add("line");
      var obj = { el: el, start: line.start, end: line.end, words: [], opposite: !!line.opposite };
      if (line.opposite) el.classList.add("OppositeAligned");
      if (isRtl(line.text) && !el.classList.contains("rtl")) el.classList.add("rtl");
      if (lyricsType === "Line") {
        el.textContent = stripZeroWidth(line.text);
        lines.push(obj);
      } else {
        lines.push(obj);
        var words = (line.words && line.words.length)
          ? line.words
          : [{ t: line.text, s: line.start, e: line.end, part: false }];
        buildWords(el, words, obj, false);
        (line.bg || []).forEach(function (bg) {
          var bel = document.createElement("div");
          bel.classList.add("line", "bg-line");
          var bobj = { el: bel, start: bg.start, end: bg.end, words: [], bgLine: true, opposite: !!line.opposite };
          if (line.opposite) bel.classList.add("OppositeAligned");
          var bt = (bg.words || []).map(function (w) { return w.t; }).join(" ");
          if (isRtl(bt)) bel.classList.add("rtl");
          lines.push(bobj);
          buildWords(bel, bg.words || [], bobj, true);
        });
      }
      var next = arr[index + 1];
      if (next && next.start - line.end >= LYRICS_BETWEEN_SHOW_MS) {
        addDotLine(line.end, next.start, next.opposite, false);
      }
    });

    lines.forEach(function (l) {
      var wrap = document.createElement("div");
      wrap.className = "LineWrap";
      wrap.appendChild(l.el);
      virtual.appendChild(wrap);
      l.wrap = wrap;
    });
    layoutDirty = true;
    glideBlockedUntil = nowMs() + 1500;
    scrollSnap = true;
    scrollTarget = null;
    scrollIdx = -1;
    observeLines();
  }

  /* ------------------------------------------------------------------------------------------
   * Layout (stands in for Spicy's virtualizer: absolutely positioned rows with a trailing gap)
   * ---------------------------------------------------------------------------------------- */
  var resizeObserver = typeof ResizeObserver === "function"
    ? new ResizeObserver(function () { layoutDirty = true; })
    : null;
  function observeLines() {
    if (!resizeObserver) return;
    resizeObserver.disconnect();
    lines.forEach(function (l) { resizeObserver.observe(l.el); });
    resizeObserver.observe(scrollEl);
  }

  function gapFor(i) {
    if (i >= lines.length - 1) return 0;
    var l = lines[i];
    if (l.dot && !l.el.classList.contains("Active")) return 0;
    var nextIsBg = lines[i + 1] && lines[i + 1].bgLine;
    return (nextIsBg ? GAP_LINE_TO_BG : GAP_NORMAL) * (scrollEl.clientWidth / 100);
  }

  function layout() {
    layoutDirty = false;
    var glide = nowMs() > glideBlockedUntil;

    // Read row heights before writing styles. Interleaving style writes and offsetHeight reads
    // forces WebView to synchronously recalculate layout repeatedly across the lyric list.
    var heights = new Array(lines.length);
    var gaps = new Array(lines.length);
    for (var m = 0; m < lines.length; m++) {
      var row = lines[m];
      var oldGap = parseFloat(row.pb || "0") || 0;
      // Preserve wrapper height contributions such as child margins, excluding the old gap.
      heights[m] = Math.max(0, row.wrap.offsetHeight - oldGap);
      gaps[m] = gapFor(m);
    }

    var y = 0;
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i];
      var gap = gaps[i];
      var pb = gap + "px";
      if (l.pb !== pb) { l.wrap.style.paddingBottom = pb; l.pb = pb; }
      var t = "translateY(" + Math.round(y) + "px)";
      if (l.t !== t) {
        l.wrap.style.transition = glide ? LAYOUT_GLIDE : "";
        l.wrap.style.transform = t;
        l.t = t;
      }
      l.start_y = y;
      y += heights[i] + gap;
    }
    virtual.style.height = y + "px";
  }

  /* ------------------------------------------------------------------------------------------
   * Scrolling (Spicy: critically damped 1 Hz spring, line centred plus a 30px offset)
   * ---------------------------------------------------------------------------------------- */
  function isBgLine(l) { return l.bgLine === true; }
  function resolveLead(i) { while (i > 0 && isBgLine(lines[i])) i--; return i; }

  function pickScrollLine(pos) {
    var active = [];
    for (var i = 0; i < lines.length; i++) {
      if (lines[i].start <= pos && lines[i].end >= pos) active.push(i);
    }
    if (!active.length) return -1;
    var front = -1;
    active.forEach(function (i) { var lead = resolveLead(i); if (lead > front) front = lead; });
    var leads = [];
    active.forEach(function (i) {
      var lead = resolveLead(i);
      if (isBgLine(lines[i]) && lead < front) return;
      if (leads[leads.length - 1] !== lead) leads.push(lead);
    });
    if (leads.length === 1) return leads[0];
    var top = leads[0];
    var groupEnd = lines[top].end;
    for (var j = top + 1; j < lines.length && isBgLine(lines[j]); j++) groupEnd = Math.max(groupEnd, lines[j].end);
    var remaining = 2, look = null;
    for (var k = top + 1; k < lines.length; k++) {
      if (isBgLine(lines[k])) continue;
      if (--remaining === 0) { look = lines[k]; break; }
    }
    if (look && groupEnd < look.start) return top;
    if (!look) return top;
    return leads[leads.length - 1];
  }

  function scrollTopFor(idx) {
    var l = lines[idx];
    if (!l || l.start_y === undefined) return null;
    var h = l.el.offsetHeight;
    var target = virtual.offsetTop + l.start_y + h / 2 - scrollEl.clientHeight / 2 + SCROLL_CENTER_OFFSET_PX;
    var max = Math.max(0, scrollEl.scrollHeight - scrollEl.clientHeight);
    return Math.min(Math.max(target, 0), max);
  }

  function markUserScroll() {
    var now = nowMs();
    if (!userScrolling) {
      userScrolling = true;
      if (window.Android && Android.onUserScroll) { try { Android.onUserScroll(true); } catch (e) { /* ignore */ } }
    }
    userScrollUntil = now + USER_SCROLL_HOLD_MS;
    idleFrames = 0;
    requestFrame();
  }
  ["wheel", "touchstart", "touchmove"].forEach(function (evt) {
    scrollEl.addEventListener(evt, markUserScroll, { passive: true });
  });
  scrollEl.addEventListener("touchend", markUserScroll, { passive: true });

  function autoScroll(pos, dt, now) {
    if (userScrolling) {
      if (now < userScrollUntil) return;
      userScrolling = false;
      if (window.Android && Android.onUserScroll) { try { Android.onUserScroll(false); } catch (e) { /* ignore */ } }
      scrollSpring.SetGoal(scrollEl.scrollTop, true);
      lastScrollWritten = scrollEl.scrollTop;
    }
    var idx = pickScrollLine(pos);
    if (idx === -1) {
      if (scrollIdx === -1) {
        if (!lines.length) return;
        idx = pos < lines[0].start ? 0 : lines.length - 1;
        if (pos >= lines[lines.length - 1].end) idx = lines.length - 1;
      } else {
        idx = scrollIdx;
      }
    }
    var top = scrollTopFor(idx);
    if (top === null) return;
    if (idx !== scrollIdx) { scrollIdx = idx; idleFrames = 0; }
    if (scrollSnap) {
      scrollSnap = false;
      scrollSpring.SetGoal(top, true);
      scrollEl.scrollTop = top;
      lastScrollWritten = scrollEl.scrollTop;
      return;
    }
    scrollSpring.SetGoal(top);
    var p = scrollSpring.Step(dt);
    if (Math.abs(p - top) < 0.25 && Math.abs(scrollSpring.v) < 0.05) { p = top; scrollSpring.SetGoal(top, true); }
    else idleFrames = 0;
    scrollEl.scrollTop = p;
    lastScrollWritten = p;
  }

  /* ------------------------------------------------------------------------------------------
   * Animation (src/utils/Lyrics/Animator/Lyrics/LyricsAnimator.ts)
   * ---------------------------------------------------------------------------------------- */
  function applyBlur(activeIndex, pos) {
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i];
      var st = stateOf(pos, l.start, l.end);
      var distance = Math.abs(i - activeIndex);
      var blur = distance === 0 ? 0 : Math.min(BlurMultiplier * distance, BLUR_MAX);
      var value = (st === "Active" || distance === 0) ? "0px" : blur + "px";
      styleStr(l, "--BlurAmount", value);
    }
  }

  function stepDot(w, pos, dt) {
    var st = stateOf(pos, w.start, w.end);
    var pct = progress(pos, w.start, w.end);
    if (!w.sp) {
      w.sp = createDotSprings();
    }
    var p = st === "Active" ? pct : (st === "NotSung" ? 0 : 1);
    var s = w.sp;
    s.Scale.SetGoal(DotScaleSpline.at(p));
    s.YOffset.SetGoal(DotYOffsetSpline.at(p));
    s.Glow.SetGoal(DotGlowSpline.at(p));
    s.Opacity.SetGoal(DotOpacitySpline.at(p));
    var scale = s.Scale.Step(dt), y = s.YOffset.Step(dt), glow = s.Glow.Step(dt), op = s.Opacity.Step(dt);
    translateY(w, y || 0);
    styleNum(w, "scale", scale, 0.001, String(scale));
    styleNum(w, "opacity", op, 0.001, String(op));
    styleNum(w, "--text-shadow-blur-radius", 4 + 6 * glow, 0.5, (4 + 6 * glow) + "px");
    styleNum(w, "--text-shadow-opacity", glow * 90, 1, (glow * 90) + "%");
  }

  function stepLetters(w, pos, dt, wordState) {
    var letters = w.letters;
    if (wordState === "Active") {
      var activeIdx = -1, activePct = 0;
      for (var i = 0; i < letters.length; i++) {
        if (stateOf(pos, letters[i].start, letters[i].end) === "Active") {
          activeIdx = i;
          activePct = progress(pos, letters[i].start, letters[i].end);
          break;
        }
      }
      // These spline values depend on the active letter, not on each letter in the word.
      // Calculate them once per frame instead of repeating the same spline work for every glyph.
      var baseScale = activeIdx !== -1 ? LetterScaleSpline.at(activePct) : 0;
      var baseY = activeIdx !== -1 ? LetterYOffsetSpline.at(activePct) : 0;
      var baseGlow = activeIdx !== -1 ? GlowSpline.at(activePct) : 0;
      var restScale = LetterScaleSpline.at(0);
      var restY = LetterYOffsetSpline.at(0);
      var restGlow = GlowSpline.at(0);
      for (var k = 0; k < letters.length; k++) {
        var letter = letters[k];
        if (!letter.sp) letter.sp = createLetterSprings();
        var tScale = restScale, tY = restY, tGlow = restGlow, tGrad;
        var ls = stateOf(pos, letter.start, letter.end);
        if (activeIdx !== -1) {
          var distance = Math.abs(k - activeIdx);
          var falloff = Math.max(0, 1 / (1 + Math.pow(distance, 2.8)));
          var glowFalloff = Math.max(0, 1 / (1 + distance * 0.9));
          tScale = restScale + (baseScale - restScale) * falloff;
          tY = restY + (baseY - restY) * falloff;
          tGlow = restGlow + (baseGlow - restGlow) * glowFalloff;
        }
        if (ls === "NotSung") {
          tScale = LetterScaleSpline.at(0); tY = LetterYOffsetSpline.at(0); tGlow = GlowSpline.at(0);
        } else if (ls === "Sung" && activeIdx === -1) {
          tGlow = GlowSpline.at(SungLetterGlow);
        }
        if (ls === "NotSung") tGrad = -20;
        else if (ls === "Sung") tGrad = 100;
        else tGrad = k === activeIdx ? -20 + 120 * easeSinOut(activePct) : -20;
        applyLetter(letter, dt, tScale, tY, tGlow, tGrad);
      }
    } else {
      var toSung = wordState === "Sung";
      var p = toSung ? 1 : 0;
      for (var m = 0; m < letters.length; m++) {
        var lt = letters[m];
        if (!lt.sp) lt.sp = createLetterSprings();
        applyLetter(lt, dt, LetterScaleSpline.at(p), LetterYOffsetSpline.at(p), GlowSpline.at(p), toSung ? 100 : -20);
      }
    }
  }

  function applyLetter(letter, dt, tScale, tY, tGlow, tGrad) {
    var s = letter.sp;
    s.Scale.SetGoal(tScale); s.YOffset.SetGoal(tY); s.Glow.SetGoal(tGlow);
    var scale = s.Scale.Step(dt), y = s.YOffset.Step(dt), glow = s.Glow.Step(dt);
    styleNum(letter, "--gradient-position", tGrad, 0.0001, tGrad + "%");
    translateY(letter, y * 2);
    styleNum(letter, "scale", scale, 0.001, String(scale));
    styleNum(letter, "--text-shadow-blur-radius", 4 + 12 * glow, 0.5, (4 + 12 * glow) + "px");
    styleNum(letter, "--text-shadow-opacity", glow * LetterGlowMultiplier_Opacity, 1, (glow * LetterGlowMultiplier_Opacity) + "%");
  }

  function stepWord(w, pos, dt) {
    if (w.dot) { stepDot(w, pos, dt); return; }
    var st = stateOf(pos, w.start, w.end);
    var pct = progress(pos, w.start, w.end);
    if (!w.sp) w.sp = createWordSprings();
    var p = st === "Active" ? pct : (st === "NotSung" ? 0 : 1);
    var s = w.sp;
    s.Scale.SetGoal(ScaleSpline.at(p));
    s.YOffset.SetGoal(YOffsetSpline.at(p));
    s.Glow.SetGoal(GlowSpline.at(p));
    var scale = s.Scale.Step(dt), y = s.YOffset.Step(dt), glow = s.Glow.Step(dt);
    styleNum(w, "scale", scale, 0.001, String(scale));
    translateY(w, y);
    if (!w.letterGroup) {
      var grad = st === "Active" ? -20 + 120 * pct : (st === "NotSung" ? -20 : 100);
      styleNum(w, "--gradient-position", grad, 0.0001, grad + "%");
      styleNum(w, "--text-shadow-blur-radius", 4 + 2 * glow, 0.5, (4 + 2 * glow) + "px");
      styleNum(w, "--text-shadow-opacity", Math.min(glow * 35, 100), 1, Math.min(glow * 35, 100) + "%");
    } else {
      stepLetters(w, pos, dt, st);
    }
  }

  function stepLineWords(l, pos, dt) {
    var sleeping = true;
    for (var i = 0; i < l.words.length; i++) {
      var w = l.words[i];
      var wordState = stateOf(pos, w.start, w.end);

      // Words outside their active timing window only need to animate until their springs settle.
      // Without this cache, every frame still stepped every word and letter in the active line,
      // even though their target styles had stopped changing.
      if (w.st !== wordState) {
        w.st = wordState;
        w.settled = false;
      }
      if (w.settled && wordState !== "Active") continue;

      stepWord(w, pos, dt);

      var wordSleeping = !w.sp || springsSleeping(w.sp);
      if (wordSleeping && w.letters) {
        for (var k = 0; k < w.letters.length; k++) {
          var letter = w.letters[k];
          if (letter.sp && !springsSleeping(letter.sp)) {
            wordSleeping = false;
            break;
          }
        }
      }
      w.settled = wordState !== "Active" && wordSleeping;
      if (!w.settled) sleeping = false;
    }
    return sleeping;
  }

  function stepLineType(l, st, pos, dt) {
    if (!l.sp) l.sp = { Glow: new Spring(LineGlowSpline.at(0), LineGlowFrequency, LineGlowDamping) };
    var pct = progress(pos, l.start, l.end);
    var tGlow, tGrad;
    if (st === "Active") { tGlow = LineGlowSpline.at(pct); tGrad = pct * 100; }
    else if (st === "NotSung") { tGlow = LineGlowSpline.at(0); tGrad = -20; }
    else { tGlow = LineGlowSpline.at(1); tGrad = 100; }
    l.sp.Glow.SetGoal(tGlow);
    var glow = l.sp.Glow.Step(dt);
    styleNum(l, "--gradient-position", tGrad, 0.0001, tGrad + "%");
    styleNum(l, "--text-shadow-blur-radius", 4 + 8 * glow, 0.5, (4 + 8 * glow) + "px");
    styleNum(l, "--text-shadow-opacity", glow * 50, 1, (glow * 50) + "%");
    return l.sp.Glow.CanSleep();
  }

  function animate(pos, dt) {
    var anyUnsung = false;
    var blurTarget = -1;
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i];
      var st = stateOf(pos, l.start, l.end);
      if (st !== "Sung") anyUnsung = true;
      var changed = l.st !== st;
      if (st === "Active") {
        if (blurTarget === -1 || !l.bgLine) blurTarget = i;
        if (changed) { setClass(l.el, "Active", true); setClass(l.el, "NotSung", false); setClass(l.el, "Sung", false); layoutDirty = true; }
        if (l.dot) setClass(l.el, "pre-hidden", pos > l.end - PRE_HIDDEN_DOT_LINE_MS);
        l.settled = false;
        if (l.dot || lyricsType === "Syllable") stepLineWords(l, pos, dt);
        else stepLineType(l, st, pos, dt);
      } else {
        if (changed) {
          if (st === "NotSung") {
            setClass(l.el, "NotSung", true); setClass(l.el, "Sung", false); setClass(l.el, "Active", false);
            if (l.dot) setClass(l.el, "pre-hidden", true);
          } else {
            setClass(l.el, "Sung", true); setClass(l.el, "Active", false); setClass(l.el, "NotSung", false);
            if (l.dot) setClass(l.el, "pre-hidden", false);
          }
          l.settled = false;
          layoutDirty = true;
        }
        if (!l.settled) {
          var sleeping;
          if (l.dot || lyricsType === "Syllable") sleeping = stepLineWords(l, pos, dt);
          else sleeping = stepLineType(l, st, pos, dt);
          if (sleeping) l.settled = true; else idleFrames = 0;
        }
      }
      l.st = st;
    }
    if (blurTarget !== -1 && blurLast !== blurTarget) {
      applyBlur(blurTarget, pos);
      blurLast = blurTarget;
    }
    setClass(scrollEl, "LinesAllSung", !anyUnsung && lines.length > 0);
  }

  /* ------------------------------------------------------------------------------------------
   * Frame loop
   * ---------------------------------------------------------------------------------------- */
  function requestFrame() {
    if (frameScheduled) return;
    frameScheduled = true;
    requestAnimationFrame(frame);
  }

  function frame(now) {
    frameScheduled = false;
    var dt = lastFrame ? Math.min((now - lastFrame) / 1000, 0.1) : 1 / 60;
    lastFrame = now;
    if (!lines.length) return;
    if (!anchor.playing && idleFrames > 120) return;
    idleFrames++;
    if (scrollEl.clientWidth !== lastWidth) {
      lastWidth = scrollEl.clientWidth;
      layoutDirty = true;
      glideBlockedUntil = now + 800;
      scrollSnap = true;
    }
    if (layoutDirty) layout();
    var pos = currentPos(now);
    animate(pos, dt);
    if (layoutDirty) layout();
    autoScroll(pos, dt, now);

    // Don't keep waking the WebView at display refresh rate once paused animations settle.
    // setAnchor/setLyrics, resize, and user interaction restart the loop when needed.
    if (anchor.playing || idleFrames <= 120) requestFrame();
  }

  // Tap a line to seek to its start.
  scrollEl.addEventListener("click", function (e) {
    var target = e.target;
    while (target && target !== scrollEl && !(target.classList && target.classList.contains("line"))) target = target.parentNode;
    if (!target || target === scrollEl || target.classList.contains("musical-line")) return;
    for (var i = 0; i < lines.length; i++) {
      if (lines[i].el === target) {
        if (window.Android && Android.onSeek) { try { Android.onSeek(Math.round(lines[i].start)); } catch (err) { /* ignore */ } }
        return;
      }
    }
  });

  /* ------------------------------------------------------------------------------------------
   * Public API
   * ---------------------------------------------------------------------------------------- */
  window.addEventListener("error", function (ev) { console.log("SpicyLyrics js error: " + ev.message + " @" + ev.lineno + ":" + ev.colno); });
  // Some WebViews resolve the percentage-height chain to 0 at load; pin the page to the real viewport.
  function pinViewport() {
    var h = window.innerHeight || document.documentElement.clientHeight || 0;
    var w = window.innerWidth || document.documentElement.clientWidth || 0;
    if (h > 0) { page.style.height = h + "px"; page.style.width = w + "px"; }
    return w + "x" + h;
  }
  window.addEventListener("resize", function () {
    pinViewport();
    layoutDirty = true;
    requestFrame();
  });
  pinViewport();
  window.SpicyLyrics = {
    setLyrics: function (data) {
      try {
        var vp = pinViewport();
        if (typeof data === "string") data = JSON.parse(data);
        build(data || { lines: [] });
        idleFrames = 0;
        lastFrame = 0;
        requestFrame();
        console.log("SpicyLyrics.setLyrics ok: lines=" + lines.length + " scroll=" + scrollEl.clientWidth + "x" + scrollEl.clientHeight +
          " content=" + (document.querySelector(".LyricsContent") || {}).clientHeight + " ua=" + navigator.userAgent);
        var dbg = document.getElementById("SpicyDebug");
        if (dbg) dbg.textContent = "vp=" + vp + " lines=" + lines.length + " pane=" + scrollEl.clientWidth + "x" + scrollEl.clientHeight + " " + (navigator.userAgent.match(/Chrome\/[\d.]+/) || [""])[0];
      } catch (e) {
        console.log("SpicyLyrics.setLyrics FAILED: " + e + " " + (e && e.stack));
        var d2 = document.getElementById("SpicyDebug");
        if (d2) { d2.style.color = "#f77"; d2.textContent = "setLyrics FAILED: " + e; }
      }
    },
    setAnchor: function (positionMs, isPlaying, speed) {
      anchor = { pos: positionMs, perf: nowMs(), playing: !!isPlaying, speed: speed || 1 };
      idleFrames = 0;
      requestFrame();
    },
    setFontSize: function (px) {
      if (px > 0) scrollEl.style.setProperty("--DefaultLyricsSize", px + "px");
      else scrollEl.style.removeProperty("--DefaultLyricsSize");
      layoutDirty = true;
      scrollSnap = true;
      idleFrames = 0;
      requestFrame();
    },
    setOffsetMs: function (ms) { offsetMs = ms || 0; idleFrames = 0; requestFrame(); },
    // Test hook: how many lines / which are active.
    _debug: function () { return { lines: lines.length, type: lyricsType, scrollIdx: scrollIdx }; }
  };
})();
