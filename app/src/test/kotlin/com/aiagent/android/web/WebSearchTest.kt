package com.aiagent.android.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WebSearch]'s pure parsing logic. We test the HTML / JSON parsers in
 * isolation so they can fail loudly when DDG or Wikipedia change their wire format,
 * without depending on actual network access from the test runner.
 */
class WebSearchTest {

    @Test
    fun parsesDuckDuckGoHtmlResults() {
        // Synthetic but realistic snippet of DDG's `/html/` surface — class names match
        // what the live site emitted at the time of writing.
        val html = """
            <div class="result">
              <a class="result__a" href="https://example.com/page1">Example One</a>
              <a class="result__snippet" href="https://example.com/page1">Short description of page one.</a>
            </div>
            <div class="result">
              <a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.org%2Fpage2&rut=abc">Example Two</a>
              <div class="result__snippet">Second snippet.</div>
            </div>
        """.trimIndent()

        val results = WebSearch.parseDdgResults(html, maxResults = 8)
        assertEquals(2, results.size)
        assertEquals("Example One", results[0].title)
        assertEquals("https://example.com/page1", results[0].url)
        assertEquals("Short description of page one.", results[0].snippet)

        assertEquals("Example Two", results[1].title)
        // Redirect URLs through DDG's `/l/?uddg=…` should be decoded back to the origin.
        assertEquals("https://example.org/page2", results[1].url)
        assertEquals("Second snippet.", results[1].snippet)
    }

    @Test
    fun parsesWikipediaOpenSearchResponse() {
        // Real shape returned by `https://ru.wikipedia.org/w/api.php?action=opensearch`:
        // a 4-tuple of [query_string, [titles], [snippets], [urls]].
        val json = "[\"cats\"," +
            "[\"Cat\",\"Kitten\",\"Cat (film)\"]," +
            "[\"Carnivorous mammal.\",\"A juvenile cat.\",\"Russian short film.\"]," +
            "[\"https://en.wikipedia.org/wiki/Cat\"," +
            "\"https://en.wikipedia.org/wiki/Kitten\"," +
            "\"https://en.wikipedia.org/wiki/Cat_(film)\"]]"

        val results = WebSearch.parseWikipediaResults(json)
        assertEquals(3, results.size)
        assertEquals("Cat", results[0].title)
        assertEquals("https://en.wikipedia.org/wiki/Cat", results[0].url)
        assertTrue(results[0].snippet.contains("Carnivorous"))
        assertEquals("Cat (film)", results[2].title)
        assertEquals("https://en.wikipedia.org/wiki/Cat_(film)", results[2].url)
    }

    @Test
    fun parsesEmptyDuckDuckGoHtmlGracefully() {
        // DDG sometimes returns the page header and zero results (rate limit, captcha
        // redirect, …). The parser must yield an empty list rather than throw.
        val html = "<html><body><p>No results.</p></body></html>"
        assertEquals(0, WebSearch.parseDdgResults(html, maxResults = 8).size)
    }

    @Test
    fun extractsTopLevelStringArraysHandlesNestedBrackets() {
        // Make sure our hand-rolled top-level array extractor doesn't get confused by
        // strings containing brackets or commas.
        val json = """["q",["a[1]","b,c"],["d","e"],["u1","u2"]]"""
        val arrays = WebSearch.extractTopLevelStringArrays(json)
        assertEquals(3, arrays.size)
        assertEquals(listOf("a[1]", "b,c"), arrays[0])
        assertEquals(listOf("d", "e"), arrays[1])
        assertEquals(listOf("u1", "u2"), arrays[2])
    }
}
