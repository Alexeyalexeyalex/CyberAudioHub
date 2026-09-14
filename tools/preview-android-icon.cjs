// Local preview of the actual VectorDrawables; not an Android screenshot.
// Requires xml-js and @napi-rs/canvas (can be supplied through NODE_PATH).
const fs = require('fs');
const path = require('path');
const { xml2js } = require('xml-js');
const { createCanvas, Path2D, loadImage } = require('@napi-rs/canvas');
const root = path.resolve(__dirname, '..');
const drawable = path.join(root, 'application/android/app/src/main/res/drawable');
const attr = (node, key, fallback) => node.attributes?.[`android:${key}`] ?? fallback;
const children = node => (node.elements || []).filter(item => item.type === 'element');

function paint(ctx, node, kind) {
  const complex = children(node).find(item => item.name === 'aapt:attr' && item.attributes.name === `android:${kind}Color`);
  if (complex) {
    const gradient = children(complex)[0];
    if (attr(gradient, 'type') !== 'linear') throw new Error('Unsupported gradient');
    const result = ctx.createLinearGradient(+attr(gradient, 'startX'), +attr(gradient, 'startY'), +attr(gradient, 'endX'), +attr(gradient, 'endY'));
    for (const stop of children(gradient)) result.addColorStop(+attr(stop, 'offset'), attr(stop, 'color'));
    return result;
  }
  const value = attr(node, `${kind}Color`, '#00000000');
  // Android is #AARRGGBB, CSS is #RRGGBBAA.
  return value.length === 9 ? `#${value.slice(3)}${value.slice(1, 3)}` : value;
}

function draw(ctx, node) {
  ctx.save();
  if (node.name === 'group') {
    ctx.translate(+attr(node, 'translateX', 0), +attr(node, 'translateY', 0));
    ctx.scale(+attr(node, 'scaleX', 1), +attr(node, 'scaleY', 1));
    for (const child of children(node)) draw(ctx, child);
  } else if (node.name === 'path') {
    const shape = new Path2D(attr(node, 'pathData'));
    ctx.fillStyle = paint(ctx, node, 'fill');
    ctx.globalAlpha = +attr(node, 'fillAlpha', 1);
    ctx.fill(shape);
    if (+attr(node, 'strokeWidth', 0)) {
      ctx.strokeStyle = paint(ctx, node, 'stroke');
      ctx.globalAlpha = +attr(node, 'strokeAlpha', 1);
      ctx.lineWidth = +attr(node, 'strokeWidth');
      ctx.lineCap = attr(node, 'strokeLineCap', 'butt');
      ctx.lineJoin = attr(node, 'strokeLineJoin', 'miter');
      ctx.stroke(shape);
    }
  } else throw new Error(`Unsupported vector element: ${node.name}`);
  ctx.restore();
}

function vector(ctx, filename) {
  const document = xml2js(fs.readFileSync(path.join(drawable, filename), 'utf8'));
  const vectorNode = document.elements.find(item => item.name === 'vector');
  if (+attr(vectorNode, 'viewportWidth') !== 108) throw new Error('Expected 108dp icon');
  for (const child of children(vectorNode)) draw(ctx, child);
}

async function main() {
  const canvas = createCanvas(960, 280);
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#171320';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(await loadImage(path.join(root, 'static/assets/favicon.svg')), 30, 20, 200, 200);
  const labels = ['Website', 'Android / round', 'Android / rounded', 'Android / themed'];
  for (let i = 1; i < 4; i++) {
    ctx.save();
    ctx.translate(30 + i * 230, 20);
    ctx.scale(200 / 72, 200 / 72);
    ctx.beginPath();
    if (i === 1) ctx.arc(36, 36, 36, 0, 2 * Math.PI);
    else ctx.roundRect(0, 0, 72, 72, 20);
    ctx.clip();
    ctx.translate(-18, -18);
    if (i === 3) {
      ctx.fillStyle = '#695878';
      ctx.fillRect(0, 0, 108, 108);
    } else vector(ctx, 'ic_launcher_background.xml');
    vector(ctx, i === 3 ? 'ic_launcher_monochrome.xml' : 'ic_launcher_foreground.xml');
    ctx.restore();
  }
  ctx.fillStyle = '#d9cbe6';
  ctx.font = '16px sans-serif';
  labels.forEach((label, i) => ctx.fillText(label, 30 + i * 230, 253));
  const output = path.join(root, 'application/android/app/build/qa/icon-preview.png');
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, canvas.toBuffer('image/png'));
  console.log(output);
}
main().catch(error => { console.error(error); process.exitCode = 1; });
