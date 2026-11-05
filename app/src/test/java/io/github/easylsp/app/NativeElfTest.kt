package io.github.easylsp.app

import io.github.easylsp.app.platform.analysis.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeElfTest {
    private val elf = NativeElf(183, 3, 0, listOf(
        ElfSegment(0, 0x10000, 0x1000, 0x1000, 4, 0x1000),
        ElfSegment(0x2000, 0x18000, 0x1800, 0x2000, 5, 0x1000)), "abc")
    @Test fun loadBiasUsesFileOffsetAndNonzeroVirtualAddress() {
        val bias = elf.loadBias(0x70019000, 0x3000, 4096)
        assertEquals(0x70000000, bias)
        val vaddr = 0x70019100 - bias
        assertEquals(0x19100, vaddr)
        assertEquals(0x3100L, elf.fileOffset(vaddr))
        assertEquals(vaddr, elf.virtualAddress(0x3100))
    }
    @Test fun zeroFilledMemoryHasNoFileOffset() {
        assertTrue(elf.contains(0x19f00))
        assertNull(elf.fileOffset(0x19f00))
        assertFalse(elf.contains(0x20000))
    }
    @Test fun apkOffsetMustFirstRemoveEntryDataStart() {
        val apkOffset = 0x103000L; val dataStart = 0x100000L
        assertEquals(0x70000000, elf.loadBias(0x70019000, apkOffset - dataStart, 4096))
        assertTrue(runCatching { elf.loadBias(0x70019000, apkOffset, 4096) }.isFailure)
    }
    @Test fun unalignedLoadSegmentAndSixteenKPagesKeepTheCorrectBias() {
        val value = NativeElf(183, 3, 0, listOf(ElfSegment(0x4123, 0x10123, 0x7000, 0x8000, 5, 0x4000)), null)
        assertEquals(0x70000000, value.loadBias(0x70010000, 0x4000, 16384))
        assertEquals(0x4123L, value.fileOffset(0x10123))
    }
    @Test fun parserReadsExistingBuildIdAndRejectsTruncatedProgramHeaders() {
        val bytes = ByteArray(256)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 0x464c457f); buffer.put(4, 2); buffer.put(5, 1); buffer.putShort(16, 3); buffer.putShort(18, 183)
        buffer.putLong(32, 64); buffer.putShort(54, 56); buffer.putShort(56, 2)
        buffer.putInt(64, 1); buffer.putInt(68, 5); buffer.putLong(72, 0); buffer.putLong(80, 0x10000); buffer.putLong(96, 256); buffer.putLong(104, 512); buffer.putLong(112, 4096)
        buffer.putInt(120, 4); buffer.putLong(128, 192); buffer.putLong(152, 20)
        buffer.putInt(192, 4); buffer.putInt(196, 4); buffer.putInt(200, 3)
        buffer.position(204); buffer.put(byteArrayOf(71,78,85,0,1,2,3,4))
        val value = NativeElf.read(bytes.size.toLong()) { offset, count -> bytes.copyOfRange(offset.toInt(), offset.toInt() + count) }
        assertEquals("01020304", value.buildId)
        assertEquals(0x10000L, value.loads.single().vaddr)
        assertTrue(runCatching { NativeElf.read(100) { offset, count -> bytes.copyOfRange(offset.toInt(), offset.toInt() + count) } }.isFailure)
    }
}
