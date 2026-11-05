package io.github.easylsp.app.platform.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rebuilds the AXML string pool; indices and all XML nodes remain unchanged. */
object BinaryManifest {
    fun renamePackage(xml: ByteArray, oldPackage: String, newPackage: String): ByteArray {
        val input = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN)
        require(input.getShort(0).toInt() == 3) { "需要二进制 AndroidManifest.xml" }
        var offset = input.getShort(2).toInt(); var changed = false
        val result = ByteArrayOutputStream(); result.write(xml, 0, offset)
        while (offset < xml.size) {
            val type = input.getShort(offset).toInt() and 0xffff
            val size = input.getInt(offset + 4)
            require(size > 0 && offset + size <= xml.size)
            if (type != 1) result.write(xml, offset, size) else {
                val headerSize = input.getShort(offset + 2).toInt() and 0xffff
                val count = input.getInt(offset + 8); val styles = input.getInt(offset + 12)
                val utf8 = input.getInt(offset + 16) and 0x100 != 0
                val stringsStart = input.getInt(offset + 20); val stylesStart = input.getInt(offset + 24)
                val strings = ByteArrayOutputStream(); val indices = IntArray(count)
                fun length8(at: Int): Pair<Int, Int> { val first = xml[at].toInt() and 255; return if (first and 128 == 0) first to (at + 1) else (((first and 127) shl 8) or (xml[at + 1].toInt() and 255)) to (at + 2) }
                fun length16(at: Int): Pair<Int, Int> { val first = input.getShort(at).toInt() and 65535; return if (first and 32768 == 0) first to (at + 2) else (((first and 32767) shl 16) or (input.getShort(at + 2).toInt() and 65535)) to (at + 4) }
                fun put8(length: Int) { if (length > 127) strings.write((length shr 8) or 128); strings.write(length and 255) }
                fun put16(length: Int) { if (length > 32767) { val high = (length ushr 16) or 32768; strings.write(high and 255); strings.write(high ushr 8) }; strings.write(length and 255); strings.write((length ushr 8) and 255) }
                repeat(count) { index ->
                    val at = offset + stringsStart + input.getInt(offset + headerSize + index * 4)
                    val value = if (utf8) { val (_, afterChars) = length8(at); val (bytes, start) = length8(afterChars); String(xml, start, bytes, Charsets.UTF_8) }
                    else { val (chars, start) = length16(at); String(xml, start, chars * 2, Charsets.UTF_16LE) }
                    val replacement = if (value == oldPackage) { changed = true; newPackage } else value
                    indices[index] = strings.size()
                    if (utf8) { val bytes = replacement.toByteArray(Charsets.UTF_8); put8(replacement.length); put8(bytes.size); strings.write(bytes); strings.write(0) }
                    else { put16(replacement.length); strings.write(replacement.toByteArray(Charsets.UTF_16LE)); strings.write(0); strings.write(0) }
                }
                while (strings.size() % 4 != 0) strings.write(0)
                val start = headerSize + (count + styles) * 4
                val styleBytes = if (stylesStart != 0) xml.copyOfRange(offset + stylesStart, offset + size) else byteArrayOf()
                val block = ByteBuffer.allocate(start + strings.size() + styleBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                block.put(xml, offset, headerSize); block.putInt(4, block.capacity()); block.putInt(16, input.getInt(offset + 16) and 1.inv()); block.putInt(20, start); block.putInt(24, if (stylesStart == 0) 0 else start + strings.size())
                block.position(headerSize); indices.forEach(block::putInt)
                block.put(xml, offset + headerSize + count * 4, styles * 4)
                block.put(strings.toByteArray()); block.put(styleBytes); result.write(block.array())
            }
            offset += size
        }
        require(changed) { "模板包名未找到" }
        return result.toByteArray().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, it.size) }
    }
}
