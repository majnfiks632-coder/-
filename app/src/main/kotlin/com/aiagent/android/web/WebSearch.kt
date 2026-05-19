package com.aiagent.android.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLQueryComponent

/**
 * Lightweight, dependency-free internet search + page fetch utility exposed to the agent.
 *
 * The user explicitly asked for an in-app web search:
 *   «Добавь если там нет хороший поиск в интернете».
 *
 * Goals:
 *  - No third-party API keys (works on a fresh install, no extra config).
 *  - No heavy parsing libraries (we only need a few selectors, regex is enough).
 *  - Returns plain text the LLM can read directly.
 *
 * Strategy:
 *  - For `search(...)`: query DuckDuckGo's HTML endpoint and extract the result blocks
 *    (title / URL / snippet) with simple regex. DDG explicitly publishes this surface for
 *    bots and scrapers — no JS, no captcha for short queries.
 *  - For `fetch(...)`: GET the URL, strip script/style tags, collapse whitespace and return
 *    up to N characters of readable text. Good enough to let the model quote a page or
 *    extract a single fact without pulling in a full HTML→text converter.
 */
object WebSearch {

    /** Max characters returned by [fetch]; anything beyond is truncated with a trailing note. */
    private const val MAX_BODY_CHARS: Int = 8_000

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    /**
     * Run a DuckDuckGo HTML search, falling back to a Wikipedia opensearch lookup if DDG
     * returns nothing (e.g. captcha redirect, regional block, rate limit). Returns up to
     * [maxResults] formatted result lines or a clear «no results» message.
     */
    suspend fun search(query: String, maxResults: Int = 8): String {
        if (query.isBlank()) return "Пустой запрос — нечего искать."

        var ddgError: String? = null
        val ddgResults: List<WebResult> = try {
            searchDuckDuckGo(query, maxResults)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // DDG may rate-limit, return captcha pages, or block by region. Record the error
            // and fall through to Wikipedia.
            ddgError = e.message ?: e::class.java.simpleName
            emptyList()
        }
        if (ddgResults.isNotEmpty()) {
            return formatResults("DuckDuckGo", query, ddgResults)
        }

        var wikiError: String? = null
        val wikiResults: List<WebResult> = try {
            searchWikipedia(query, maxResults)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            wikiError = e.message ?: e::class.java.simpleName
            emptyList()
        }
        if (wikiResults.isNotEmpty()) {
            return formatResults("Wikipedia", query, wikiResults)
        }

        val errs = listOfNotNull(
            ddgError?.let { "DDG: $it" },
            wikiError?.let { "Wikipedia: $it" },
        )
        val errSuffix = if (errs.isEmpty()) "" else " (" + errs.joinToString("; ") + ")"
        return "Нет результатов для «$query». Попробуй переформулировать запрос.$errSuffix"
    }

    /** DuckDuckGo HTML scraping path. Uses the `/html/` endpoint that omits JS. */
    private suspend fun searchDuckDuckGo(query: String, maxResults: Int): List<WebResult> {
        // The bare `duckduckgo.com/html/` host occasionally bounces us to the JS-only landing
        // page; the `html.duckduckgo.com` subdomain is the stable scrape target.
        val url = "https://html.duckduckgo.com/html/?q=" + query.encodeURLQueryComponent()
        val response: HttpResponse = client.get(url) {
            // DDG returns a captcha to the default Ktor UA; pretending to be a browser is enough.
            header("User-Agent", USER_AGENT)
            header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
        }
        return parseDdgResults(response.bodyAsText(), maxResults)
    }

    /**
     * Wikipedia fallback. Uses the public `action=opensearch` endpoint that returns a JSON
     * tuple of [query, [titles], [snippets], [urls]]. Picks ru-Wikipedia for Cyrillic queries
     * and en-Wikipedia otherwise.
     */
    private suspend fun searchWikipedia(query: String, maxResults: Int): List<WebResult> {
        val lang = if (query.any { it.code in 0x0400..0x04FF }) "ru" else "en"
        val url = "https://$lang.wikipedia.org/w/api.php?action=opensearch&format=json" +
            "&limit=$maxResults&namespace=0&search=" + query.encodeURLQueryComponent()
        val response: HttpResponse = client.get(url) {
            header("User-Agent", USER_AGENT)
            header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
        }
        return parseWikipediaResults(response.bodyAsText())
    }

