package io.github.easylsp.app

import io.github.easylsp.app.feature.chat.ChatCommands
import io.github.easylsp.app.platform.skills.SkillMetadata
import io.github.easylsp.app.platform.tools.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class Phase6CapabilitiesTest {
    @Test fun yamlFrontmatterUsesActualYamlSemantics() {
        val meta = SkillMetadata.parse("---\nname: 'quoted: name'\ndescription: >-\n  first line\n  second line\n---\nbody")
        assertEquals("quoted: name", meta.first)
        assertEquals("first line second line", meta.second)
        assertThrows(IllegalArgumentException::class.java) { SkillMetadata.parse("---\nname: true\ndescription: text\n---") }
    }
    @Test fun skillReferencesPinningRevisionAndExplicitSelectionAreIndependent() {
        var revision = 1L
        var saved = JSONObject()
        val tools = listOf("shared", "exclusive").map { name -> ToolDefinition(name, name, JSONObject(), invoke = { JSONObject() }) }
        fun caps() = ToolCapabilities(tools, saved = saved, persist = { saved = JSONObject(it.toString()) }, loadSkill = { id ->
            SkillGuidance(id, revision, "guidance", listOf(id), emptyList(), listOf("GLOBAL", "PROJECT"))
        }, groups = mapOf("one" to listOf("shared", "exclusive"), "two" to listOf("shared")), skillRevision = { revision })
        val caps = caps()
        assertEquals("guidance", caps.activate("one").getString("instructions"))
        assertTrue(caps.activate("one").isNull("instructions"))
        caps.activate("two", pinned = true)
        caps.select(JSONObject().put("names", JSONArray().put("exclusive")))
        caps.resolve(); caps.release("one"); caps.resolve()
        assertTrue(caps.permits("shared")); assertTrue(caps.permits("exclusive"))
        assertFalse(caps.release("two").getBoolean("released"))
        val restored = caps(); restored.release("two", user = true); restored.resolve()
        assertFalse(restored.permits("shared")); assertTrue(restored.permits("exclusive"))
        restored.activate("one"); revision++; restored.resolve()
        assertFalse(restored.permits("shared")); assertEquals("guidance", restored.activate("one").getString("instructions"))
    }
    @Test fun unifiedDiffPreservesLinesAndRejectsMismatchedContextBeforeWriting() {
        val directory = Files.createTempDirectory("patch").toFile()
        try {
            val file = directory.resolve("a.txt").apply { writeText("a\nb\n") }
            fun patch(body: String) = UnifiedPatch.parse("--- a/a.txt\n+++ b/a.txt\n$body", directory::resolve)[file]
            assertEquals("a\nx\nb\n", patch("@@ -1,0 +2 @@\n+x\n"))
            assertEquals("a\nb\nc\n", patch("@@ -2,0 +3 @@\n+c\n"))
            assertEquals("a\nz", patch("@@ -2 +2 @@\n-b\n+z\n\\ No newline at end of file\n"))
            file.writeText("a")
            assertEquals("z\n", patch("@@ -1 +1 @@\n-a\n\\ No newline at end of file\n+z\n"))
            assertThrows(IllegalArgumentException::class.java) { patch("@@ -1 +1 @@\n-no\n+z\n") }
            assertEquals("a", file.readText())
            assertNull(UnifiedPatch.parse("--- a/a.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-a\n\\ No newline at end of file\n", directory::resolve)[file])
        } finally { directory.deleteRecursively() }
    }
    @Test fun commandsAndEscapesDoNotConfuseLiteralMessages() {
        assertNull(ChatCommands.parse("//compact")); assertEquals("/compact", ChatCommands.literal("//compact"))
        assertNull(ChatCommands.parse("ordinary /compact"))
        assertTrue(ChatCommands.parse("/compact")!!.known)
        assertFalse(ChatCommands.parse("/unknown keep draft")!!.known)
        assertFalse(ChatCommands.parse("/new new title")!!.known)
        assertFalse(ChatCommands.parse("/compact custom instruction")!!.known)
        assertFalse(ChatCommands.parse("/compact\nordinary text")!!.known)
        assertTrue(ChatCommands.parse("/skills use workspace")!!.known)
        assertTrue(ChatCommands.parse("/skills release workspace")!!.known)
        assertFalse(ChatCommands.parse("/skills use")!!.known)
    }
}
