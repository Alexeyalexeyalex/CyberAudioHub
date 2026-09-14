const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = name => fs.readFileSync(path.join(__dirname,'../static/js/',name),'utf8');
test('shell leaves nested buttons and catalogue navigation to their handlers',()=>{
    const handlers={},navigated=[];
    const shared={setUser(){},state:{path:null}};
    const parent={CyberShell:{navigate:url=>{navigated.push(url);return true;},sync(){}},CyberPlayback:shared,document:{}};
    const window={parent,addEventListener(){}};
    const document={title:'Test',addEventListener:(name,fn,options)=>{handlers[name]=fn;if(name==='click')assert.notEqual(options,true);}};
    const context={window,parent,document,history:{pushState(){},replaceState(){}},location:{href:'http://localhost/'}};
    vm.runInNewContext(source('shell-bridge.js'),context);handlers.DOMContentLoaded();
    const link={target:'',href:'http://localhost/player',hasAttribute:()=>false,getAttribute:()=>'/player'};
    function event(button,prevented=false) {
        return {button:0,defaultPrevented:prevented,target:{closest:s=>s==='a[href]'?link:button},preventDefault(){this.defaultPrevented=true;}};
    }
    handlers.click(event({})); // Folder button inside the book link.
    handlers.click(event(null,true)); // Catalogue handled a folder locally.
    assert.equal(navigated.length,0);
    const normal=event(null);handlers.click(normal);assert.equal(navigated.length,1);assert.equal(normal.defaultPrevented,true);
    const modified=event(null);modified.ctrlKey=true;handlers.click(modified);assert.equal(navigated.length,1);
});
test('top player is visible only away from the playback page',()=>{
    const elements=new Map();
    const element=id=>{if(!elements.has(id))elements.set(id,{hidden:true,dataset:{src:'/player?_view=1'},setAttribute(){},getAttribute(){}});return elements.get(id);};
    const location={href:'http://localhost/player',origin:'http://localhost',pathname:'/player'};
    const navigate=(_,__,url)=>{const u=new URL(url,location.origin);location.href=u.href;location.pathname=u.pathname;};
    const player={state:{active:false,album:null,rate:1},audio:{paused:true},subscribe(fn){this.changed=fn;}};
    const handlers={},window={createCyberPlayback:()=>player,addEventListener:(k,v)=>handlers[k]=v};
    vm.runInNewContext(source('shell.js'),{window,document:{getElementById:element},history:{pushState:navigate,replaceState:navigate},location,URL});
    player.state.active=true;player.changed('state',player.state);assert.equal(element('global-player').hidden,true);
    window.CyberShell.navigate('/friends');assert.equal(element('global-player').hidden,false);
    window.CyberShell.navigate('/player?path=Book');assert.equal(element('global-player').hidden,true);
    window.CyberShell.navigate('/');player.state.active=false;player.changed('state',player.state);
    assert.equal(element('global-player').hidden,true);
});