    private fun formatResults(source: String, query: String, results: List<WebResult>): String =
        buildString {
            append("Результаты ").append(source)
            append(" по запросу «").append(query).append("»:\n\n")
            results.forEachIndexed { idx, r ->
                append(idx + 1).append(". ").append(r.title).append('\n')
                append("   ").append(r.url).append('\n')
                if (r.snippet.isNotBlank()) append("   ").append(r.snippet).append('\n')
                append('\n')
            }
        }.trimEnd()

    /**
     * GET [url] and return up to [maxBodyChars] of readable text. Strips scripts, styles,
     * tags, collapses whitespace. Used by the `fetch_url` tool.
     */
    suspend fun fetch(url: String): String {
        val normalized = normalizeUrl(url)
        val response: HttpResponse = client.get(normalized) {
            header("User-Agent", USER_AGENT)
            header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
        }
        val statusCode = response.status.value
        val body = response.bodyAsText()
        val text = htmlToText(body)
        val truncated = if (text.length > MAX_BODY_CHARS) {
            text.take(MAX_BODY_CHARS) + "\n…[обрезано до $MAX_BODY_CHARS символов]"
        } else {
            text
        }
        return buildString {
            append("HTTP ").append(statusCode).append(' ').append(normalized).append("\n\n")
            append(truncated)
        }
    }

    fun close() = client.close()

