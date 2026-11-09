// TV Mirror / 視訊：信令伺服器（只用 Node 內建模組）
// 本機測試：node server.js            → http://localhost:8787
// 正式主機：PORT=8790 TURN_SECRET=… PUBLIC_HOST=<你的網域> node server.js（前面由反向代理負責 HTTPS）
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.env.PORT) || 8787;
const PUBLIC = path.join(__dirname, 'public');
const TURN_SECRET = process.env.TURN_SECRET || '';
const TURN_HOST = process.env.TURN_HOST || process.env.PUBLIC_HOST || 'localhost';
const TURN_PORT = Number(process.env.TURN_PORT) || 3478;
const CODE_TTL_MS = 10 * 60 * 1000;
const ROLES = ['mac', 'tv', 'join', 'mic'];
// 參加者 ID：電視 'tv'、客廳麥克風 'mic'、每支手機 'j…'（多方通話用）
const validPeer = (id) => typeof id === 'string' && /^[A-Za-z0-9_-]{2,64}$/.test(id);

// ---- 房間：每個房間各有一條 SSE 連線與佇列 ----
// rooms[roomId] = { clients: {role: res}, queues: {role: []}, code, codeExpires, joined }
const rooms = new Map();
const codes = new Map(); // code -> { roomId, expires }（舊的一次性配對碼，保留給 /rooms）

// 固定配對碼：存檔，伺服器重啟後仍有效
const DATA_DIR = path.join(__dirname, 'data');
const PAIRS_FILE = path.join(DATA_DIR, 'pairs.json');
let pairs = {};
try { pairs = JSON.parse(fs.readFileSync(PAIRS_FILE, 'utf8')); } catch (e) { pairs = {}; }
function savePairs() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  fs.writeFileSync(PAIRS_FILE, JSON.stringify(pairs), { mode: 0o600 });
}

// 錯誤嘗試限制：一分鐘內超過 20 次錯誤就暫停
let failures = [];
function tooManyFailures() {
  const now = Date.now();
  failures = failures.filter((t) => now - t < 60000);
  return failures.length >= 20;
}

function getRoom(id) {
  if (!rooms.has(id)) rooms.set(id, { clients: {}, queues: {}, joined: false });
  return rooms.get(id);
}

function deliver(roomId, role, msg) {
  const room = getRoom(roomId);
  const c = room.clients[role];
  if (c) c.write(`data: ${JSON.stringify(msg)}\n\n`);
  else (room.queues[role] = room.queues[role] || []).push(msg);
}

function newCode() {
  let code;
  do { code = String(crypto.randomInt(100000, 1000000)); } while (codes.has(code));
  return code;
}

// 每 60 秒清掉過期配對碼
setInterval(() => {
  const now = Date.now();
  for (const [code, info] of codes) if (info.expires < now) codes.delete(code);
}, 60000).unref();

// ---- TURN 臨時憑證（coturn use-auth-secret 格式）----
function turnCredentials() {
  const ttl = 3600;
  const username = `${Math.floor(Date.now() / 1000) + ttl}:tvmirror`;
  const credential = crypto.createHmac('sha1', TURN_SECRET).update(username).digest('base64');
  return {
    iceServers: [
      { urls: [`stun:${TURN_HOST}:${TURN_PORT}`] },
      {
        urls: [
          `turn:${TURN_HOST}:${TURN_PORT}?transport=udp`,
          `turn:${TURN_HOST}:${TURN_PORT}?transport=tcp`,
        ],
        username,
        credential,
      },
    ],
  };
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
};

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => {
      body += chunk;
      if (body.length > 1e6) reject(new Error('payload too large'));
    });
    req.on('end', () => resolve(body));
    req.on('error', reject);
  });
}

function json(res, status, obj) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(obj));
}

