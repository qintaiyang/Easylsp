package io.github.easylsp.app

import io.github.easylsp.app.platform.diagnostics.*
import io.github.easylsp.app.platform.tools.BuiltinToolSchemas
import io.github.easylsp.app.platform.tools.ToolArguments
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DiagnosticEvidenceTest {
    private val fixture = """
        *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
        Timestamp: 2026-11-05 08:00:00.123456789+0800
        pid: 1234, tid: 1234, name: fixture  >>> example.target:worker <<<
        uid: 10123
        signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x10
            x0  0000000000000010  x1  0000000000000001
            lr  0000007000012344  sp  0000007fffffff00  pc  0000007000012340  pstate 0000000060001000
        backtrace:
              #00 pc 0000000000012340  /data/app/example/lib/arm64/libfixture.so (known_fault+4) (BuildId: abc123)
              #01 pc 0000000000005678  /data/app/example/split.apk!libfixture.so (offset 0x2000)
    """.trimIndent()
    private val time = Instant.parse("2026-11-05T00:00:00.123Z").toEpochMilli()
    private val target = DiagnosticTarget("example.target", 10123, "*")

    @Test fun relativeFramePcDoesNotOverwriteRuntimeRegisterPcOrLoseSource() {
        val parsed = DiagnosticEvidence.tombstone(fixture, "raw/tombstone_01")!!
        assertEquals(time, parsed.getLong("timestamp"))
        assertEquals("0x0000007000012340", parsed.getJSONObject("registers").getString("pc"))
        val frame = parsed.getJSONArray("frames").getJSONObject(0)
        assertEquals("TOMBSTONE_RELATIVE_PC", frame.getString("addressKind"))
        assertEquals("0x0000000000012340", frame.getString("pc"))
        assertEquals("abc123", frame.getString("buildId"))
        assertEquals(9, frame.getInt("line"))
        assertEquals("raw/tombstone_01", frame.getString("source"))
        assertEquals("UNCONFIRMED", parsed.getString("moduleMatch"))
    }
    @Test fun tombstoneRequiresMatchingUidProcessAndContentTime() {
        val parsed = DiagnosticEvidence.tombstone(fixture, "raw/tombstone_01")!!
        assertTrue(DiagnosticEvidence.belongs(parsed, target, time - 10, time + 10))
        assertFalse(DiagnosticEvidence.belongs(parsed, target.copy(uid = 110123), time - 10, time + 10))
        assertFalse(DiagnosticEvidence.belongs(parsed, target.copy(processName = "example.target"), time - 10, time + 10))
        assertFalse(DiagnosticEvidence.belongs(parsed, target, time + 1, time + 100))
        parsed.remove("timestamp")
        assertFalse(DiagnosticEvidence.belongs(parsed, target, time - 10, time + 10))
    }
    @Test fun otherThreadBacktracesNeverReplaceTheCrashingRegistersOrFrames() {
        val text = fixture + "\n--- --- --- ---\npid: 1234, tid: 1235, name: main >>> example.target:worker <<<\n" +
            "    lr  0000000000000099  sp  0000000000000088  pc  0000000000000077\nbacktrace:\n    #00 pc 0000000000000077 /system/lib64/libc.so\n"
        val parsed = DiagnosticEvidence.tombstone(text, "raw/tombstone_01")!!
        assertEquals("0x0000007000012340", parsed.getJSONObject("registers").getString("pc"))
        assertEquals(2, parsed.getJSONArray("frames").length())
    }
    @Test fun processScopeDoesNotMatchPrefixLookalikes() {
        assertTrue(target.matches("example.target:worker"))
        assertFalse(target.matches("example.target.other"))
        assertFalse(target.matches("another.target"))
    }
    private fun exit(pid: Int, uid: Int, process: String, reason: Int, status: Int, stamp: String = "2026-11-05 08:00:00.123") = """
        ApplicationExitInfo #$pid:
          timestamp=$stamp pid=$pid realUid=$uid packageUid=$uid definingUid=$uid user=0
          process=$process reason=$reason (test reason) subreason=0 (UNKNOWN) status=$status
          importance=100 description=fixture state=empty trace=null
    """.trimIndent()
    @Test fun exitWindowExcludesOldForceStopOtherUidAndOtherProcess() {
        val text = listOf(exit(1, 10123, "example.target", 1, 42), exit(2, 10123, "example.target", 10, 0, "2026-11-05 07:59:59.000"),
            exit(3, 110123, "example.target", 5, 0), exit(4, 10123, "example.target.other", 4, 0)).joinToString("\n")
        val rows = DiagnosticEvidence.exits(text, target, time - 1, time + 1, ZoneId.of("Asia/Shanghai"))
        assertEquals(1, rows.length())
        assertEquals("SELF_EXIT", rows.getJSONObject(0).getString("classification"))
        assertEquals(42, rows.getJSONObject(0).getInt("status"))
        assertTrue(rows.getJSONObject(0).isNull("signal"))
    }
    @Test fun fatalSignalsAndKillNeverImplyDetectionOrInventAStack() {
        val text = exit(1, 10123, "example.target", 2, 9) + "\n" + exit(2, 10123, "example.target:worker", 2, 6)
        val rows = DiagnosticEvidence.exits(text, target, time - 1, time + 1, ZoneId.of("Asia/Shanghai"))
        assertEquals("SIGNALLED", rows.getJSONObject(0).getString("classification"))
        assertEquals("NATIVE_FATAL_SIGNAL", rows.getJSONObject(1).getString("classification"))
        assertFalse(rows.getJSONObject(0).has("frames"))
        assertEquals("MULTIPLE", DiagnosticEvidence.conclusion(JSONArray(), rows).getString("classification"))
        assertEquals("UNKNOWN", DiagnosticEvidence.conclusion(JSONArray(), JSONArray()).getString("classification"))
    }
    @Test fun collectionLimitIsRejectedBeforeTheActionRuns() {
        val schema = BuiltinToolSchemas.create("start_native_diagnostic", mapOf("mode" to "string", "restart" to "boolean", "durationSeconds" to "integer"), emptyList())
        for (seconds in listOf(0, 121)) {
            assertTrue(runCatching { ToolArguments.validate(schema, JSONObject().put("durationSeconds", seconds)) }.isFailure)
        }
        ToolArguments.validate(schema, JSONObject().put("durationSeconds", 5))
    }
}