    private fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }

    // -- Parsing helpers ----------------------------------------------------------------

    data class WebResult(val title: String, val url: String, val snippet: String)

    /**
     * Walk DDG's HTML response and extract result blocks. DDG marks each result with a `result`
     * CSS class on a `<div>` and inside has:
     *   - <a class="result__a" href="...redirect-or-direct-url...">Title</a>
     *   - <a class="result__snippet">Description</a>  (sometimes a <div>)
     */
    internal fun parseDdgResults(html: String, maxResults: Int): List<WebResult> {
        val results = mutableListOf<WebResult>()
        val titleRegex = Regex("""<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""")
        // DDG used to wrap snippets in <a>, the current layout uses <div>. Accept both.
        val snippetRegex = Regex(
            """<(?:a|div)[^>]*class="[^"]*result__snippet[^"]*"[^>]*>([\s\S]*?)</(?:a|div)>"""
        )

        val titleMatches = titleRegex.findAll(html).toList()
        val snippetMatches = snippetRegex.findAll(html).toList()
        val titleCount = titleMatches.size
        for (idx in 0 until minOf(titleCount, maxResults)) {
            val tm = titleMatches[idx]
            val rawHref = tm.groupValues[1]
            val title = stripTags(tm.groupValues[2]).trim()
            val url = decodeDdgRedirect(rawHref)
            val snippet = snippetMatches.getOrNull(idx)?.let { stripTags(it.groupValues[1]).trim() } ?: ""
            if (url.isNotBlank()) {
                results.add(WebResult(title = title, url = url, snippet = snippet))
            }
        }
        return results
    }

    /**
     * Parse a Wikipedia opensearch response. The shape is a 4-tuple where index 0 is the echoed
     * query string and indexes 1..3 are arrays of titles / snippets / urls.
     *
     * We hand-roll the parse instead of pulling in kotlinx-serialization plumbing because the
     * tuple shape doesn't map cleanly onto a kotlin data class, and the surface area is so
     * small that regex extraction is reliable enough.
     */
    internal fun parseWikipediaResults(json: String): List<WebResult> {
        // The tuple's first element is a string (the echoed query) — our array extractor
        // only collects arrays, so titles end up at index 0, snippets at 1, urls at 2.
        val arrays = extractTopLevelStringArrays(json)
        if (arrays.size < 3) return emptyList()
        val titles = arrays[0]
        val snippets = arrays[1]
        val urls = arrays[2]
        val out = ArrayList<WebResult>(titles.size)
        for (i in titles.indices) {
            val title = titles[i]
            val url = urls.getOrNull(i).orEmpty()
            val snippet = snippets.getOrNull(i).orEmpty()
            if (title.isNotBlank() && url.isNotBlank()) {
                out.add(WebResult(title = title, url = url, snippet = snippet))
            }
        }
        return out
    }

    /**
     * Find all top-level string arrays in a JSON value, given that the JSON is a single
     * compound expression (typically an array of mixed types). For the input
     * `["q", ["a", "b"], ["c"], ["u1", "u2"]]` returns three lists.
     */
    internal fun extractTopLevelStringArrays(json: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var depth = 0
        var i = 0
        while (i < json.length) {
            val c = json[i]
            when (c) {
                '"' -> {
                    // Skip over the entire string literal, respecting backslash escapes,
                    // so commas / brackets inside a string don't perturb depth tracking.
                    i = endOfJsonString(json, i)
                }
                '[' -> {
                    depth += 1
                    if (depth == 2) {
                        val end = findMatchingBracket(json, i)
                        if (end > i) {
                            out.add(parseStringArray(json.substring(i + 1, end)))
                            i = end
                            depth -= 1
                        }
                    }
                }
                ']' -> depth -= 1
            }
            i += 1
        }
        return out
    }

    /** Return the index of the closing `]` that matches the `[` at [openIdx], or -1. */
    private fun findMatchingBracket(json: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < json.length) {
            val c = json[i]
            when (c) {
                '"' -> i = endOfJsonString(json, i)
                '[' -> depth += 1
                ']' -> {
                    depth -= 1
                    if (depth == 0) return i
                }
            }
            i += 1
        }
        return -1
    }

    /** Return the index of the closing `"` for the JSON string literal that starts at [startIdx]. */
    private fun endOfJsonString(json: String, startIdx: Int): Int {
        var i = startIdx + 1
        while (i < json.length) {
            val c = json[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '"') return i
            i += 1
        }
        return json.length - 1
    }

    /** Parse the inner contents of `["a", "b", "c"]` (the brackets are stripped before call). */
    private fun parseStringArray(inner: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '"') {
                val end = endOfJsonString(inner, i)
                if (end > i) {
                    out.add(decodeJsonString(inner.substring(i + 1, end)))
                    i = end
                }
            }
            i += 1
        }
        return out
    }

    /** Minimal JSON string unescape sufficient for Wikipedia opensearch responses. */
    private fun decodeJsonString(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) {
                sb.append(c); i += 1; continue
            }
            val next = s[i + 1]
            when (next) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000c')
                'u' -> {
                    if (i + 5 < s.length) {
                        val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 6
                            continue
                        }
                    }
                    sb.append(next)
                }
                else -> sb.append(next)
            }
            i += 2
        }
        return sb.toString()
    }

    /** DDG wraps result URLs in `/l/?uddg=<encoded>` redirects; unwrap them. */
    internal fun decodeDdgRedirect(href: String): String {
        if (!href.contains("uddg=")) return absolutize(href)
        val q = href.substringAfter("uddg=", "")
        val raw = q.substringBefore('&')
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrNull()
        return decoded?.takeIf { it.isNotBlank() } ?: absolutize(href)
    }

    private fun absolutize(href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        if (href.startsWith("//")) return "https:$href"
        if (href.startsWith("/")) {
            // Best-effort: rebuild a DDG-anchored absolute URL. The href already starts with /
            // and may contain a query string — just glue the host on the front. Avoids the Ktor
            // URLBuilder.encodedPath surface, which differs between minor versions.
            return "https://duckduckgo.com$href"
        }
        return href
    }

    /** Convert raw HTML to plain readable text. Drops `<script>`/`<style>` blocks fully,
     *  then strips remaining tags and collapses whitespace. */
    internal fun htmlToText(html: String): String {
        val noScript = html.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
        val noTags = noScript
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?p[^>]*>"), "\n\n")
            .replace(Regex("(?i)</?(div|li|h[1-6]|tr)[^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
        val decoded = decodeEntities(noTags)
        return decoded
            .replace(Regex("[ \\t\\f\\u00A0]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n\n")
            .trim()
    }

    private fun stripTags(html: String): String =
        decodeEntities(html.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    private fun decodeEntities(input: String): String =
        input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("&#(\\d+);")) { m ->
                val code = m.groupValues[1].toIntOrNull() ?: return@replace m.value
                runCatching { code.toChar().toString() }.getOrDefault(m.value)
            }
            .replace(Regex("&#x([0-9A-Fa-f]+);")) { m ->
                val code = m.groupValues[1].toIntOrNull(16) ?: return@replace m.value
                runCatching { code.toChar().toString() }.getOrDefault(m.value)
            }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; KiroAgent) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
}
