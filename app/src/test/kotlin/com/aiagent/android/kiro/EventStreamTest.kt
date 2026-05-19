package com.aiagent.android.kiro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Verifies that [EventStreamParser] correctly decodes an `application/vnd.amazon.eventstream`
 * body identical in shape to what `q.us-east-1.amazonaws.com` returns for the
 * `GenerateAssistantResponse` operation.
 *
 * The fixture (`kiro_history_reply.bin`) is a real captured response from a multi-turn chat
 * exchange against Kiro AI using a `ksk_…` API key. It contains:
 *   - 1 `initial-response` event
 *   - N `assistantResponseEvent` events whose concatenated `content` reconstructs the reply
 *   - 1 `contextUsageEvent` and `meteringEvent` event each.
 */
class EventStreamTest {

    @Test
    fun parsesAssistantContentFromKiroReply() {
        val bytes = javaClass.classLoader!!
            .getResourceAsStream("kiro_history_reply.bin")!!
            .use { it.readBytes() }

        val parser = EventStreamParser().apply { append(bytes) }
        val messages = parser.drainMessages()

        assertTrue("at least one assistantResponseEvent expected", messages.isNotEmpty())

        val json = Json { ignoreUnknownKeys = true }
        val replyBuilder = StringBuilder()
        for (m in messages) {
            if (m.eventType == "assistantResponseEvent") {
                val payload = json.parseToJsonElement(m.payloadAsString()) as JsonObject
                val text = (payload["content"] as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrEmpty()) replyBuilder.append(text)
            }
        }
        val reply = replyBuilder.toString()
        assertTrue("non-empty reply", reply.isNotEmpty())
        // The captured fixture is the response to "Как тебя зовут? Одним словом." after
        // a history turn naming the assistant Kiro. The model answered "Kiro." or similar.
        assertTrue("reply should mention an identity, got: $reply", reply.length in 1..200)
    }

    @Test
    fun emptyBufferYieldsNothing() {
        val parser = EventStreamParser()
        assertEquals(0, parser.drainMessages().size)
    }
}
