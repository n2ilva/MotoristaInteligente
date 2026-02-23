const sharp = require('sharp');
const path = require('path');

const logo = path.join('app', 'src', 'main', 'res', 'drawable', 'logo.png');
const base = path.join('app', 'src', 'main', 'res');

const sizes = {
  'mipmap-mdpi': 48,
  'mipmap-hdpi': 72,
  'mipmap-xhdpi': 96,
  'mipmap-xxhdpi': 144,
  'mipmap-xxxhdpi': 192,
};

const fgSizes = {
  'mipmap-mdpi': 108,
  'mipmap-hdpi': 162,
  'mipmap-xhdpi': 216,
  'mipmap-xxhdpi': 324,
  'mipmap-xxxhdpi': 432,
};

async function generate() {
  for (const [folder, size] of Object.entries(sizes)) {
    const dir = path.join(base, folder);
    await sharp(logo)
      .resize(size, size, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
      .png()
      .toFile(path.join(dir, 'ic_launcher.png'));
    await sharp(logo)
      .resize(size, size, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
      .png()
      .toFile(path.join(dir, 'ic_launcher_round.png'));
    console.log('Generated ' + folder + ': ' + size + 'px');
  }
  
  for (const [folder, size] of Object.entries(fgSizes)) {
    const dir = path.join(base, folder);
    const logoSize = Math.round(size * 0.61);
    const padding = Math.round((size - logoSize) / 2);
    const resized = await sharp(logo)
      .resize(logoSize, logoSize, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
      .png()
      .toBuffer();
    await sharp({
      create: { width: size, height: size, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } }
    })
      .composite([{ input: resized, left: padding, top: padding }])
      .png()
      .toFile(path.join(dir, 'ic_launcher_foreground.png'));
    console.log('Generated foreground ' + folder + ': ' + size + 'px');
  }
  
  console.log('Done!');
}

generate().catch(e => console.error(e));
