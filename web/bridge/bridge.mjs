// ÆTHER local bridge: WebSocket <-> TCP frame relay.
//
// Browsers can't open raw TCP sockets, so the ÆTHER browser app reaches mesh nodes through this
// relay running on the operator's own machine. Each WebSocket binary message is exactly one ÆTHER
// frame; on the TCP side frames are `len:u16be | bytes`, the same as ÆTHER Mobile MIL™.
//
// The relay only ever sees Noise handshake messages and ciphertext: the handshake and every
// message are end-to-end encrypted between the browser and the peer.
//
// Safety limits: listens on 127.0.0.1 only, accepts browser pages from an origin allowlist,
// and dials only private-network or loopback addresses, so it can't be used as an open proxy.
//
//   node bridge.mjs                  # ws://127.0.0.1:7340
//   AETHER_BRIDGE_PORT=7340 AETHER_ORIGINS="https://example.pages.dev" node bridge.mjs

import net from 'node:net';
import { WebSocketServer } from 'ws';

const PORT = Number(process.env.AETHER_BRIDGE_PORT || 7340);
const EXTRA_ORIGINS = (process.env.AETHER_ORIGINS || '').split(',').map((s) => s.trim()).filter(Boolean);
const MAX_FRAME = 0xffff;

function originAllowed(origin) {
  if (!origin) return false;
  if (EXTRA_ORIGINS.includes(origin)) return true;
  if (/^https:\/\/([a-z0-9-]+\.)*aether-mil\.pages\.dev$/.test(origin)) return true;
  if (/^https:\/\/([a-z0-9-]+-)?aether-mil-web\.infinitumcorp\.workers\.dev$/.test(origin)) return true;
  if (origin === 'https://aether-mil-web.vercel.app') return true;
  if (/^https:\/\/aether-mil-[a-z0-9]+-i-nfinitum-corporation\.vercel\.app$/.test(origin)) return true;
  try {
    const u = new URL(origin);
    return ['localhost', '127.0.0.1', '[::1]'].includes(u.hostname);
  } catch {
    return false;
  }
}

function privateTarget(host) {
  if (net.isIPv4(host)) {
    const [a, b] = host.split('.').map(Number);
    return a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254);
  }
  return host === 'localhost' || host === '::1';
}

const wss = new WebSocketServer({ host: '127.0.0.1', port: PORT, maxPayload: MAX_FRAME });

wss.on('connection', (ws, req) => {
  const origin = req.headers.origin;
  const params = new URL(req.url, 'http://bridge').searchParams;
  const to = params.get('to') || '';
  const m = /^([^:]+|\[[^\]]+\]):(\d{1,5})$/.exec(to);
  const host = m ? m[1].replace(/^\[|\]$/g, '') : '';
  const port = m ? Number(m[2]) : 0;

  if (!originAllowed(origin)) return ws.close(4003, 'origin not allowed');
  if (!m || port < 1 || port > 65535 || !privateTarget(host)) return ws.close(4000, 'target must be host:port on a private network');

  console.log(`[bridge] ${origin} -> ${host}:${port}`);
  const sock = net.connect({ host, port });
  sock.setNoDelay(true);
  let buf = Buffer.alloc(0);
  const pending = [];
  let open = false;

  sock.on('connect', () => {
    open = true;
    for (const f of pending.splice(0)) writeFrame(f);
    ws.send(JSON.stringify({ t: 'open' }));
  });
  sock.on('data', (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    while (buf.length >= 2) {
      const len = buf.readUInt16BE(0);
      if (buf.length < 2 + len) break;
      ws.send(buf.subarray(2, 2 + len));
      buf = buf.subarray(2 + len);
    }
  });
  sock.on('error', (e) => ws.close(4002, String(e.code || e.message).slice(0, 100)));
  sock.on('close', () => ws.readyState === ws.OPEN && ws.close(1000, 'peer closed'));

  function writeFrame(f) {
    const head = Buffer.alloc(2);
    head.writeUInt16BE(f.length, 0);
    sock.write(Buffer.concat([head, f]));
  }

  ws.on('message', (data, isBinary) => {
    if (!isBinary) return;
    const f = Buffer.from(data);
    if (f.length > MAX_FRAME) return ws.close(1009, 'frame too large');
    open ? writeFrame(f) : pending.push(f);
  });
  ws.on('close', () => sock.destroy());
});

console.log(`ÆTHER bridge listening on ws://127.0.0.1:${PORT}`);