const requestHandler = async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const room = url.searchParams.get('room') || 'default';

  // TURN 憑證
  if (req.method === 'GET' && url.pathname === '/turn') {
    if (!TURN_SECRET) return json(res, 503, { error: 'TURN not configured' });
    return json(res, 200, turnCredentials());
  }

  // 視訊房間：電視建立房間，取得 6 位數配對碼（10 分鐘內有效）
  if (req.method === 'POST' && url.pathname === '/rooms') {
    const id = crypto.randomUUID();
    const code = newCode();
    codes.set(code, { roomId: id, expires: Date.now() + CODE_TTL_MS });
    getRoom(id).code = code;
    return json(res, 201, { roomId: id, code, expiresInSec: CODE_TTL_MS / 1000 });
  }

  // 電視登記固定配對碼（6 位數）→ 房間 ID；電視每次載入頁面都會重新登記，保持有效
  if (req.method === 'POST' && url.pathname === '/register-code') {
    try {
      const { code, roomId } = JSON.parse(await readBody(req));
      if (!/^\d{6}$/.test(String(code)) || typeof roomId !== 'string' || roomId.length < 8 || roomId.length > 64) {
        return json(res, 400, { error: 'bad request' });
      }
      // 同一個房間只保留最新的配對碼，舊碼立刻失效
      for (const k of Object.keys(pairs)) if (pairs[k] === roomId) delete pairs[k];
      pairs[String(code)] = roomId;
      savePairs();
      res.writeHead(204).end();
    } catch (e) {
      return json(res, 400, { error: 'bad request' });
    }
    return;
  }

  // 老婆輸入配對碼 → 房間 ID（可重複使用；錯誤次數過多會暫停）
  if (req.method === 'POST' && url.pathname === '/join-code') {
    if (tooManyFailures()) return json(res, 429, { error: '嘗試次數過多，請一分鐘後再試' });
    try {
      const { code } = JSON.parse(await readBody(req));
      const roomId = pairs[String(code)];
      if (!roomId) {
        failures.push(Date.now());
        return json(res, 404, { error: '配對碼不正確' });
      }
      return json(res, 200, { roomId });
    } catch (e) {
      return json(res, 400, { error: 'bad request' });
    }
  }

  // 信令：SSE 訂閱
  if (req.method === 'GET' && url.pathname === '/events') {
    const role = url.searchParams.get('role');
    if (!validPeer(role)) return res.writeHead(400).end('bad role');
    const r = getRoom(room);

    if (r.clients[role]) r.clients[role].end(); // 同角色重連：舊連線先關
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    res.write(': connected\n\n');
    r.clients[role] = res;

    while (r.queues[role] && r.queues[role].length) deliver(room, role, r.queues[role].shift());

    const ping = setInterval(() => res.write(': ping\n\n'), 15000);
    req.on('close', () => {
      clearInterval(ping);
      if (r.clients[role] === res) r.clients[role] = null;
    });
    return;
  }

  // 信令：送出訊息給對方角色
  if (req.method === 'POST' && url.pathname === '/send') {
    try {
      const msg = JSON.parse(await readBody(req));
      if (!validPeer(msg.to)) return res.writeHead(400).end('bad target');
      deliver(room, msg.to, { from: msg.from, type: msg.type, data: msg.data });
      res.writeHead(204).end();
    } catch (e) {
      res.writeHead(400).end(String(e.message));
    }
    return;
  }

  // 靜態檔案
  const routes = {
    '/': 'index.html', '/mac': 'mac.html', '/tv': 'tv.html',
    '/call-tv': 'call-tv.html', '/call-join': 'call-join.html', '/mic': 'mic.html',
  };
  const file = routes[url.pathname] || url.pathname.slice(1);
  const full = path.normalize(path.join(PUBLIC, file));
  if (!full.startsWith(PUBLIC + path.sep)) return res.writeHead(403).end();

  fs.readFile(full, (err, buf) => {
    if (err) return res.writeHead(404).end('not found');
    res.writeHead(200, { 'Content-Type': MIME[path.extname(full)] || 'application/octet-stream' });
    res.end(buf);
  });
};

// 有 certs/ 且未設定 BEHIND_PROXY 才走本機 HTTPS（區網自簽憑證）；正式主機由 Caddy 提供 HTTPS
const CERT_DIR = path.join(__dirname, 'certs');
const useLocalTls = !process.env.BEHIND_PROXY
  && fs.existsSync(path.join(CERT_DIR, 'cert.pem')) && fs.existsSync(path.join(CERT_DIR, 'key.pem'));

const server = useLocalTls
  ? https.createServer({
      cert: fs.readFileSync(path.join(CERT_DIR, 'cert.pem')),
      key: fs.readFileSync(path.join(CERT_DIR, 'key.pem')),
    }, requestHandler)
  : http.createServer(requestHandler);

const scheme = useLocalTls ? 'https' : 'http';
server.listen(PORT, '0.0.0.0', () => {
  console.log(`TV Mirror / 視訊 已啟動（${scheme}，埠 ${PORT}）`);
  if (!TURN_SECRET) console.log('提醒：未設定 TURN_SECRET，/turn 會回 503');
});
