const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../static/js/playback-core.js'), 'utf8');
class Audio extends EventTarget {
    paused = true; currentTime = 0; duration = 120; playbackRate = 1; _src = '';
    set src(value) { this._src = value; this.currentTime = 0; }
    get src() { return this._src; }
    getAttribute() { return this.src || null; }
    removeAttribute() { this.src = ''; }
    play() { this.paused = false; this.dispatchEvent(new Event('play')); return Promise.resolve(); }
    pause() { if (!this.paused) {this.paused = true; this.dispatchEvent(new Event('pause'));} }
    load() { this.dispatchEvent(new Event('emptied')); }
}
function setup() {
    const calls = [], saved = new Map();
    const context = {window:{addEventListener(){}}, navigator:{}, URL, location:{origin:'http://localhost'}, console,
        fetch:async (url, options)=>{calls.push({url, body:JSON.parse(options.body)}); return {ok:true,json:async()=>({granted:[]})};},
        localStorage:{getItem:k=>saved.get(k),setItem:(k,v)=>saved.set(k,v)}};
    vm.runInNewContext(source, context);
    const audio = new Audio(), player = context.window.createCyberPlayback(audio);
    const book = {title:'Book',cover:'/cover.png',tracks:[{name:'One',url:'/one.mp3'},{name:'Two',url:'/two.mp3'}]};
    player.select('Book', book, 0);
    return {player,audio,book,calls,saved};
}
test('revisiting the same book keeps the audio and position', async()=>{
    const {player,audio,book}=setup(); await player.play(); audio.currentTime=37;
    assert.equal(player.select('Book',book,0),false);
    assert.equal(audio.currentTime,37); assert.equal(audio.paused,false);
});
test('exact supported speeds, default one, speed survives chapter changes',()=>{
    const {player,audio}=setup(); assert.equal(audio.playbackRate,1);
    for (const rate of [1,1.25,1.5,2,2.5,3]) {player.setRate(rate);assert.equal(audio.playbackRate,rate);}
    player.setRate(10); assert.equal(audio.playbackRate,3);
    player.next(); assert.equal(audio.playbackRate,3); assert.equal(audio.src,'/two.mp3');
});
test('close stops, unloads and hides the player', async()=>{
    const {player,audio,saved}=setup();await player.play();audio.currentTime=42;player.stop();
    assert.equal(audio.paused,true); assert.equal(audio.src,'');assert.equal(player.state.active,false);
    assert.equal(JSON.parse(saved.get('cyberAudioProgress')).Book.position,42);
});
test('resetting history never writes the removed progress back', async()=>{
    const {player,audio,calls}=setup();player.setUser({id:1});await player.play();audio.currentTime=42;
    player.stop(false);assert.equal(calls.length,0);assert.equal(audio.paused,true);
});
test('progress is saved even with no page subscribers', async()=>{
    const {player,audio,calls}=setup();player.setUser({id:1});await player.play();audio.currentTime=40;
    audio.dispatchEvent(new Event('timeupdate'));
    assert.equal(calls[0].url,'/api/progress');assert.equal(calls[0].body.position,40);
});
test('automatic advance ends at final chapter, seek clamps to boundaries', async()=>{
    const {player,audio}=setup();await player.play();audio.dispatchEvent(new Event('ended'));
    assert.equal(player.state.index,1);audio.dispatchEvent(new Event('ended'));assert.equal(player.state.index,1);
    player.seek(-5);assert.equal(audio.currentTime,0);player.seek(500);assert.equal(audio.currentTime,120);
});
