const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../static/js/recommendation.js'), 'utf8');

function element() {
    const handlers = {}, classes = new Set();
    return {hidden: true, attrs: {}, firstElementChild: {},
        classList: {add: name => classes.add(name), contains: name => classes.has(name)},
        setAttribute(name, value) { this.attrs[name] = value; },
        addEventListener(name, handler) { handlers[name] = handler; },
        fire(name, value) { handlers[name]?.(value); }};
}

async function fixture({reduced = false, book = {path:'Папка/Книга', name:'Книга', cover:'/cover.png'}, failed = false} = {}) {
    const ids = Object.fromEntries(['book-recommendation', 'recommendation-link',
        'recommendation-cover', 'recommendation-caption', 'recommendation-pause'].map(id => [id, element()]));
    const document = element(), window = element(), media = element();
    document.getElementById = id => ids[id];
    document.hidden = false;
    media.matches = reduced;
    let now = 0, id = 0;
    const timers = new Map(), frames = new Map(), animations = [];
    ids['recommendation-link'].animate = (keyframes, options) => {
        const animation = {keyframes, options, playbackRate:1, paused:false,
            pause() { this.paused = true; }, play() { this.paused = false; },
            cancel() { this.cancelled = true; }};
        animations.push(animation);
        return animation;
    };
    vm.runInNewContext(source, {document, window, matchMedia: () => media,
        performance: {now: () => now},
        fetch: async () => ({ok:!failed, json:async () => ({book})}),
        setTimeout(fn, delay) { timers.set(++id, {at:now + delay, fn}); return id; },
        clearTimeout(key) { timers.delete(key); },
        requestAnimationFrame(fn) { frames.set(++id, fn); return id; },
        cancelAnimationFrame(key) { frames.delete(key); }});
    document.fire('DOMContentLoaded');
    await new Promise(resolve => setImmediate(resolve));
    return {ids, document, window, media, animations,
        imageLoaded() { ids['recommendation-cover'].onload?.(); },
        advance(ms) {
            const target = now + ms;
            while (true) {
                const due = [...timers.entries()].filter(([,t]) => t.at <= target)
                    .sort((a,b) => a[1].at - b[1].at)[0];
                if (!due) break;
                now = due[1].at; timers.delete(due[0]); due[1].fn();
            }
            now = target;
            const batch = [...frames.values()]; frames.clear();
            batch.forEach(fn => fn(now));
        }};
}

test('static for one second, then an accessible book link', async () => {
    const f = await fixture(); f.imageLoaded();
    f.advance(999);
    assert.equal(f.ids['recommendation-link'].href, undefined);
    assert.equal(f.ids['recommendation-cover'].hidden, true);
    f.advance(1);
    assert.equal(f.ids['recommendation-link'].href, '/player?path=' + encodeURIComponent('Папка/Книга'));
    assert.equal(f.ids['recommendation-link'].attrs['aria-label'], 'Открыть книгу «Книга»');
    assert.equal(f.ids['recommendation-cover'].hidden, false);
    assert.equal(f.animations.length, 0);
});

test('needle settles before one continuous animation accelerates', async () => {
    const f = await fixture(); f.imageLoaded(); f.advance(1000); f.advance(1999);
    assert.equal(f.animations.length, 0);
    f.advance(1);
    const spin = f.animations[0];
    assert.equal(spin.keyframes[0].transform, 'rotate(-16deg)');
    assert.equal(spin.playbackRate, 0);
    f.advance(900); assert.equal(spin.playbackRate, .5);
    f.advance(900); assert.equal(spin.playbackRate, 1);
    assert.equal(f.animations.length, 1);
    f.ids['recommendation-pause'].fire('click'); assert.equal(spin.paused, true);
    f.ids['recommendation-pause'].fire('click'); assert.equal(spin.paused, false);
});

test('reduced motion retains the recommendation without rotation', async () => {
    const f = await fixture({reduced:true}); f.imageLoaded(); f.advance(10000);
    assert.equal(f.ids['recommendation-cover'].hidden, false);
    assert.equal(f.ids['recommendation-pause'].hidden, true);
    assert.equal(f.animations.length, 0);
});

test('empty or unavailable catalogue keeps the initial record', async () => {
    for (const settings of [{book:null}, {failed:true}]) {
        const f = await fixture(settings); f.imageLoaded(); f.advance(10000);
        assert.equal(f.ids['recommendation-link'].href, undefined);
        assert.equal(f.animations.length, 0);
    }
});

test('leaving the page cancels the delayed motor', async () => {
    const f = await fixture(); f.imageLoaded(); f.advance(1000);
    f.window.fire('pagehide', {persisted:false}); f.advance(10000);
    assert.equal(f.animations.length, 0);
});

test('a cover loaded after one second has no extra reveal delay', async () => {
    const f = await fixture(); f.advance(1500); f.imageLoaded(); f.advance(0);
    assert.equal(f.ids['recommendation-cover'].hidden, false);
    assert.equal(f.ids['book-recommendation'].classList.contains('has-recommendation'), true);
});

test('the one-second delay is measured from page load, not image load', async () => {
    const f = await fixture(); f.advance(400); f.imageLoaded(); f.advance(599);
    assert.equal(f.ids['recommendation-cover'].hidden, true);
    f.advance(1);
    assert.equal(f.ids['recommendation-cover'].hidden, false);
});
