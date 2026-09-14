(() => {
    let shell, shared;
    try {shell=window.parent!==window && window.parent.CyberShell;shared=window.parent!==window && window.parent.CyberPlayback;} catch(_) { }
    window.CyberPlayback=shared || window.createCyberPlayback(document.getElementById('audio-player') || new Audio());
    document.addEventListener('DOMContentLoaded',()=>{
        window.addEventListener('cyberaudio:progress-reset',event=>{
            const player=window.CyberPlayback;
            if (event.detail?.path===player.state.path) player.stop(false);
        });
        if(window.CyberAuth) {
            CyberAuth.ready.then(()=>window.CyberPlayback.setUser(CyberAuth.user));
            CyberAuth.onChange(user=>window.CyberPlayback.setUser(user));
        }
        if(!shell)return;
        parent.document.title=document.title;
        shell.sync(location.href);
        document.addEventListener('click',event=>{
            // Let buttons inside cards and the catalogue's own folder navigation run first.
            if(event.defaultPrevented || event.button!==0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey)return;
            if(event.target.closest('button,input,select,textarea,[role="button"]'))return;
            const link=event.target.closest('a[href]');
            if(!link || link.hasAttribute('download') || (link.target && link.target!=='_self'))return;
            const raw=link.getAttribute('href');if(raw.startsWith('#'))return;
            if(shell.navigate(link.href)) event.preventDefault();
        });
        for(const method of ['pushState','replaceState']) {
            const original=history[method].bind(history);
            history[method]=(...args)=>{const result=original(...args);shell.sync(location.href);return result;};
        }
    });
})();
