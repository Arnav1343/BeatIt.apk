/* ═══ BeatIt iPod — app.js ═══ */
(function () {
    'use strict';

    /* ── refs ── */
    const $ = id => document.getElementById(id);
    const audio = $('audioPlayer');
    const wheel = $('clickWheel');
    const wheelHighlight = $('wheelHighlight');

    /* ── state ── */
    let currentView = 'menu';
    let menuIndex = 0, libIndex = 0;
    let library = [];
    let currentTrackIdx = -1;
    let shuffleOn = false, repeatOn = false;
    let selectedCodec = 'mp3', selectedQuality = 192;
    let selectedResult = null; // { url, title, uploader, duration }
    let toastTimer = null;
    let wheelAngle = 0; // tracks rotation for highlight ring

    /* ── theme ── */
    const themes = ['default', 'charcoal', 'neon', 'purple', 'deeppurple'];
    let themeIdx = 0;

    /* ── vibration helper ── */
    function vib(ms) { if (navigator.vibrate) navigator.vibrate(ms || 15); }

    /* ── tick sound (Web Audio API) ── */
    let audioCtx = null;
    function playTick() {
        try {
            if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
            const osc = audioCtx.createOscillator();
            const gain = audioCtx.createGain();
            osc.connect(gain);
            gain.connect(audioCtx.destination);
            osc.frequency.value = 1200;
            osc.type = 'sine';
            gain.gain.setValueAtTime(0.03, audioCtx.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + 0.012);
            osc.start(audioCtx.currentTime);
            osc.stop(audioCtx.currentTime + 0.012);
        } catch (_) { }
    }

    /* ── scroll acceleration ── */
    let lastScrollTime = 0;
    let scrollVelocity = 0;
    const BASE_SCROLL_INTERVAL = 200; // ms — minimum time between effective scrolls

    function getScrollDelay() {
        const now = Date.now();
        const delta = now - lastScrollTime;
        lastScrollTime = now;
        // Faster rotation = shorter delay
        if (delta < 80) scrollVelocity = Math.min(scrollVelocity + 0.3, 3);
        else if (delta < 150) scrollVelocity = Math.max(scrollVelocity - 0.1, 1);
        else scrollVelocity = 1;
        return true; // always allow scroll, velocity affects skip count
    }

    function getSkipCount() {
        return Math.max(1, Math.floor(scrollVelocity));
    }

    /* ── wheel rotation visual feedback ── */
    let spinTimeout = null;
    function animateWheelTick(dir) {
        // Rotate highlight ring
        wheelAngle += dir * 15;
        if (wheelHighlight) {
            wheelHighlight.style.transform = `rotate(${wheelAngle}deg)`;
        }
        // Show highlight
        wheel.classList.add('spinning');
        clearTimeout(spinTimeout);
        spinTimeout = setTimeout(() => wheel.classList.remove('spinning'), 300);

        // Micro bounce
        wheel.classList.add('tick');
        setTimeout(() => wheel.classList.remove('tick'), 50);
    }

    /* ── scroll handler ── */
    function handleScroll(dir) {
        // Haptic
        if (navigator.vibrate) navigator.vibrate(12);
        // Tick sound
        playTick();
        // Visual
        animateWheelTick(dir);
        // Acceleration
        getScrollDelay();
        const skip = getSkipCount();

        if (currentView === 'menu') {
            for (let i = 0; i < skip; i++) dir > 0 ? menuDown() : menuUp();
        } else if (currentView === 'library') {
            for (let i = 0; i < skip; i++) dir > 0 ? libDown() : libUp();
        } else if (currentView === 'nowplaying') {
            audio.volume = Math.max(0, Math.min(1, audio.volume + dir * -0.05));
            showToast('Vol: ' + Math.round(audio.volume * 100) + '%');
        }
    }

    /* ────────── TOUCH WHEEL ────────── */
    let touchStartAngle = null, lastAngle = null, touchAccum = 0;
    const TOUCH_THRESHOLD = 12; // degrees per tick

    function getAngle(e, rect) {
        const cx = rect.left + rect.width / 2, cy = rect.top + rect.height / 2;
        const x = (e.touches ? e.touches[0].clientX : e.clientX) - cx;
        const y = (e.touches ? e.touches[0].clientY : e.clientY) - cy;
        return Math.atan2(y, x) * 180 / Math.PI;
    }

    function isOnRing(e, rect) {
        const cx = rect.left + rect.width / 2, cy = rect.top + rect.height / 2;
        const x = (e.touches ? e.touches[0].clientX : e.clientX) - cx;
        const y = (e.touches ? e.touches[0].clientY : e.clientY) - cy;
        const dist = Math.sqrt(x * x + y * y);
        const outerR = rect.width / 2;
        const innerR = outerR * 0.38; // center button is ~38% of wheel
        return dist > innerR && dist < outerR;
    }

    wheel.addEventListener('touchstart', e => {
        // If touch is on a button (MENU, Play, Prev, Next, Select), let it through
        const target = e.target.closest('button');
        if (target) return; // don't intercept button taps

        const rect = wheel.getBoundingClientRect();
        if (!isOnRing(e, rect)) return;
        touchStartAngle = getAngle(e, rect);
        lastAngle = touchStartAngle;
        touchAccum = 0;
        e.preventDefault();
    }, { passive: false });

    wheel.addEventListener('touchmove', e => {
        if (lastAngle === null) return;
        const rect = wheel.getBoundingClientRect();
        const angle = getAngle(e, rect);
        let delta = angle - lastAngle;
        if (delta > 180) delta -= 360;
        if (delta < -180) delta += 360;
        touchAccum += delta;
        lastAngle = angle;

        while (touchAccum > TOUCH_THRESHOLD) {
            handleScroll(1);
            touchAccum -= TOUCH_THRESHOLD;
        }
        while (touchAccum < -TOUCH_THRESHOLD) {
            handleScroll(-1);
            touchAccum += TOUCH_THRESHOLD;
        }
        e.preventDefault();
    }, { passive: false });

    wheel.addEventListener('touchend', () => { touchStartAngle = null; lastAngle = null; });

    /* Mouse wheel */
    wheel.addEventListener('wheel', e => {
        e.preventDefault();
        handleScroll(e.deltaY > 0 ? 1 : -1);
    }, { passive: false });

    /* ────────── NAVIGATION ────────── */
    function showView(name) {
        document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
        $(name === 'menu' ? 'viewMenu' : name === 'search' ? 'viewSearch' :
            name === 'library' ? 'viewLibrary' : 'viewNowPlaying').classList.add('active');
        currentView = name;
        if (name === 'library') loadLibrary();
        if (name === 'search') setTimeout(() => $('searchInput').focus(), 100);
    }

    /* ── menu ── */
    const menuItems = document.querySelectorAll('.menu-item');

    function updateMenuSelection() {
        menuItems.forEach((el, i) => {
            el.classList.toggle('selected', i === menuIndex);
        });
    }

    function menuDown() { menuIndex = Math.min(menuIndex + 1, menuItems.length - 1); updateMenuSelection(); }
    function menuUp() { menuIndex = Math.max(menuIndex - 1, 0); updateMenuSelection(); }
    function menuSelect() {
        const action = menuItems[menuIndex].dataset.action;
        showView(action);
    }

    menuItems.forEach((el, i) => {
        el.addEventListener('click', () => { vib(20); menuIndex = i; updateMenuSelection(); menuSelect(); });
    });

    /* ── buttons (all with vibration) ── */
    $('btnMenu').addEventListener('click', () => {
        vib(20);
        if (currentView !== 'menu') showView('menu');
    });

    $('btnSelect').addEventListener('click', () => {
        vib(25);
        if (currentView === 'menu') menuSelect();
        else if (currentView === 'library') playSelected();
        else if (currentView === 'nowplaying') togglePlay();
    });

    $('btnPlay').addEventListener('click', () => { vib(20); togglePlay(); });
    $('btnPrev').addEventListener('click', () => { vib(15); prevTrack(); });
    $('btnNext').addEventListener('click', () => { vib(15); nextTrack(); });

    /* ────────── SEARCH ────────── */
    let searchDebounce = null;
    let sugIndex = -1;

    $('searchInput').addEventListener('input', () => {
        const q = $('searchInput').value.trim();
        clearTimeout(searchDebounce);
        if (q.length < 2) {
            $('suggestionsDropdown').classList.add('hidden');
            return;
        }
        searchDebounce = setTimeout(() => fetchSuggestions(q), 300);
    });

    $('searchInput').addEventListener('keydown', e => {
        if (e.key === 'Enter') {
            e.preventDefault();
            const items = document.querySelectorAll('.suggestion-item');
            if (sugIndex >= 0 && items[sugIndex]) {
                items[sugIndex].click();
            } else if ($('searchInput').value.trim().length >= 2) {
                doSearch($('searchInput').value.trim());
            }
        } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            const items = document.querySelectorAll('.suggestion-item');
            if (items.length === 0) return;
            sugIndex += e.key === 'ArrowDown' ? 1 : -1;
            sugIndex = Math.max(-1, Math.min(sugIndex, items.length - 1));
            items.forEach((el, i) => el.classList.toggle('selected', i === sugIndex));
        }
    });

    async function fetchSuggestions(q) {
        try {
            const r = await fetch('/api/suggestions', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ query: q })
            });
            const data = await r.json();
            if (data.error || !Array.isArray(data)) {
                $('suggestionsDropdown').classList.add('hidden');
                return;
            }
            renderSuggestions(data);
        } catch (_) {
            $('suggestionsDropdown').classList.add('hidden');
        }
    }

    function renderSuggestions(items) {
        const dd = $('suggestionsDropdown');
        sugIndex = -1;
        if (!items.length) { dd.classList.add('hidden'); return; }
        dd.innerHTML = items.map((item, i) => `
      <div class="suggestion-item" data-idx="${i}">
        <div class="suggestion-info">
          <div class="suggestion-title">${esc(item.title)}</div>
          <div class="suggestion-artist">${esc(item.uploader)}</div>
        </div>
        <div class="suggestion-dur">${fmtDur(item.duration)}</div>
      </div>`).join('');
        dd.classList.remove('hidden');

        dd.querySelectorAll('.suggestion-item').forEach((el, i) => {
            el.addEventListener('click', () => {
                selectSuggestion(items[i]);
                dd.classList.add('hidden');
            });
        });
    }

    function selectSuggestion(item) {
        selectedResult = item;
        $('searchInput').value = item.title;
        $('resultTitle').textContent = item.title;
        $('resultMeta').textContent = `${item.uploader} · ${fmtDur(item.duration)}`;
        $('searchResult').classList.remove('hidden');
        $('searchStatus').textContent = '';
        updateQualityOptions();

        // Pre-fetch stream URL in background
        prefetchStream(item.url);
    }

    async function doSearch(q) {
        $('searchStatus').textContent = 'Searching…';
        $('searchStatus').className = 'search-status';
        $('searchResult').classList.add('hidden');
        $('suggestionsDropdown').classList.add('hidden');
        try {
            const r = await fetch('/api/search', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ query: q })
            });
            const data = await r.json();
            if (data.error) throw new Error(data.error);
            selectSuggestion(data);
        } catch (e) {
            $('searchStatus').textContent = e.message;
            $('searchStatus').className = 'search-status error-text';
        }
    }

    /* ── prefetch ── */
    function prefetchStream(url) {
        fetch('/api/prefetch', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ url })
        }).catch(() => { /* silent */ });
    }

    /* ── quality options ── */
    function updateQualityOptions() {
        const opts = selectedCodec === 'opus'
            ? [{ q: 64, name: '64k', detail: '~1.5 MB' }, { q: 128, name: '128k', detail: '~3 MB', dflt: true }, { q: 256, name: '256k', detail: '~6 MB' }]
            : [{ q: 128, name: '128k', detail: '~3 MB' }, { q: 192, name: '192k', detail: '~4.5 MB', dflt: true }, { q: 320, name: '320k', detail: '~7.5 MB' }];

        selectedQuality = opts.find(o => o.dflt)?.q || opts[0].q;
        $('qualityOptions').innerHTML = opts.map(o =>
            `<button class="quality-opt${o.dflt ? ' active' : ''}" data-q="${o.q}">
        <span class="q-name">${o.name}</span>
        <span class="q-detail">${o.detail}</span>
      </button>`).join('');

        $('qualityOptions').querySelectorAll('.quality-opt').forEach(btn => {
            btn.addEventListener('click', () => {
                $('qualityOptions').querySelectorAll('.quality-opt').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                selectedQuality = parseInt(btn.dataset.q);
            });
        });
    }

    document.querySelectorAll('.fmt-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.fmt-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            selectedCodec = btn.dataset.codec;
            updateQualityOptions();
        });
    });

    updateQualityOptions();

    /* ── download ── */
    $('downloadBtn').addEventListener('click', () => { vib(30); startDownload(); });

    async function startDownload() {
        if (!selectedResult) return;
        $('downloadBtn').disabled = true;
        $('downloadProgressWrap').classList.remove('hidden');
        $('downloadProgressFill').style.width = '0%';
        $('downloadProgressText').textContent = 'Fetching stream…';
        $('downloadStatus').textContent = '';

        try {
            const r = await fetch('/api/download', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    url: selectedResult.url,
                    title: selectedResult.title,
                    quality: selectedQuality,
                    codec: selectedCodec
                })
            });
            const data = await r.json();
            if (data.error) throw new Error(data.error);
            pollProgress(data.task_id);
        } catch (e) {
            $('downloadStatus').textContent = e.message;
            $('downloadStatus').className = 'download-status error';
            $('downloadBtn').disabled = false;
            $('downloadProgressWrap').classList.add('hidden');
        }
    }

    function pollProgress(taskId) {
        const iv = setInterval(async () => {
            try {
                const r = await fetch(`/api/progress/${taskId}`);
                const d = await r.json();

                if (d.status === 'extracting') {
                    $('downloadProgressFill').style.width = '0%';
                    $('downloadProgressText').textContent = 'Fetching stream…';
                } else if (d.status === 'downloading') {
                    $('downloadProgressFill').style.width = d.percent + '%';
                    $('downloadProgressText').textContent = d.percent + '%';
                } else if (d.status === 'done') {
                    clearInterval(iv);
                    $('downloadProgressFill').style.width = '100%';
                    $('downloadProgressText').textContent = '100%';
                    $('downloadStatus').textContent = '✓ ' + (d.result?.size_human || 'Done');
                    $('downloadStatus').className = 'download-status success';
                    $('downloadBtn').disabled = false;
                    showToast('Downloaded!', 'success');
                    loadLibrary();
                } else if (d.status === 'error') {
                    clearInterval(iv);
                    $('downloadStatus').textContent = d.error || 'Download failed';
                    $('downloadStatus').className = 'download-status error';
                    $('downloadBtn').disabled = false;
                    $('downloadProgressWrap').classList.add('hidden');
                }
            } catch (_) { }
        }, 500);
    }

    /* ────────── LIBRARY ────────── */
    async function loadLibrary() {
        try {
            const r = await fetch('/api/library');
            const data = await r.json();
            library = Array.isArray(data) ? data : [];
            renderLibrary();
            $('libraryBadge').textContent = library.length || '';
        } catch (_) { }
    }

    function renderLibrary() {
        const list = $('libraryList');
        if (!library.length) {
            list.innerHTML = '<div class="library-empty">No songs yet.<br>Search & download!</div>';
            return;
        }
        list.innerHTML = library.map((s, i) => {
            const isPlaying = currentTrackIdx === i && !audio.paused;
            const isPaused = currentTrackIdx === i && audio.paused;
            const sel = i === libIndex ? ' selected' : '';
            return `<div class="library-item${isPlaying ? ' playing' : ''}${sel}" data-idx="${i}">
        <div class="lib-icon">${isPlaying ? eqBars(false) : isPaused ? eqBars(true) : '♪'}</div>
        <div class="lib-info">
          <div class="lib-title">${esc(s.title)}</div>
          <div class="lib-meta"><span>${s.size_human}</span><span>${s.codec.toUpperCase()}</span></div>
        </div>
        <button class="lib-delete" data-fn="${esc(s.filename)}" title="Delete">✕</button>
      </div>`;
        }).join('');

        list.querySelectorAll('.library-item').forEach(el => {
            el.addEventListener('click', e => {
                if (e.target.closest('.lib-delete')) return;
                libIndex = parseInt(el.dataset.idx);
                updateLibSelection();
            });
            el.addEventListener('dblclick', () => {
                libIndex = parseInt(el.dataset.idx);
                playSelected();
            });
        });

        list.querySelectorAll('.lib-delete').forEach(btn => {
            btn.addEventListener('click', e => {
                e.stopPropagation();
                deleteSong(btn.dataset.fn);
            });
        });
    }

    function updateLibSelection() {
        document.querySelectorAll('.library-item').forEach((el, i) => {
            el.classList.toggle('selected', i === libIndex);
        });
    }

    function libDown() { libIndex = Math.min(libIndex + 1, library.length - 1); updateLibSelection(); scrollLibIntoView(); }
    function libUp() { libIndex = Math.max(libIndex - 1, 0); updateLibSelection(); scrollLibIntoView(); }

    function scrollLibIntoView() {
        const items = document.querySelectorAll('.library-item');
        if (items[libIndex]) items[libIndex].scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }

    function playSelected() {
        if (!library.length || libIndex < 0) return;
        currentTrackIdx = libIndex;
        const song = library[currentTrackIdx];
        audio.src = `/api/music/${encodeURIComponent(song.filename)}`;
        audio.play();
        updateNowPlaying();
        showView('nowplaying');
    }

    async function deleteSong(filename) {
        try {
            await fetch('/api/delete', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ filename })
            });
            showToast('Deleted');
            loadLibrary();
        } catch (_) { }
    }

    /* ────────── NOW PLAYING ────────── */
    function updateNowPlaying() {
        if (currentTrackIdx < 0 || !library[currentTrackIdx]) return;
        const s = library[currentTrackIdx];
        $('npTitle').textContent = s.title;
        $('npArtist').textContent = s.codec.toUpperCase() + ' · ' + s.size_human;
        $('npTrackNum').textContent = `${currentTrackIdx + 1} of ${library.length}`;
        renderLibrary();
    }

    function togglePlay() {
        if (audio.src && audio.src !== window.location.href) {
            audio.paused ? audio.play() : audio.pause();
            renderLibrary();
        }
    }

    function prevTrack() {
        if (!library.length) return;
        if (audio.currentTime > 3) { audio.currentTime = 0; return; }
        currentTrackIdx = (currentTrackIdx - 1 + library.length) % library.length;
        libIndex = currentTrackIdx;
        playSelected();
    }

    function nextTrack() {
        if (!library.length) return;
        if (shuffleOn) {
            currentTrackIdx = Math.floor(Math.random() * library.length);
        } else {
            currentTrackIdx = (currentTrackIdx + 1) % library.length;
        }
        libIndex = currentTrackIdx;
        playSelected();
    }

    audio.addEventListener('ended', () => {
        if (repeatOn) { audio.currentTime = 0; audio.play(); }
        else nextTrack();
    });

    audio.addEventListener('timeupdate', () => {
        if (!audio.duration) return;
        const pct = (audio.currentTime / audio.duration * 100).toFixed(1);
        $('npProgressFill').style.width = pct + '%';
        $('npProgressKnob').style.left = pct + '%';
        $('npTimeElapsed').textContent = fmtDur(Math.floor(audio.currentTime));
        $('npTimeTotal').textContent = '-' + fmtDur(Math.floor(audio.duration - audio.currentTime));
    });

    // Seek by clicking progress bar
    $('npProgressBar').addEventListener('click', e => {
        if (!audio.duration) return;
        const rect = $('npProgressBar').getBoundingClientRect();
        const pct = (e.clientX - rect.left) / rect.width;
        audio.currentTime = pct * audio.duration;
    });

    // Shuffle / Repeat
    $('btnShuffle').addEventListener('click', () => {
        vib(15);
        shuffleOn = !shuffleOn;
        $('btnShuffle').classList.toggle('active', shuffleOn);
        showToast(shuffleOn ? 'Shuffle ON' : 'Shuffle OFF');
    });

    $('btnRepeat').addEventListener('click', () => {
        vib(15);
        repeatOn = !repeatOn;
        $('btnRepeat').classList.toggle('active', repeatOn);
        showToast(repeatOn ? 'Repeat ON' : 'Repeat OFF');
    });

    /* ────────── THEMES ────────── */
    $('themeCycle').addEventListener('click', () => {
        themeIdx = (themeIdx + 1) % themes.length;
        applyTheme();
        showToast(themes[themeIdx].charAt(0).toUpperCase() + themes[themeIdx].slice(1));
    });

    $('themeToggle').addEventListener('click', () => {
        // Legacy toggle — just cycle
        themeIdx = (themeIdx + 1) % themes.length;
        applyTheme();
    });

    function applyTheme() {
        if (themes[themeIdx] === 'default') {
            document.documentElement.removeAttribute('data-theme');
        } else {
            document.documentElement.setAttribute('data-theme', themes[themeIdx]);
        }
        localStorage.setItem('beatit-theme', themes[themeIdx]);
    }

    // Restore saved theme
    const saved = localStorage.getItem('beatit-theme');
    if (saved) {
        themeIdx = themes.indexOf(saved);
        if (themeIdx < 0) themeIdx = 0;
        applyTheme();
    }

    /* ────────── HELPERS ────────── */
    function showToast(msg, type) {
        const t = $('toast');
        t.textContent = msg;
        t.className = 'toast' + (type ? ' toast-' + type : '');
        clearTimeout(toastTimer);
        requestAnimationFrame(() => { t.classList.add('visible'); });
        toastTimer = setTimeout(() => { t.classList.remove('visible'); }, 1500);
    }

    function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }

    function fmtDur(s) {
        if (!s || s < 0) return '0:00';
        const m = Math.floor(s / 60);
        return m + ':' + String(Math.floor(s % 60)).padStart(2, '0');
    }

    function eqBars(paused) {
        return `<div class="lib-eq${paused ? ' paused' : ''}"><span></span><span></span><span></span></div>`;
    }

    /* ── keyboard shortcuts ── */
    document.addEventListener('keydown', e => {
        if (e.target.tagName === 'INPUT') return;
        if (e.key === 'ArrowUp') { e.preventDefault(); handleScroll(-1); }
        else if (e.key === 'ArrowDown') { e.preventDefault(); handleScroll(1); }
        else if (e.key === 'Enter') $('btnSelect').click();
        else if (e.key === 'Escape' || e.key === 'Backspace') $('btnMenu').click();
        else if (e.key === 'ArrowLeft') prevTrack();
        else if (e.key === 'ArrowRight') nextTrack();
        else if (e.key === ' ') { e.preventDefault(); togglePlay(); }
    });

    /* ── init ── */
    loadLibrary();
    updateMenuSelection();
})();
