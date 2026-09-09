// Derived PNGs retain the old asset URLs and work in Android BitmapFactory.
// Run with Node.js and sharp available in NODE_PATH (or installed normally).
const path = require('path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '..');
async function main() {
  const assets = path.join(root, 'static/assets');
  await sharp(path.join(assets, 'favicon.svg')).resize(128, 128).png().toFile(path.join(assets, 'favicon.png'));
  await sharp(path.join(assets, 'default_cover.svg')).resize(800, 800).png().toFile(path.join(assets, 'default_cover.png'));
  await sharp(path.join(assets, 'default_cover.svg')).resize(400, 400).png().toFile(path.join(root, 'application/android/app/src/main/res/drawable/default_cover.png'));
}
main().catch(error => { console.error(error); process.exitCode = 1; });
