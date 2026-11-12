/**
 * Mio forum API — Cloudflare Worker örneği.
 *
 * `docs/index.html` içindeki `FORUM_API_BASE` bu worker'ın adresine ayarlandığında forum
 * gerçek olur: konular herkes tarafından görülür ve yanıtlanır.
 *
 * Uçlar (sözleşme: docs/FORUM_API.md)
 *   GET    /threads               -> { threads: [...] }
 *   POST   /threads               <- { title, author, tag, body }      -> { thread }
 *   POST   /threads/:id/replies   <- { author, body }                  -> { thread }
 *   OPTIONS *                     -> CORS
 *
 * Depolama: tek KV anahtarı (`threads`) altında konu listesi. Trafik artarsa D1'e taşınabilir.
 *
 * Gizli ayarlar (wrangler secret / [vars]):
 *   ALLOWED_ORIGIN  — sitenin adresi, örn. https://mio-129.github.io ("*" bırakılabilir)
 *   ADMIN_TOKEN     — (isteğe bağlı) DELETE /threads/:id için
 */

const KEY = 'threads';
const MAX_THREADS = 200;
const LIMITS = { title: 120, author: 40, body: 8000, reply: 4000 };
const TAGS = ['kurulum', 'hata', 'ipucu', 'öneri', 'install', 'bug', 'tip', 'idea'];

/* --------------------------------------------------------------- yardımcılar ---------------- */

function corsHeaders(env, request) {
  const allowed = (env && env.ALLOWED_ORIGIN) || '*';
  const origin = request.headers.get('Origin') || '';
  const allowOrigin = allowed === '*' ? '*' : (origin === allowed ? origin : allowed);
  return {
    'Access-Control-Allow-Origin': allowOrigin,
    'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    'Access-Control-Max-Age': '86400',
    Vary: 'Origin'
  };
}

function json(body, status, env, request) {
  return new Response(JSON.stringify(body), {
    status: status || 200,
    headers: Object.assign(
      { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
      corsHeaders(env, request)
    )
  });
}

function sanitize(value, maxLength) {
  if (typeof value !== 'string') return '';
  // Kontrol karakterlerini at, kırp, uzunluğu sınırla
  return value.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '').trim().slice(0, maxLength);
}

function newId() {
  return String(Date.now()) + '-' + Math.random().toString(36).slice(2, 7);
}

async function readThreads(env) {
  const raw = await env.FORUM.get(KEY);
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch (e) {
    return [];
  }
}

async function writeThreads(env, threads) {
  const trimmed = threads
    .sort((a, b) => String(b.created).localeCompare(String(a.created)))
    .slice(0, MAX_THREADS);
  await env.FORUM.put(KEY, JSON.stringify(trimmed));
}

/* ------------------------------------------------------------------ handler ----------------- */

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders(env, request) });
    }

    try {
      /* ------------------------------------------------------------- listele ---------------- */
      if (path === '/threads' && request.method === 'GET') {
        const threads = await readThreads(env);
        return json({ threads }, 200, env, request);
      }

      /* -------------------------------------------------------------- konu aç --------------- */
      if (path === '/threads' && request.method === 'POST') {
        const payload = await request.json().catch(() => ({}));
        const title = sanitize(payload.title, LIMITS.title);
        const author = sanitize(payload.author, LIMITS.author);
        const body = sanitize(payload.body, LIMITS.body);
        const tag = TAGS.includes(payload.tag) ? payload.tag : 'kurulum';

        if (!title || !author || !body) {
          return json({ error: 'title, author ve body zorunlu' }, 400, env, request);
        }

        const threads = await readThreads(env);
        const thread = { id: newId(), title, author, tag, body, created: new Date().toISOString(), replies: [] };
        threads.push(thread);
        await writeThreads(env, threads);
        return json({ thread }, 201, env, request);
      }

      /* --------------------------------------------------------------- yanıt ---------------- */
      const replyMatch = path.match(/^\/threads\/([^/]+)\/replies$/);
      if (replyMatch && request.method === 'POST') {
        const id = decodeURIComponent(replyMatch[1]);
        const payload = await request.json().catch(() => ({}));
        const author = sanitize(payload.author, LIMITS.author);
        const body = sanitize(payload.body, LIMITS.reply);
        if (!author || !body) {
          return json({ error: 'author ve body zorunlu' }, 400, env, request);
        }

        const threads = await readThreads(env);
        const index = threads.findIndex(thread => String(thread.id) === String(id));
        if (index < 0) return json({ error: 'konu bulunamadı' }, 404, env, request);

        threads[index].replies = threads[index].replies || [];
        threads[index].replies.push({ id: newId(), author, body, created: new Date().toISOString() });
        await writeThreads(env, threads);
        return json({ thread: threads[index] }, 201, env, request);
      }

      /* ---------------------------------------------------- moderasyon (sil) ---------------- */
      const deleteMatch = path.match(/^\/threads\/([^/]+)$/);
      if (deleteMatch && request.method === 'DELETE') {
        const token = (request.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '');
        if (!env.ADMIN_TOKEN || token !== env.ADMIN_TOKEN) {
          return json({ error: 'yetkisiz' }, 401, env, request);
        }
        const id = decodeURIComponent(deleteMatch[1]);
        const threads = (await readThreads(env)).filter(thread => String(thread.id) !== String(id));
        await writeThreads(env, threads);
        return json({ ok: true }, 200, env, request);
      }

      /* ------------------------------------------------------------ sağlık ------------------ */
      if (path === '/' || path === '/health') {
        return json({ ok: true, service: 'mio-forum', endpoints: ['/threads'] }, 200, env, request);
      }

      return json({ error: 'bulunamadı' }, 404, env, request);
    } catch (error) {
      return json({ error: 'sunucu hatası', detail: String(error && error.message || error) }, 500, env, request);
    }
  }
};
