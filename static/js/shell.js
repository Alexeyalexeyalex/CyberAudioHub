(() => {
    const frame=document.getElementById('site-frame'), bar=document.getElementById('global-player');
    const player=window.CyberPlayback=window.createCyberPlayback(document.getElementById('persistent-audio'));
    const routes=new Set(['/','/player','/friends','/achievements','/stats','/admin']);
    function clean(raw) {const url=new URL(raw,location.href);url.searchParams.delete('_view');return url;}
    const publicPath=url=>url.pathname+url.search+url.hash;
    const updateVisibility=()=>{bar.hidden=!player.state.active || location.pathname==='/player';};
    window.CyberShell={
        navigate(raw,replace=false) {
            const url=clean(raw); if(url.origin!==location.origin || !routes.has(url.pathname)) return false;
            history[replace?'replaceState':'pushState']({},'',publicPath(url));
            updateVisibility();
            url.searchParams.set('_view','1'); frame.src=publicPath(url); return true;
        },
        sync(raw) {const url=clean(raw);if(url.origin===location.origin){history.replaceState({},'',publicPath(url));updateVisibility();}}
    };
    window.addEventListener('popstate',()=>{
        updateVisibility();
        const url=clean(location.href);url.searchParams.set('_view','1');frame.src=publicPath(url);
    });
    player.subscribe((type,state)=>{
        updateVisibility();
        if(state.album) {
            document.getElementById('global-title').textContent=state.album.title;
            document.getElementById('global-chapter').textContent=state.album.tracks[state.index]?.name || '';
            const cover=document.getElementById('global-cover');
            const wanted=state.album.cover || '/static/assets/default_cover.png';
            if(cover.getAttribute('src')!==wanted)cover.src=wanted;
        }
        const play=document.getElementById('global-play');play.textContent=player.audio.paused?'▶':'Ⅱ';
        play.setAttribute('aria-label',player.audio.paused?'Воспроизвести':'Пауза');
        document.getElementById('global-speed').value=String(state.rate);
        if(type?.awards)type.awards.forEach(item=>frame.contentWindow.CyberAchievements?.show(item));
    });
    document.getElementById('global-play').onclick=()=>player.toggle();
    document.getElementById('global-rewind').onclick=()=>player.seek(-5);
    document.getElementById('global-forward').onclick=()=>player.seek(5);
    document.getElementById('global-speed').onchange=e=>player.setRate(e.target.value);
    document.getElementById('global-close').onclick=()=>player.stop();
    document.getElementById('global-open').onclick=()=>{
        if(player.state.path)window.CyberShell.navigate('/player?path='+encodeURIComponent(player.state.path));
    };
    frame.src=frame.dataset.src;
})();
