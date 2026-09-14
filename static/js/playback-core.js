/* Audio belongs to the outer page, not to the document currently being browsed. */
(() => {
    window.createCyberPlayback = audio => {
        const rates = [1, 1.25, 1.5, 2, 2.5, 3];
        const state = {path:null, album:null, index:0, rate:1, active:false, user:null};
        const listeners = new Set(), claimed = new Set();
        let lastSave = 0;
        const emit = type => listeners.forEach(fn => { try { fn(type,state); } catch(e) { console.error(e); } });
        const json = (url, body) => fetch(url,{method:'PUT',credentials:'same-origin',keepalive:true,
            headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}).catch(() => {});
        function save() {
            if (!state.path || !state.album || !audio.currentTime) return;
            const entry = {trackIndex:state.index,position:audio.currentTime};
            try {
                const saved = JSON.parse(localStorage.getItem('cyberAudioProgress') || '{}');
                saved[state.path] = entry; localStorage.setItem('cyberAudioProgress',JSON.stringify(saved));
            } catch (_) { }
            if (state.user) json('/api/progress',{path:state.path,title:state.album.title,cover:state.album.cover,
                track_index:state.index,position:audio.currentTime,
                finished:state.index === state.album.tracks.length-1 && audio.duration-audio.currentTime <= 10});
        }
        function metadata() {
            if (!('mediaSession' in navigator) || !state.album) return;
            navigator.mediaSession.metadata = new MediaMetadata({
                title:state.album.tracks[state.index]?.name || state.album.title,
                artist:state.album.title,album:state.album.title,
                artwork:[{src:new URL(state.album.cover || '/static/assets/default_cover.png',location.origin).href}]});
        }
        const player = {
            audio, state, rates,
            subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); },
            setUser(user) { state.user = user; },
            save,
            select(path,album,index) {
                index = Math.max(0,Math.min(index,album.tracks.length-1));
                if (state.path===path && state.index===index && audio.getAttribute('src')) return false;
                save(); audio.pause(); state.path=path; state.album=album; state.index=index;
                audio.src=album.tracks[index].url; audio.playbackRate=state.rate;
                metadata(); emit('track'); return true;
            },
            play() { if (!state.album) return; return audio.play().catch(() => emit('state')); },
            pause() { audio.pause(); },
            toggle() { audio.paused ? player.play() : player.pause(); },
            seek(delta) { if (Number.isFinite(audio.duration)) audio.currentTime=Math.max(0,Math.min(audio.duration,audio.currentTime+delta)); },
            next() {
                if (!state.album) return;
                player.select(state.path,state.album,(state.index+1)%state.album.tracks.length); player.play();
            },
            previous() {
                if (!state.album) return;
                if (audio.currentTime>3) audio.currentTime=0;
                else { player.select(state.path,state.album,Math.max(0,state.index-1)); player.play(); }
            },
            setRate(rate) { if (rates.includes(Number(rate))) {state.rate=Number(rate);audio.playbackRate=state.rate;emit('state');} },
            stop(remember=true) {
                if (remember) save();
                state.active=false; state.path=null; state.album=null;
                audio.pause(); audio.removeAttribute('src'); audio.load();
                if ('mediaSession' in navigator) navigator.mediaSession.metadata=null;
                emit('state');
            }
        };
        for (const name of ['play','playing','pause','seeked','loadedmetadata','ratechange','error'])
            audio.addEventListener(name,() => {
                if (name==='play') state.active=true;
                if (name==='pause') save();
                if ('mediaSession' in navigator) navigator.mediaSession.playbackState=audio.paused?'paused':'playing';
                emit('state');
            });
        audio.addEventListener('timeupdate',() => {
            if (Date.now()-lastSave>5000) {lastSave=Date.now();save();}
            if (state.user && state.path && audio.duration-audio.currentTime<=10) {
                const key=state.user.id+':'+state.path+':'+state.index;
                if (!claimed.has(key)) {
                    claimed.add(key);
                    fetch('/api/achievements/claim',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},
                        body:JSON.stringify({path:state.path,track:state.index})}).then(r => {
                            if (!r.ok) throw Error(); return r.json();
                        }).then(data => emit({awards:data.granted || []})).catch(() => claimed.delete(key));
                }
            }
            emit('state');
        });
        audio.addEventListener('ended',() => {
            save(); if (state.album && state.index+1<state.album.tracks.length) player.next(); else emit('state');
        });
        if ('mediaSession' in navigator) {
            const actions={play:()=>player.play(),pause:()=>player.pause(),previoustrack:()=>player.previous(),nexttrack:()=>player.next(),
                seekbackward:()=>player.seek(-5),seekforward:()=>player.seek(5),seekto:e=>{audio.currentTime=e.seekTime;},stop:()=>player.stop()};
            Object.entries(actions).forEach(([name,fn])=>{try {navigator.mediaSession.setActionHandler(name,fn);} catch (_) {}});
        }
        window.addEventListener('pagehide',save);
        return player;
    };
})();
