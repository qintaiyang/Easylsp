package io.github.easylsp.app.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads at most [limit] bytes, including from ZIP entries and short socket reads. */
fun InputStream.readUpTo(limit: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(minOf(limit, 8192))
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
