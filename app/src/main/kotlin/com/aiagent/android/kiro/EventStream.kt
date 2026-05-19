package com.aiagent.android.kiro

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Tiny parser for the AWS Event Stream (`application/vnd.amazon.eventstream`)
 * binary framing used by the CodeWhisperer Streaming `GenerateAssistantResponse`
 * operation.
 *
 * Each message on the wire looks like:
 *
 *     +--------+--------+--------+--------+
 *     |        total_length (uint32 BE)   |
 *     +--------+--------+--------+--------+
 *     |       headers_length (uint32 BE)  |
 *     +--------+--------+--------+--------+
 *     |        prelude_crc (uint32 BE)    |
 *     +--------+--------+--------+--------+
 *     |               headers             |
 *     +-----------------------------------+
 *     |               payload             |
 *     +--------+--------+--------+--------+
 *     |          message_crc (uint32)     |
 *     +--------+--------+--------+--------+
 *
 * Headers are a packed list of records:
 *
 *     header_name_len (uint8) | header_name | header_value_type (uint8) |
 *       header_value (varies by type)
 *
 * For our purposes we only need the string header type (`7`) which carries
 * `:event-type`, `:content-type` and `:message-type`. The CRCs are not
 * validated — AWS already authenticated the response over TLS.
 *
 * This parser is **stateful**: feed it bytes as they arrive (in our case the
 * whole response body buffered in memory; we don't stream in this build) and
 * call [drainMessages] to pull out any complete frames.
 */
class EventStreamParser {
    private val buf = ByteArrayOutputStream()

    fun append(chunk: ByteArray) {
        buf.write(chunk, 0, chunk.size)
    }

    /** Pull out every complete framed message currently buffered. */
    fun drainMessages(): List<EventMessage> {
        val data = buf.toByteArray()
        val out = ArrayList<EventMessage>()
        var i = 0
        while (i + 12 <= data.size) {
            val totalLen = ByteBuffer.wrap(data, i, 4).int
            if (totalLen <= 0 || totalLen > data.size - i) break
            val headerLen = ByteBuffer.wrap(data, i + 4, 4).int
            // prelude_crc at i+8..i+12, ignored
            val headersStart = i + 12
            val headersEnd = headersStart + headerLen
            val payloadEnd = i + totalLen - 4 // 4 bytes message_crc tail
            if (headersEnd > data.size || payloadEnd > data.size) break

            val headers = parseHeaders(data, headersStart, headersEnd)
            val payload = data.copyOfRange(headersEnd, payloadEnd)
            out.add(EventMessage(headers, payload))
            i += totalLen
        }
        // Keep any unconsumed tail in the buffer.
        if (i > 0) {
            val tail = data.copyOfRange(i, data.size)
            buf.reset()
            buf.write(tail, 0, tail.size)
        }
        return out
    }

    private fun parseHeaders(data: ByteArray, start: Int, end: Int): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var p = start
        while (p < end) {
            val nameLen = data[p].toInt() and 0xff; p += 1
            if (p + nameLen > end) return map
            val name = String(data, p, nameLen, Charsets.US_ASCII); p += nameLen
            if (p + 1 > end) return map
            val type = data[p].toInt() and 0xff; p += 1
            when (type) {
                7 -> { // string
                    if (p + 2 > end) return map
                    val valLen = ((data[p].toInt() and 0xff) shl 8) or (data[p + 1].toInt() and 0xff)
                    p += 2
                    if (p + valLen > end) return map
                    val value = String(data, p, valLen, Charsets.UTF_8)
                    p += valLen
                    map[name] = value
                }
                // The CodeWhisperer streaming response only uses string headers in practice.
                // For anything else, bail — we just lose this message rather than mis-parse.
                else -> return map
            }
        }
        return map
    }
}

/** A single AWS Event Stream message: its headers and raw payload bytes. */
data class EventMessage(
    val headers: Map<String, String>,
    val payload: ByteArray,
) {
    val eventType: String? get() = headers[":event-type"]
    val messageType: String? get() = headers[":message-type"]
    val contentType: String? get() = headers[":content-type"]

    fun payloadAsString(): String = String(payload, Charsets.UTF_8)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EventMessage) return false
        return headers == other.headers && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * headers.hashCode() + payload.contentHashCode()
}
