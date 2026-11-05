package io.github.easylsp.app.platform.analysis

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ElfSegment(val offset: Long, val vaddr: Long, val fileSize: Long, val memorySize: Long, val flags: Int, val alignment: Long) {
    fun json() = JSONObject().put("offset", hex(offset)).put("vaddr", hex(vaddr)).put("fileSize", fileSize).put("memorySize", memorySize).put("flags", flags).put("alignment", alignment)
}
fun hex(value: Long) = "0x" + value.toULong().toString(16)
fun nativeAddress(value: String): Long = value.removePrefix("0x").toULong(16).toLong()

/** ELF64 little-endian metadata. Bounds belong here because APK/ELF bytes are external inputs. */
data class NativeElf(val machine: Int, val type: Int, val entry: Long, val loads: List<ElfSegment>, val buildId: String?) {
    fun json() = JSONObject().put("class", "ELF64").put("byteOrder", "LITTLE_ENDIAN").put("machine", machine).put("type", type)
        .put("entry", hex(entry)).put("buildId", buildId ?: JSONObject.NULL).put("loadSegments", JSONArray(loads.map { it.json() }))
    fun fileOffset(vaddr: Long): Long? = loads.firstOrNull { vaddr >= it.vaddr && vaddr - it.vaddr < it.fileSize }?.let { it.offset + vaddr - it.vaddr }
    fun virtualAddress(offset: Long): Long = requireNotNull(loads.firstOrNull { offset >= it.offset && offset - it.offset < it.fileSize }) { "文件偏移不在 PT_LOAD 的文件覆盖范围内" }.let { it.vaddr + offset - it.offset }
    fun contains(vaddr: Long) = loads.any { vaddr >= it.vaddr && vaddr - it.vaddr < it.memorySize }
    fun loadBias(mappingStart: Long, mappingOffset: Long, pageSize: Long, executable: Boolean = false): Long {
        require(pageSize == 4096L || pageSize == 16384L || pageSize == 65536L) { "需要诊断记录的实际页大小" }
        fun floor(value: Long) = value / pageSize * pageSize
        val candidates = loads.filter { (!executable || it.flags and 1 != 0) && mappingOffset >= floor(it.offset) && mappingOffset < (it.offset + it.fileSize + pageSize - 1) / pageSize * pageSize }
            .map { mappingStart - (floor(it.vaddr) + mappingOffset - floor(it.offset)) }.distinct()
        require(candidates.size == 1) { "映射与 PT_LOAD 不能唯一确定 load bias；请指定对应映射，不能减去可执行映射起点" }
        return candidates.single()
    }
    companion object {
        fun read(size: Long, bytes: (Long, Int) -> ByteArray): NativeElf {
            fun at(offset: Long, count: Int): ByteBuffer {
                require(offset >= 0 && count >= 0 && offset <= size && count.toLong() <= size - offset) { "ELF 元数据超出文件范围" }
                return ByteBuffer.wrap(bytes(offset, count)).order(ByteOrder.LITTLE_ENDIAN)
            }
            val header = at(0, 64)
            require(header.getInt(0) == 0x464c457f && header.get(4).toInt() == 2 && header.get(5).toInt() == 1) { "仅支持 ELF64 little-endian" }
            val machine = header.getShort(18).toInt() and 65535
            require(machine == 183) { "首版只分析 AArch64 ELF（machine=$machine）" }
            val count = header.getShort(56).toInt() and 65535
            val stride = header.getShort(54).toInt() and 65535
            require(count in 1..4096 && stride >= 56) { "不支持的 ELF program header 表" }
            val loads = mutableListOf<ElfSegment>()
            var buildId: String? = null
            for (i in 0 until count) {
                val ph = at(Math.addExact(header.getLong(32), i.toLong() * stride), 56)
                val type = ph.getInt(0)
                val offset = ph.getLong(8); val vaddr = ph.getLong(16); val length = ph.getLong(32); val memory = ph.getLong(40)
                require(offset >= 0 && length >= 0 && offset <= size && length <= size - offset) { "ELF segment 超出文件范围" }
                if (type == 1) {
                    require(vaddr >= 0 && memory >= length && vaddr <= Long.MAX_VALUE - memory)
                    loads += ElfSegment(offset, vaddr, length, memory, ph.getInt(4), ph.getLong(48))
                }
                if (type == 4) {
                    require(length <= 1024 * 1024) { "ELF note 段过大" }
                    val note = at(offset, length.toInt())
                    while (note.remaining() >= 12) {
                        val nameSize = note.int; val dataSize = note.int; val noteType = note.int
                        require(nameSize >= 0 && dataSize >= 0 && nameSize <= note.remaining()) { "ELF note 长度无效" }
                        val name = ByteArray(nameSize).also(note::get)
                        val padding = (4 - nameSize % 4) % 4
                        require(padding <= note.remaining()); note.position(note.position() + padding)
                        require(dataSize <= note.remaining()) { "ELF note 数据不完整" }
                        val data = ByteArray(dataSize).also(note::get)
                        if (noteType == 3 && name.contentEquals(byteArrayOf(71, 78, 85, 0))) buildId = data.joinToString("") { "%02x".format(it.toInt() and 255) }
                        val tail = (4 - dataSize % 4) % 4
                        require(tail <= note.remaining()); note.position(note.position() + tail)
                    }
                }
            }
            return NativeElf(machine, header.getShort(16).toInt() and 65535, header.getLong(24), loads, buildId)
        }
    }
}
