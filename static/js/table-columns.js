/* Column borders resize the two neighbours without changing the table width. */
(() => {
    const key = table => 'cah:columns:' + table.id;
    function setup(table) {
        const cells = [...(table.tHead?.rows[0]?.cells || [])];
        if (cells.length < 2 || cells.some(c => c.colSpan !== 1) || table.querySelector('colgroup[data-resizable]')) return;
        let widths = cells.map(c => c.getBoundingClientRect().width);
        if (!widths.every(w => w > 0)) return;
        const total = widths.reduce((a,b) => a+b, 0);
        try {
            const saved = JSON.parse(localStorage.getItem(key(table)));
            if (Array.isArray(saved) && saved.length === widths.length && saved.every(w => Number.isFinite(w) && w > 0))
                widths = saved.map(w => w * total);
        } catch (_) { }
        const group = document.createElement('colgroup'); group.dataset.resizable = '';
        const columns = widths.map(width => {
            const col = document.createElement('col'); col.style.width = width + 'px'; group.append(col); return col;
        });
        table.prepend(group); table.classList.add('is-resizable');
        table.style.width = total + 'px';
        function resize(index, delta, initial = widths) {
            const minimum = Math.min(64, (initial[index] + initial[index+1]) / 2);
            const movement = Math.max(minimum-initial[index], Math.min(delta, initial[index+1]-minimum));
            widths[index] = initial[index] + movement;
            widths[index+1] = initial[index+1] - movement;
            columns[index].style.width = widths[index] + 'px';
            columns[index+1].style.width = widths[index+1] + 'px';
        }
        function save() { try { localStorage.setItem(key(table), JSON.stringify(widths.map(w => w/total))); } catch (_) { } }
        cells.slice(0,-1).forEach((cell,index) => {
            cell.querySelector('.column-resizer')?.remove();
            const grip = document.createElement('span'); grip.className = 'column-resizer';
            grip.tabIndex = 0; grip.setAttribute('role','separator'); grip.setAttribute('aria-orientation','vertical');
            grip.setAttribute('aria-label','Изменить ширину столбца «' + cell.textContent.trim() + '»');
            grip.setAttribute('aria-valuemin','64'); grip.setAttribute('aria-valuenow',String(Math.round(widths[index])));
            grip.addEventListener('pointerdown', event => {
                if (event.button !== 0) return;
                event.preventDefault(); event.stopPropagation();
                grip.setPointerCapture(event.pointerId);
                const start = event.clientX, initial = widths.slice();
                document.body.classList.add('is-resizing-columns');
                const move = e => { resize(index,e.clientX-start,initial); grip.setAttribute('aria-valuenow',String(Math.round(widths[index]))); };
                const end = () => {
                    grip.removeEventListener('pointermove',move);
                    grip.removeEventListener('pointerup',end); grip.removeEventListener('pointercancel',end);
                    document.body.classList.remove('is-resizing-columns'); save();
                };
                grip.addEventListener('pointermove',move); grip.addEventListener('pointerup',end); grip.addEventListener('pointercancel',end);
            });
            grip.addEventListener('keydown', event => {
                if (!['ArrowLeft','ArrowRight'].includes(event.key)) return;
                event.preventDefault(); resize(index,event.key==='ArrowRight'?16:-16,widths.slice()); save();
                grip.setAttribute('aria-valuenow',String(Math.round(widths[index])));
            });
            cell.append(grip);
        });
    }
    let scheduled = false;
    const scan = () => {
        if (scheduled) return; scheduled = true;
        requestAnimationFrame(() => { scheduled = false; document.querySelectorAll('.admin-table[id]').forEach(setup); });
    };
    document.addEventListener('DOMContentLoaded', () => {
        if (!document.querySelector('.admin-table')) return;
        new MutationObserver(scan).observe(document.querySelector('main'), {childList:true,subtree:true,attributes:true,attributeFilter:['hidden']}); scan();
    });
})();
