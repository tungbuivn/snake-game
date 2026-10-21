// Simple downloader with redirect follow, used because schannel TLS is blocked in this sandbox.
const fs = require('fs');
const path = require('path');
const https = require('https');
const http = require('http');

function get(url, redirects = 8) {
  return new Promise((resolve, reject) => {
    const lib = url.startsWith('https') ? https : http;
    const req = lib.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' } }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        const next = new URL(res.headers.location, url).toString();
        if (redirects <= 0) return reject(new Error('too many redirects'));
        return resolve(get(next, redirects - 1));
      }
      if (res.statusCode !== 200) {
        res.resume();
        return reject(new Error('HTTP ' + res.statusCode + ' for ' + url));
      }
      resolve(res);
    });
    req.on('error', reject);
    req.setTimeout(60000, () => req.destroy(new Error('timeout ' + url)));
  });
}

async function download(url, dest) {
  const res = await get(url);
  const total = parseInt(res.headers['content-length'] || '0', 10);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  const tmp = dest + '.part';
  let got = 0;
  let lastLog = 0;
  await new Promise((resolve, reject) => {
    const out = fs.createWriteStream(tmp);
    res.on('data', (chunk) => {
      got += chunk.length;
      if (Date.now() - lastLog > 1000) {
        lastLog = Date.now();
        const pct = total ? ((got / total) * 100).toFixed(1) + '%' : '';
        process.stdout.write(`\r${path.basename(dest)} ${(got / 1e6).toFixed(1)}MB ${pct}`);
      }
    });
    res.pipe(out);
    out.on('finish', () => out.close(resolve));
    out.on('error', reject);
    res.on('error', reject);
  });
  fs.renameSync(tmp, dest);
  process.stdout.write(`\r${path.basename(dest)} done ${(fs.statSync(dest).size / 1e6).toFixed(1)}MB\n`);
}

async function text(url) {
  const res = await get(url);
  return new Promise((resolve, reject) => {
    let buf = '';
    res.setEncoding('utf8');
    res.on('data', (c) => (buf += c));
    res.on('end', () => resolve(buf));
    res.on('error', reject);
  });
}

module.exports = { download, text, get };

if (require.main === module) {
  const [cmd, ...rest] = process.argv.slice(2);
  if (cmd === 'get') {
    download(rest[0], rest[1]).catch((e) => { console.error('FAIL', e.message); process.exit(1); });
  } else if (cmd === 'text') {
    text(rest[0]).then((t) => process.stdout.write(t)).catch((e) => { console.error('FAIL', e.message); process.exit(1); });
  }
}
