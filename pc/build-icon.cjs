const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const pngToIco = require('png-to-ico');

(async () => {
  const png = await sharp(path.join(__dirname, 'icon.svg')).resize(256, 256).png().toBuffer();
  const ico = await pngToIco([png]);
  fs.writeFileSync(path.join(__dirname, 'icon.ico'), ico);
  console.log('Created Windows icon from icon.svg');
})().catch(error => {
  console.error('Could not build app icon:', error);
  process.exitCode = 1;
});
