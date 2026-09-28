package com.haise.jiyu.source.comix

/**
 * JavaScript injektovaný do stránek comix.to ve skrytém WebView ([ComixWebViewRunner]).
 * Stránka podepisuje a dešifruje API requesty vlastním modulem, který se skládá za
 * běhu z `env-*.js` bundlu a crypto materiálu rotujícího s každým deployi webu -
 * proto se materiál nehardkóduje, ale zachytává hookem na `window.atob`.
 *
 * Skripty jsou přesným portem ověřeného Kotatsu-Redo ComixParseru (BROWSE/PAGE
 * capture + CHAPTER script), akorát výsledek nevrací přes URL fragment, ale přes
 * `window.__jiyuComixPass(json)` / `__jiyuComixFail(msg)` - ty volají
 * `JiyuComixBridge` (addJavascriptInterface) a zároveň ukládají do
 * `window.__jiyuComixResult/__jiyuComixError` pro polling fallback.
 */
object ComixScripts {

    private const val POLL_TICKS = 300

    /** JSON literal pro vložení stringu do skriptu. */
    fun jsString(value: String?): String =
        value?.let { org.json.JSONObject.quote(it) } ?: "null"

    /**
     * Detekce blokovacích stavů stránky podle stabilních DOM markerů (ne
     * lokalizovaných titulků). Sdílené všemi capture skripty.
     */
    private const val CLOUDFLARE_DETECT_JS = """
        const isCloudflareChallenge = () => {
            try {
                return !!document.querySelector(
                    '#challenge-form, #challenge-running, #challenge-error-title, #challenge-error-text, ' +
                    '#cf-challenge-running, .cf-browser-verification, .cf-turnstile, ' +
                    'form[action*="__cf_chl"], input[name="cf-turnstile-response"], ' +
                    'script[src*="challenge-platform"], script[src*="turnstile"], ' +
                    '[src*="challenges.cloudflare.com"]'
                );
            } catch (e) { return false; }
        };
        const isWebViewLoadError = () => {
            try {
                const uri = String(document.documentURI || '');
                return uri.indexOf('chrome-error://') === 0 || !!document.querySelector(
                    '#main-frame-error, #error-information-popup-container, body.neterror'
                );
            } catch (e) { return false; }
        };
    """

    /**
     * Injektuje se jako úplně první <script> do HTML - běží před vlastními
     * skripty stránky, takže hook na atob() zachytí crypto inicializaci.
     * [contentFilter] nastaví localStorage `settings_v2`, aby server vracel
     * tituly daného ratingu (web drží filtr klient-side).
     */
    fun bootstrap(contentFilter: String?): String = """
        <script>
        (function () {
            if (window.__jiyuComixBoot) return;
            window.__jiyuComixBoot = true;
            try {
                var rating = ${jsString(contentFilter)};
                if (rating) {
                    var settings = { state: { contentFilter: rating }, version: 0 };
                    window.localStorage.setItem('settings_v2', JSON.stringify(settings));
                }
            } catch (e) {}
            var captures = [];
            var origAtob = window.atob ? window.atob.bind(window) : null;
            if (origAtob) {
                window.atob = function (v) {
                    var d = origAtob(v);
                    try {
                        var b = Array.from(d, function (c) { return c.charCodeAt(0) & 255; });
                        if (b.length === 256 || b.length === 24 || b.length === 32) captures.push(b);
                    } catch (e) {}
                    return d;
                };
            }
            window.__jiyuComixMaterial = function () {
                var sboxes = captures.filter(function (x) { return x.length === 256; }).slice(0, 3);
                var keys = captures.filter(function (x) { return x.length === 24 || x.length === 32; }).slice(0, 3);
                return (sboxes.length === 3 && keys.length === 3) ? { sboxes: sboxes, keys: keys } : null;
            };
            // Vysledek se uklada i do window.__jiyuComixResult/__jiyuComixError -
            // runner ho muze vycist evaluateJavascript pollingem, kdyby JS bridge
            // interface z jakehokoli duvodu nebyl dostupny.
            window.__jiyuComixPass = function (payload) {
                try {
                    var json = JSON.stringify({
                        payload: String(payload),
                        material: window.__jiyuComixMaterial(),
                    });
                    window.__jiyuComixResult = json;
                    try { window.JiyuComixBridge.pass(json); } catch (e) {}
                } catch (e) {
                    window.__jiyuComixFail('bridge: ' + e);
                }
            };
            window.__jiyuComixFail = function (err) {
                try {
                    var msg = String((err && err.message) || err);
                    window.__jiyuComixError = msg;
                    try { window.JiyuComixBridge.fail(msg); } catch (e) {}
                } catch (e) {}
            };
        })();
        </script>
    """.trimIndent()

