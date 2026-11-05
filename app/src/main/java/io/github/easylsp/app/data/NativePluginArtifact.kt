package io.github.easylsp.app.data

import io.github.easylsp.app.platform.analysis.NativeElf
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The native ZIP acceptance boundary; build artifacts are checked by the SDK compiler task. */
object NativePluginArtifact {
    fun validate(bytes: ByteArray) {
        require(bytes.size in 64..16 * 1024 * 1024) { "native SO 上限 16 MiB" }
        val elf = NativeElf.read(bytes.size.toLong()) { offset, count -> bytes.copyOfRange(offset.toInt(), offset.toInt() + count) }
        require(elf.type == 3 && elf.loads.isNotEmpty()) { "native 插件必须为 AArch64 ET_DYN" }
        require(elf.loads.all { it.alignment >= 16384 && it.alignment and (it.alignment - 1) == 0L && it.offset % it.alignment == it.vaddr % it.alignment }) { "native 插件需要 16 KB PT_LOAD 对齐" }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun range(offset: Long, length: Long): Int { require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset) { "ELF 数据超出文件范围" }; return offset.toInt() }
        fun address(address: Long, size: Long): Int {
            val segment = requireNotNull(elf.loads.firstOrNull { address >= it.vaddr && address - it.vaddr <= it.fileSize && size <= it.fileSize - (address - it.vaddr) }) { "ELF 动态地址不在文件 PT_LOAD 内" }
            return range(segment.offset + address - segment.vaddr, size)
        }
        val dynamic = mutableMapOf<Long, MutableList<Long>>()
        var api = 0
        repeat(data.getShort(56).toInt() and 65535) { index ->
            val ph = range(data.getLong(32) + index * (data.getShort(54).toInt() and 65535).toLong(), 56)
            val offset = data.getLong(ph + 8); val size = data.getLong(ph + 32)
            when (data.getInt(ph)) {
                2 -> { var cursor = offset; while (cursor + 16 <= offset + size) {
                    val at = range(cursor, 16); val tag = data.getLong(at); if (tag == 0L) break
                    dynamic.getOrPut(tag) { mutableListOf() }.add(data.getLong(at + 8)); cursor += 16
                } }
                4 -> { var cursor = offset; while (cursor + 12 <= offset + size) {
                    val at = range(cursor, 12); val nameSize = data.getInt(at); val descSize = data.getInt(at + 4); val type = data.getInt(at + 8)
                    require(nameSize >= 0 && descSize >= 0)
                    val nameStart = range(cursor + 12, nameSize.toLong())
                    val name = bytes.copyOfRange(nameStart, nameStart + nameSize)
                    val desc = cursor + 12 + (nameSize.toLong() + 3) / 4 * 4
                    require(desc + descSize <= offset + size)
                    if (name.contentEquals("Android\u0000".toByteArray()) && type == 1 && descSize >= 4) api = data.getInt(range(desc, 4))
                    cursor = desc + (descSize.toLong() + 3) / 4 * 4
                } }
            }
        }
        require(api == 34) { "native 首版要求 Android API 34 note，实际 $api" }
        fun value(tag: Long) = requireNotNull(dynamic[tag]?.singleOrNull()) { "缺少或重复 ELF 动态项 $tag" }
        val stringSize = value(10); val strings = address(value(5), stringSize)
        fun string(index: Long): String {
            require(index in 0 until stringSize)
            var end = strings + index.toInt(); val start = end
            while (end < strings + stringSize && bytes[end] != 0.toByte()) end++
            require(end < strings + stringSize)
            return bytes.copyOfRange(start, end).toString(Charsets.UTF_8)
        }
        val dependencies = dynamic[1].orEmpty().map(::string)
        require(dependencies.all { it in setOf("libc.so", "libdl.so", "liblog.so", "libm.so") }) { "不支持的 native 动态依赖：$dependencies" }
        val symbols = if (dynamic.containsKey(4)) data.getInt(address(value(4), 8) + 4) else {
            val hash = address(value(0x6ffffef5), 16)
            val buckets = data.getInt(hash); val first = data.getInt(hash + 4); val bloom = data.getInt(hash + 8)
            require(buckets > 0 && bloom > 0 && first >= 0)
            val bucketsAt = range(hash.toLong() + 16 + bloom.toLong() * 8, buckets.toLong() * 4)
            var last = first - 1
            repeat(buckets) { last = maxOf(last, data.getInt(bucketsAt + it * 4)) }
            if (last < first) first else {
                val chains = bucketsAt.toLong() + buckets.toLong() * 4
                while (data.getInt(range(chains + (last - first).toLong() * 4, 4)) and 1 == 0) last++
                last + 1
            }
        }
        require(symbols in 1..100000 && value(11) == 24L)
        val symbolAt = address(value(6), symbols * 24L)
        val exports = mutableListOf<String>()
        repeat(symbols) { index ->
            val at = symbolAt + index * 24; val info = data.get(at + 4).toInt() and 255
            if (data.getShort(at + 6).toInt() != 0 && info shr 4 in 1..2 && data.get(at + 5).toInt() and 3 in setOf(0, 3)) {
                require(info and 15 == 2) { "native 只允许导出 SDK 函数入口" }
                exports += string(data.getInt(at).toLong() and 0xffffffffL)
            }
        }
        require(exports == listOf("easylsp_plugin_init_v1")) { "native 只允许导出 easylsp_plugin_init_v1：$exports" }
    }
}
