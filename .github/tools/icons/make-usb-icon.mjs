// Makes windows/starwire-usb.ico, the PC USB helper's icon, from starwire-usb.svg next to this
// script. Headless Microsoft Edge draws the SVG at each size, and this puts the PNGs into one .ico,
// as starwire-mic.ico holds them: 32-bit PNG images, smallest first.
//
//   node .github/tools/icons/make-usb-icon.mjs                     writes windows/starwire-usb.ico
//   node .github/tools/icons/make-usb-icon.mjs --preview OUT.png   and draws OUT.png: the small
//                                    sizes, as they are and 8 times bigger, on a dark and a light taskbar
//
// In the SVG, an element with data-min="32" is drawn only at 32 px and up, and one with
// data-max="24" only up to 24 px, so the small sizes get simpler shapes.
// Needs Node 18 or later and Microsoft Edge (set EDGE to its msedge.exe if it's somewhere else).
// Edge draws the pictures, so another version of Edge can make slightly different bytes.

import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const SIZES = [16, 20, 24, 32, 40, 48, 64, 256];
const here = dirname(fileURLToPath(import.meta.url));
const svgPath = join(here, 'starwire-usb.svg');
const icoPath = resolve(here, '../../../windows/starwire-usb.ico');
const edge = process.env.EDGE || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';

const args = process.argv.slice(2);
let preview = null;
if (args.length === 2 && args[0] === '--preview') {
    preview = resolve(args[1]);
} else if (args.length !== 0) {
    console.error('usage: node make-usb-icon.mjs [--preview OUT.png]');
    process.exit(2);
}

const work = mkdtempSync(join(tmpdir(), 'starwire-icon-'));

// Opens a page in headless Edge, with a profile of its own in the work folder
function edgeRun(page, ...extra) {
    const file = join(work, page.name);
    writeFileSync(file, page.html);
    return execFileSync(edge, ['--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
        '--hide-scrollbars', '--force-device-scale-factor=1', `--user-data-dir=${join(work, 'profile')}`,
        '--allow-file-access-from-files', ...extra, pathToFileURL(file).href],
        { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] });
}

// The page draws the SVG at each size into a canvas and lists the PNGs as JSON
const drawPage = `<!doctype html><meta charset="utf-8"><pre id="out"></pre><script>
const svg = ${JSON.stringify(readFileSync(svgPath, 'utf8'))};
async function draw(size) {
    const doc = new DOMParser().parseFromString(svg, 'image/svg+xml');
    for (const el of [...doc.querySelectorAll('[data-min], [data-max]')]) {
        const min = Number(el.getAttribute('data-min') || 0);
        const max = Number(el.getAttribute('data-max') || Infinity);
        if (size < min || size > max) el.remove();
    }
    doc.documentElement.setAttribute('width', size);
    doc.documentElement.setAttribute('height', size);
    const url = URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(doc)], { type: 'image/svg+xml' }));
    const img = new Image();
    img.src = url;
    await img.decode();
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = size;
    canvas.getContext('2d').drawImage(img, 0, 0, size, size);
    return canvas.toDataURL('image/png');
}
(async () => {
    const pngs = {};
    for (const size of ${JSON.stringify(SIZES)}) pngs[size] = await draw(size);
    document.getElementById('out').textContent = JSON.stringify(pngs);
})().catch(e => { document.getElementById('out').textContent = 'error: ' + e; });
</script>`;

try {
    const dom = edgeRun({ name: 'draw.html', html: drawPage }, '--virtual-time-budget=10000', '--dump-dom');
    const out = /<pre id="out">([^<]*)<\/pre>/.exec(dom);
    if (!out || !out[1].startsWith('{')) {
        throw new Error(`Edge didn't draw the icon: ${out ? out[1] : 'no output'}`);
    }
    const urls = JSON.parse(out[1]);
    const pngs = SIZES.map(size => Buffer.from(urls[size].replace(/^data:image\/png;base64,/, ''), 'base64'));

    // The .ico: a 6-byte header, a 16-byte entry for each image, then the PNGs
    const header = Buffer.alloc(6 + 16 * SIZES.length);
    header.writeUInt16LE(0, 0);
    header.writeUInt16LE(1, 2);
    header.writeUInt16LE(SIZES.length, 4);
    let offset = header.length;
    SIZES.forEach((size, i) => {
        const at = 6 + 16 * i;
        header[at] = header[at + 1] = size >= 256 ? 0 : size;
        header.writeUInt16LE(1, at + 4);
        header.writeUInt16LE(32, at + 6);
        header.writeUInt32LE(pngs[i].length, at + 8);
        header.writeUInt32LE(offset, at + 12);
        offset += pngs[i].length;
    });
    writeFileSync(icoPath, Buffer.concat([header, ...pngs]));
    console.log(`wrote ${icoPath}: ${SIZES.join(', ')} px, ${offset} bytes`);

    if (preview) {
        const row = (back, text) => `<div style="background:${back};color:${text};padding:12px">` +
            SIZES.filter(size => size <= 32).map(size => `<span style="display:inline-block;margin:0 12px;text-align:center">` +
                `<img src="${urls[size]}"> <img src="${urls[size]}" style="zoom:8;image-rendering:pixelated"><br>${size} px</span>`).join('') +
            '</div>';
        edgeRun({
            name: 'preview.html',
            html: `<!doctype html><body style="margin:0;font:12px Segoe UI,sans-serif">${row('#1c1c1c', '#ddd')}${row('#f3f3f3', '#222')}</body>`,
        }, `--screenshot=${preview}`, '--window-size=1000,620');
        console.log(`drew ${preview}`);
    }
} finally {
    rmSync(work, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
}