    /**
     * Zachytává výsledek `/api/v1/manga` (browse/search): hookuje `JSON.parse`
     * (chytá dešifrované objekty z response interceptoru stránky), `fetch` a
     * `XMLHttpRequest` (chytá plain odpovědi) a jako zálohu polluje
     * `script#initial-data`. Vrací kompaktní `{result:{items:[...]}}`.
     */
    fun browseCapture(): String = """
        <script>
        (async () => {
            const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
$CLOUDFLARE_DETECT_JS
            if (isWebViewLoadError()) { window.__jiyuComixFail('neterror'); return; }
            if (isCloudflareChallenge()) { window.__jiyuComixFail('CLOUDFLARE'); return; }
            const original = JSON.parse;
            let captured = null;
            const compactNamedItems = (values) => {
                if (!Array.isArray(values)) return undefined;
                return values.map((value) => {
                    if (!value || typeof value !== 'object') return null;
                    const item = {};
                    if (value.id != null) item.id = value.id;
                    if (value.title) item.title = value.title;
                    else if (value.name) item.name = value.name;
                    return item;
                }).filter((value) => value && (value.title || value.name));
            };
            const compactItem = (item) => {
                const result = {
                    hid: item.hid || item.hash_id || '',
                    title: item.title || ''
                };
                if (item.synopsis) result.synopsis = item.synopsis;
                if (item.status) result.status = item.status;
                if (item.type) result.type = item.type;
                if (item.year) result.year = item.year;
                if (item.latestChapter != null) result.latestChapter = item.latestChapter;
                if (item.rank != null) result.rank = item.rank;
                if (item.followsTotal != null) result.followsTotal = item.followsTotal;
                if (item.contentRating) result.contentRating = item.contentRating;
                if (item.ratedAvg != null) result.ratedAvg = item.ratedAvg;
                else if (item.rated_avg != null) result.rated_avg = item.rated_avg;
                if (item.poster && typeof item.poster === 'object') {
                    result.poster = {};
                    if (item.poster.large) result.poster.large = item.poster.large;
                    if (item.poster.medium) result.poster.medium = item.poster.medium;
                    if (item.poster.small) result.poster.small = item.poster.small;
                }
                const termKeys = ['genres', 'genre', 'tags', 'theme', 'demographics', 'demographic', 'formats'];
                for (const key of termKeys) {
                    const values = compactNamedItems(item[key]);
                    if (values && values.length) result[key] = values;
                }
                const authors = compactNamedItems(item.authors || item.author);
                if (authors && authors.length) result.authors = authors;
                const artists = compactNamedItems(item.artists || item.artist);
                if (artists && artists.length) result.artists = artists;
                return result;
            };
            const take = (obj) => {
                if (captured) return true;
                try {
                    const items = obj && obj.result && obj.result.items;
                    if (Array.isArray(items) && items.length > 0) {
                        captured = JSON.stringify({ result: { items: items.map(compactItem) } });
                        return true;
                    }
                } catch (e) {}
                return false;
            };
            JSON.parse = function () {
                const parsed = original.apply(this, arguments);
                take(parsed);
                return parsed;
            };
            if (typeof window.fetch === 'function') {
                const originalFetch = window.fetch;
                window.fetch = function () {
                    return originalFetch.apply(this, arguments).then((response) => {
                        try {
                            response.clone().text().then((text) => {
                                try { take(original(text)); } catch (e) {}
                            }).catch(() => {});
                        } catch (e) {}
                        return response;
                    });
                };
            }
            const originalSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function () {
                this.addEventListener('load', function () {
                    try { take(original(this.responseText)); } catch (e) {}
                });
                return originalSend.apply(this, arguments);
            };
            for (let i = 0; i < $POLL_TICKS; i++) {
                if (captured) { window.__jiyuComixPass(captured); return; }
                if (isWebViewLoadError()) { window.__jiyuComixFail('neterror'); return; }
                if (isCloudflareChallenge()) { window.__jiyuComixFail('CLOUDFLARE'); return; }
                try {
                    const node = document.querySelector('script#initial-data');
                    if (node && node.textContent) {
                        const queries = original(node.textContent).queries;
                        if (queries) {
                            for (const k in queries) {
                                if (take(queries[k]) || take({ result: queries[k] })) break;
                            }
                        }
                    }
                } catch (e) {}
                await sleep(100);
            }
            window.__jiyuComixFail('no browse data captured');
        })();
        </script>
    """.trimIndent()

