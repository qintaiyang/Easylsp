package io.github.easylsp.app

import io.github.easylsp.app.util.readUpTo
import org.junit.Assert.*
import org.junit.Test
import java.io.FilterInputStream

class InputStreamsTest {
    @Test fun boundedReadHandlesShortReadsAndLeavesRemainingBytes() {
        val stream = object : FilterInputStream(byteArrayOf(1, 2, 3, 4, 5).inputStream()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(length, 1))
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), stream.readUpTo(3))
        assertArrayEquals(byteArrayOf(4, 5), stream.readUpTo(8))
        assertArrayEquals(byteArrayOf(), stream.readUpTo(1))
        assertArrayEquals(byteArrayOf(), byteArrayOf(1).inputStream().readUpTo(0))
    }
}
