package io.github.easylsp.app.platform.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Analysis-only ELF section over an exact PT_LOAD byte window; never loaded into a target. */
object NativeCodeWindow {
    fun elf(vaddr: Long, instructions: ByteArray): ByteArray {
        val bytes = ByteArray(512 + instructions.size)
        val out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0, 0x464c457f); out.put(4, 2); out.put(5, 1); out.put(6, 1)
        out.putShort(16, 2); out.putShort(18, 183); out.putInt(20, 1)
        out.putLong(24, vaddr); out.putLong(40, 64)
        out.putShort(52, 64); out.putShort(58, 64); out.putShort(60, 3); out.putShort(62, 2)
        // Section 0 is null. Section 1 exposes only the requested original bytes at their ELF vaddr.
        out.putInt(128, 1); out.putInt(132, 1); out.putLong(136, 6)
        out.putLong(144, vaddr); out.putLong(152, 512); out.putLong(160, instructions.size.toLong()); out.putLong(176, 4)
        val names = "\u0000.text\u0000.shstrtab\u0000".toByteArray()
        out.putInt(192, 7); out.putInt(196, 3); out.putLong(216, 256); out.putLong(224, names.size.toLong()); out.putLong(240, 1)
        out.position(256); out.put(names); out.position(512); out.put(instructions)
        return bytes
    }
}