    /**
     * Stejná technika pro reader stránku - zachytává payload poznatelný podle
     * `result.pages` (`{baseUrl, items:[{url,s}]}`).
     */
    val PAGES_CAPTURE: String = """
        <script>
        (async () => {
            const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
$CLOUDFLARE_DETECT_JS
            if (isWebViewLoadError()) { window.__jiyuComixFail('neterror'); return; }
            if (isCloudflareChallenge()) { window.__jiyuComixFail('CLOUDFLARE'); return; }
            const original = JSON.parse;
            let captured = null;
            const take = (obj) => {
                if (captured) return true;
                try {
                    const result = obj && obj.result ? obj.result : obj;
                    if (result && result.pages) {
                        captured = JSON.stringify({ result: result });
                        return true;
                    }
                } catch (e) {}
                return false;
            };
            JSON.parse = function () {
                const parsed = original.apply(this, arguments);
                take(parsed);
                return parsed;
            };
            if (typeof window.fetch === 'function') {
                const originalFetch = window.fetch;
                window.fetch = function () {
                    return originalFetch.apply(this, arguments).then((response) => {
                        try {
                            response.clone().text().then((text) => {
                                try { take(original(text)); } catch (e) {}
                            }).catch(() => {});
                        } catch (e) {}
                        return response;
                    });
                };
            }
            const originalSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function () {
                this.addEventListener('load', function () {
                    try { take(original(this.responseText)); } catch (e) {}
                });
                return originalSend.apply(this, arguments);
            };
            for (let i = 0; i < $POLL_TICKS; i++) {
                if (captured) { window.__jiyuComixPass(captured); return; }
                if (isWebViewLoadError()) { window.__jiyuComixFail('neterror'); return; }
                if (isCloudflareChallenge()) { window.__jiyuComixFail('CLOUDFLARE'); return; }
                try {
                    const node = document.querySelector('script#initial-data');
                    if (node && node.textContent) {
                        const queries = original(node.textContent).queries;
                        if (queries) {
                            for (const k in queries) { if (take(queries[k])) break; }
                        }
                    }
                } catch (e) {}
                await sleep(100);
            }
            window.__jiyuComixFail('no page data captured');
        })();
        </script>
    """.trimIndent()

