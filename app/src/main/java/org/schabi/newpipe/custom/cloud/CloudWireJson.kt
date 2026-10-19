/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import java.security.MessageDigest

/** Exact JSON.stringify-compatible wire subset: ordered maps, arrays, integers, strings, booleans. */
internal object CloudWireJson {
    fun obj(text: String): JsonObject = JsonParser.`object`().from(text)
    fun array(text: String): JsonArray = JsonParser.array().from(text)
    fun encode(value: Any?): String = buildString { appendValue(value) }
    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")

            is String -> {
                append('"')
                var i = 0
                while (i < value.length) {
                    val c = value[i++]
                    when (c) {
                        '"' -> append("\\\"")

                        '\\' -> append("\\\\")

                        '\b' -> append("\\b")

                        '\u000c' -> append("\\f")

                        '\n' -> append("\\n")

                        '\r' -> append("\\r")

                        '\t' -> append("\\t")

                        else -> {
                            when {
                                c.code < 32 -> append("\\u").append(c.code.toString(16).padStart(4, '0'))

                                c.isHighSurrogate() -> {
                                    require(i < value.length && value[i].isLowSurrogate()) { "INVALID_WIRE_UNICODE" }
                                    append(c).append(value[i++])
                                }

                                c.isLowSurrogate() -> error("INVALID_WIRE_UNICODE")

                                else -> append(c)
                            }
                        }
                    }
                }
                append('"')
            }

            is Boolean, is Int, is Long, is Short, is Byte -> append(value.toString())

            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    require(entry.key is String) { "INVALID_WIRE_KEY" }
                    appendValue(entry.key)
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }

            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }

            else -> error("UNSUPPORTED_WIRE_VALUE")
        }
    }
    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        (it.toInt() and 255).toString(16).padStart(2, '0')
    }
    fun bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size
    fun head(value: Any?): CloudHead {
        val o = value as? Map<*, *> ?: error("INVALID_REMOTE_HEAD")
        require(o.keys == setOf("serviceGeneration", "library", "epoch", "revision", "headCounter", "fingerprint")) { "INVALID_REMOTE_HEAD" }
        return CloudHead(
            o["serviceGeneration"] as String,
            o["library"] as String,
            o["epoch"] as String,
            o["revision"] as String,
            o["headCounter"] as String,
            o["fingerprint"] as String
        )
    }
    fun fields(value: Any?, vararg keys: String): Map<*, *> = (value as? Map<*, *> ?: error("INVALID_WIRE_OBJECT")).also {
        require(it.keys == keys.toSet()) { "INVALID_WIRE_FIELDS" }
    }
    fun integer(value: Any?, min: Long = 0, max: Long = Int.MAX_VALUE.toLong()): Long {
        require(value is Long || value is Int || value is Short || value is Byte) { "INVALID_WIRE_INTEGER" }
        return (value as Number).toLong().also { require(it in min..max) { "WIRE_INTEGER_RANGE" } }
    }
}
