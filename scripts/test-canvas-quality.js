const fs = require('node:fs');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const { extractFunction } = require('./test-webmode-overlay-layout.js');
const path = 'app/src/main/java/com/ashraf/novelstudio/';
const extractor = fs.readFileSync(path+'WebModeExtractor.kt','utf8');
const writer = fs.readFileSync(path+'WebModeWriter.kt','utf8');
const lib = extractor.match(/private const val LIB = """([\s\S]*?)"""/)[1];
new Function(lib); // Verify all discovery code still parses, not merely the new helper.
const hook = lib.slice(0,lib.indexOf('var BAD='));
function Context2D(canvas) { this.canvas=canvas; }
Context2D.prototype.drawImage=function() { return 'drawn'; };
Context2D.prototype.clearRect=function() {};
const window={CanvasRenderingContext2D:Context2D,pageYOffset:0,pageXOffset:0};
const sandbox={window,CanvasRenderingContext2D:Context2D,console,
  document:{body:{},documentElement:{scrollTop:0},contains:()=>true},
  de:{scrollTop:0},getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})};
vm.createContext(sandbox); vm.runInContext(hook,sandbox);
const canvas={tagName:'CANVAS',width:800,height:1200,parentElement:null,
  getBoundingClientRect:()=>({left:0,top:0,width:400,height:600})};
const ctx=new Context2D(canvas);
const stampA=window.__nsCanvasToken(canvas);
const layers=Array.from({length:4},()=>({style:{display:'block'}}));
canvas.__nsOverlayLayers=layers;
assert.equal(ctx.drawImage(),'drawn','original canvas method result is preserved');
assert.notEqual(window.__nsCanvasToken(canvas),stampA,'drawing changes the generation');
assert.ok(layers.every(x=>x.style.display==='none'),'text, fill, relocated card and cleaned image clear together');
const body=[...writer.matchAll(/private const val BUBBLES_PART_[12] = """([\s\S]*?)"""/g)].map(x=>x[1]).join('');
const functions=['function canvasSpec(s)','function canvasTops(cv,r)','function canvasScore(cv,spec)']
  .map(x=>extractFunction(body,x)).join('\n');
vm.runInContext("function norm(u){return (u||'').split('#')[0];}"+functions,sandbox);
sandbox.cv=canvas;
let old=[];
for(let page=1;page<=3;page++){
  const source='ns-canvas:0,0,400x600@'+window.__nsCanvasToken(canvas);
  sandbox.source=source;
  assert.ok(Number.isFinite(vm.runInContext('canvasScore(cv,canvasSpec(source))',sandbox)));
  for(const prior of old){sandbox.prior=prior;assert.equal(vm.runInContext('canvasScore(cv,canvasSpec(prior))',sandbox),Infinity);}
  old.push(source);ctx.clearRect();
}
vm.runInContext(extractFunction(body,'function findImg(s, index)'),sandbox);
const token=window.__nsCanvasToken(canvas);
window.__nsCanvasExports={exported:{el:canvas,token}};
assert.equal(vm.runInContext("findImg('exported',null)",sandbox),canvas);
ctx.drawImage();
assert.equal(vm.runInContext("findImg('exported',null)",sandbox),null,'stale readable canvas export cannot rebind');
const region=vm.runInContext("findImg('ns-region:10,20,100x80@123',null).getBoundingClientRect()",sandbox);
assert.equal(region.left,10);assert.equal(region.top,20);assert.equal(region.width,100);
window.pageYOffset=15;
assert.equal(vm.runInContext("findImg('ns-region:10,20,100x80@123',null).getBoundingClientRect().top",sandbox),5);
console.log('Canvas generation, three-page reuse, synchronous layer invalidation, stale exports and region anchors passed.');
