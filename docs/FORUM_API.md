# Forum API — sözleşme / contract

`index.html` iki modda çalışır:

| `FORUM_API_BASE` | Mod | Davranış |
| --- | --- | --- |
| boş `''` | **yerel demo** | Konular `localStorage`'da tutulur, sadece o tarayıcıda görünür. Sunucu gerekmez. |
| dolu (örn. `https://mio-forum.ornek.workers.dev`) | **canlı** | Konular API'den okunur/yazılır, herkes görür. |

Site statiktir (GitHub Pages / herhangi bir hosting). Forum canlı olacaksa aşağıdaki dört ucu konuşan bir
API gerekir; hazır bir Cloudflare Workers örneği: [`forum-worker.js`](forum-worker.js).

## Uçlar / Endpoints

Tüm istekler ve yanıtlar JSON (`application/json; charset=utf-8`).

### `GET /threads`

```json
{
  "threads": [
    {
      "id": "1730000000000-ab12c",
      "title": "Kurulumda depo hatası",
      "author": "mert",
      "tag": "hata",
      "body": "pkg update sonrası ...",
      "created": "2026-11-09T12:00:00.000Z",
      "replies": [
        { "id": "1730000001000-x1", "author": "mio", "body": "termux-change-repo dene", "created": "2026-11-09T12:05:00.000Z" }
      ]
    }
  ]
}
```

En yeni konu önce dönebilir; site kendi sıralar (`created` azalan).

### `POST /threads`

```json
{ "title": "Başlık", "author": "ad", "tag": "kurulum", "body": "mesaj" }
```

Yanıt: `{ "thread": { …yukarıdaki nesne… } }` — `id` ve `created` sunucuda üretilir.

### `POST /threads/:id/replies`

```json
{ "author": "ad", "body": "yanıt" }
```

Yanıt: `{ "thread": { …güncel konu, replies dahil… } }`
(`thread` dönmezse site yanıtı yerel listeye ekler.)

### `OPTIONS *`

CORS ön kontrolü. Başlıklar:

```
Access-Control-Allow-Origin:  https://<site-alanı>
Access-Control-Allow-Methods: GET, POST, OPTIONS
Access-Control-Allow-Headers: Content-Type
Access-Control-Max-Age:       86400
```

## Doğrulama kuralları (site + worker)

| Alan | Kural |
| --- | --- |
| `title` | 1–120 karakter, zorunlu |
| `author` | 1–40 karakter, zorunlu |
| `body` | 1–8000 karakter (yanıt: 1–4000), zorunlu |
| `tag` | `kurulum`, `hata`, `ipucu`, `öneri` (`en`: `install`, `bug`, `tip`, `idea`) |

Site tüm metni `escapeHtml()` ile basar, yani gövdedeki HTML çalışmaz (XSS'e karşı). Worker tarafında da
en az 1 saniye hız sınırı ve 4000 karakter kırpma var.

## Cloudflare Workers ile yayına alma

```sh
npm i -g wrangler
wrangler login
wrangler kv namespace create MIO_FORUM          # dönen id'yi aşağıya yaz
# wrangler.toml:
#   name = "mio-forum"
#   main = "forum-worker.js"
#   compatibility_date = "2026-01-01"
#   [[kv_namespaces]]
#   binding = "FORUM"
#   id = "<yukarıdaki id>"
wrangler deploy
```

Sonra `docs/index.html` içindeki tek satırı değiştir:

```js
const FORUM_API_BASE = 'https://mio-forum.<hesabın>.workers.dev';
```

> Not: KV tek başına yeterli (konu listesi tek anahtarda tutulur). Trafik artarsa D1'e taşınabilir;
> uçlar aynı kaldığı için site değişmez.

## Alternatifler

- **Supabase / Firebase** — tablo + RLS ile aynı dört uç taklit edilebilir.
- **GitHub Issues** — yazma için token gerekir, tarayıcıdan güvenli değildir; sadece okuma için
  `https://api.github.com/repos/.../issues` kullanılabilir.