    /**
     * Stáhne celý seznam kapitol: vytáhne `env-*.js` bundle referencovaný hlavním
     * modulem stránky, importuje ho a volá jeho `mangaApi.chapters()` - requesty
     * si podepisuje a dešifruje kód webu sám. Stránky po 100 se dotahují
     * paralelně (6 najednou) podle `meta.lastPage`.
     *
     * Výsledek je kompaktní - sdílený URL prefix a seznam skupin jdou jen jednou,
     * kapitoly na ně odkazují indexem:
     *   prefix  sdílený začátek všech chapter URL
     *   groups  [{ id?, name?, o }]  - o = 1 pro oficiální release
     *   items   [{ i: id, n: number, u: url suffix, g: group index,
     *              v: volume?, t: name?, c: epoch seconds?, d: relative date? }]
     */
    fun chapterFetch(hid: String, envUrl: String?): String = """
        <script>
        (async () => {
$CLOUDFLARE_DETECT_JS
            if (isWebViewLoadError()) { window.__jiyuComixFail('neterror'); return; }
            if (isCloudflareChallenge()) { window.__jiyuComixFail('CLOUDFLARE'); return; }
            const MANGA_ID = ${jsString(hid)};
            const KNOWN_ENV_URL = ${jsString(envUrl)};
            const MAX_PAGES = 200;
            const CONCURRENCY = 6;
            const BUNDLE_WAIT_TICKS = 200;
            const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
            try {
                let envUrl = KNOWN_ENV_URL;
                if (!envUrl) {
                    let mainScript = null;
                    for (let i = 0; i < BUNDLE_WAIT_TICKS; i++) {
                        if (isWebViewLoadError()) { window.__jiyuComixFail('neterror'); return; }
                        if (isCloudflareChallenge()) { window.__jiyuComixFail('CLOUDFLARE'); return; }
                        mainScript = document.querySelector('script[type=module][src*="/dist/main-"]');
                        if (mainScript && mainScript.src) break;
                        await sleep(100);
                    }
                    if (!mainScript || !mainScript.src) { window.__jiyuComixFail('main bundle not found'); return; }
                    const mainResponse = await fetch(mainScript.src);
                    if (!mainResponse.ok) { window.__jiyuComixFail('could not load main bundle'); return; }
                    const mainJavaScript = await mainResponse.text();
                    const environmentFile = mainJavaScript.match(/from\s*["']\.\/(env-[^"']+\.js)["']/);
                    if (!environmentFile) { window.__jiyuComixFail('env bundle not found'); return; }
                    envUrl = new URL(environmentFile[1], mainScript.src).href;
                }
                // Osobny import() v injektovanem skriptu se parsuje ve spatnem
                // kontextu; pres Function zustane skutecnym dynamickym importem.
                const importBundle = new Function('url', 'return import(url)');
                const environment = await importBundle(envUrl);
                const mangaApi = Object.values(environment).find((value) =>
                    value && typeof value === 'object' && typeof value.chapters === 'function');
                if (!mangaApi) { window.__jiyuComixFail('manga API not found'); return; }

                const askFor = (page) => mangaApi.chapters(MANGA_ID, {
                    page: page, limit: 100, order: { number: 'desc' }
                });
                const rowsOf = (response) => response && (response.items ||
                    (response.result && response.result.items));

                const collected = [];
                const first = await askFor(1);
                const firstItems = rowsOf(first);
                if (!Array.isArray(firstItems)) { window.__jiyuComixFail('unexpected chapter response'); return; }
                collected.push(...firstItems);

                const meta = first.meta || first.pagination || (first.result && (first.result.meta || first.result.pagination)) || {};
                const lastPage = Math.min(meta.lastPage || meta.last_page || 1, MAX_PAGES);
                if (firstItems.length > 0 && lastPage > 1) {
                    let nextPage = 2;
                    const worker = async () => {
                        for (;;) {
                            const page = nextPage++;
                            if (page > lastPage) return;
                            const items = rowsOf(await askFor(page));
                            if (Array.isArray(items)) collected.push(...items);
                        }
                    };
                    await Promise.all(Array.from({ length: Math.min(CONCURRENCY, lastPage - 1) }, worker));
                } else if (meta.hasNext) {
                    let page = 2;
                    while (page <= MAX_PAGES) {
                        const response = await askFor(page);
                        const items = rowsOf(response);
                        if (!Array.isArray(items) || items.length === 0) break;
                        collected.push(...items);
                        const pageMeta = response.meta || response.pagination || {};
                        if (!pageMeta.hasNext) break;
                        page++;
                    }
                }

                let prefix = collected.length ? String(collected[0].url || '') : '';
                for (const chapter of collected) {
                    const url = String(chapter.url || '');
                    let i = 0;
                    while (i < prefix.length && i < url.length && prefix[i] === url[i]) i++;
                    prefix = prefix.slice(0, i);
                }
                const groups = [];
                const groupIndex = new Map();
                const items = collected.map((chapter) => {
                    const group = chapter.group || null;
                    const official = chapter.isOfficial ? 1 : 0;
                    const groupId = group && group.id != null ? group.id : null;
                    const groupName = group && group.name ? group.name : (official ? 'Official' : null);
                    const key = (groupId != null ? 'i' + groupId : 'n' + (groupName || '')) + '|' + official;
                    let g = groupIndex.get(key);
                    if (g === undefined) {
                        g = groups.length;
                        groupIndex.set(key, g);
                        const entry = { o: official };
                        if (groupId != null) entry.id = groupId;
                        if (groupName) entry.name = groupName;
                        groups.push(entry);
                    }
                    const row = {
                        i: chapter.id,
                        n: typeof chapter.number === 'number' ? chapter.number : Number(chapter.number) || 0,
                        u: String(chapter.url || '').slice(prefix.length),
                        g: g
                    };
                    if (chapter.volume != null) row.v = chapter.volume;
                    if (chapter.name) row.t = chapter.name;
                    if (typeof chapter.createdAt === 'number') row.c = chapter.createdAt;
                    else if (chapter.createdAtFormatted) row.d = chapter.createdAtFormatted;
                    return row;
                });
                window.__jiyuComixPass(JSON.stringify({
                    prefix: prefix,
                    groups: groups,
                    items: items,
                    empty: items.length === 0,
                    env: envUrl
                }));
            } catch (error) {
                window.__jiyuComixFail((error && error.message) || error);
            }
        })();
        </script>
    """.trimIndent()
}
