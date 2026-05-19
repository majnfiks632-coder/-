package com.aiagent.android.llm

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Unit tests for the SSE streaming parser in [LlmClient]. The parser reads OpenAI-shaped
 * `data:` events off a [ByteReadChannel] and emits each `delta.content` fragment to the
 * caller while assembling a synthetic non-streaming [ChatResponse] at the end.
 */
class SseStreamTest {

    private fun streamFrom(body: String): ByteReadChannel =
        ByteReadChannel(body.toByteArray(Charsets.UTF_8))

    private fun mkClient(): LlmClient = LlmClient(baseUrl = "http://invalid.local", apiKey = "")

    @Test
    fun `assembles content from streaming deltas`() = runBlocking {
        val body = (
            "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}\n\n" +
                "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
                "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\", world\"}}]}\n\n" +
                "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n"
            )
        val deltas = mutableListOf<String>()
        val client = mkClient()
        try {
            val resp = client.consumeSseChunks(streamFrom(body), onDelta = { deltas.add(it) })
            assertEquals(listOf("Hello", ", world"), deltas)
            val msg = resp.choices.single().message
            assertEquals("Hello, world", msg.contentText)
            assertEquals("stop", resp.choices.single().finishReason)
        } finally {
            client.close()
        }
    }

    @Test
    fun `tolerates blank lines and unknown keys`() = runBlocking {
        val body = (
            "\n" +
                "data: {\"id\":\"y\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"},\"unknown\":true}]}\n\n" +
                "data: \n\n" +
                "data: {\"id\":\"y\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"b\"}}]}\n\n" +
                "data: [DONE]\n\n"
            )
        val deltas = mutableListOf<String>()
        val client = mkClient()
        try {
            val resp = client.consumeSseChunks(streamFrom(body), onDelta = { deltas.add(it) })
            assertEquals(listOf("a", "b"), deltas)
            assertEquals("ab", resp.choices.single().message.contentText)
        } finally {
            client.close()
        }
    }

    @Test
    fun `merges tool call deltas across chunks`() = runBlocking {
        val body = (
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                "{\"index\":0,\"id\":\"call_1\",\"type\":\"function\"," +
                "\"function\":{\"name\":\"send_message\",\"arguments\":\"{\\\"text\\\":\"}}]}}]}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                "{\"index\":0,\"function\":{\"arguments\":\"\\\"hi\\\"}\"}}]}}]}\n\n" +
                "data: [DONE]\n\n"
            )
        val client = mkClient()
        try {
            val resp = client.consumeSseChunks(streamFrom(body), onDelta = { /* no content deltas */ })
            val tc = resp.choices.single().message.toolCalls!!.single()
            assertEquals("call_1", tc.id)
            assertEquals("send_message", tc.function.name)
            assertEquals("{\"text\":\"hi\"}", tc.function.arguments)
        } finally {
            client.close()
        }
    }

    @Test
    fun `stops at DONE sentinel`() = runBlocking {
        val body = (
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"}}]}\n\n" +
                "data: [DONE]\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"y\"}}]}\n\n"
            )
        val deltas = mutableListOf<String>()
        val client = mkClient()
        try {
            client.consumeSseChunks(streamFrom(body), onDelta = { deltas.add(it) })
            assertEquals(listOf("x"), deltas)
            assertTrue("y" !in deltas)
        } finally {
            client.close()
        }
    }
}
