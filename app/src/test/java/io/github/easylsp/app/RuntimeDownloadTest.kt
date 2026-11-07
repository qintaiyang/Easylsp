package io.github.easylsp.app

import io.github.easylsp.app.platform.network.RuntimeDownload
import io.github.easylsp.app.platform.network.RuntimeSources
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class RuntimeDownloadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun resumesPartialArchiveAndReportsTotalProgress() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setBody("def"))
            server.start()
            val partial = temporary.newFile().apply { writeText("abc") }
            val archive = File(temporary.root, "archive")
            val progress = mutableListOf<Pair<Long, Long>>()
            RuntimeDownload.fetch(server.url("/archive").toString(), archive, partial) { read, total -> progress += read to total }
            assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
            assertEquals("abcdef", archive.readText())
            assertFalse(partial.exists())
            assertEquals(3L to 6L, progress.first())
            assertEquals(6L to 6L, progress.last())
        }
    }

    @Test fun replacesPartialIfServerIgnoresRangeAndPreservesItOnFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("abcdef"))
            server.start()
            val partial = temporary.newFile().apply { writeText("abc") }
            val archive = File(temporary.root, "archive")
            assertTrue(runCatching { RuntimeDownload.fetch(server.url("/archive").toString(), archive, partial) { _, _ -> } }.isFailure)
            assertEquals("abc", partial.readText())
            RuntimeDownload.fetch(server.url("/archive").toString(), archive, partial) { _, _ -> }
            assertEquals("abcdef", archive.readText())
        }
    }

    @Test fun choosesFastestSuccessfulDownloadAndReportsUnavailableSources() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/slow" -> MockResponse().setBody("x".repeat(131072)).setBodyDelay(500, TimeUnit.MILLISECONDS)
                    "/fast" -> MockResponse().setBody("x".repeat(131072))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val sources = listOf("slow", "fast", "missing").map { RuntimeSources.Source(it, server.url("/$it").toString()) }
            val log = java.util.Collections.synchronizedList(mutableListOf<String>())
            assertEquals("fast", RuntimeSources().fastest("fixture", sources) { log += it }.name)
            assertTrue(log.any { it.contains("missing 不可用") })
            assertTrue(runCatching { RuntimeSources().fastest("fixture", listOf(sources.last())) {} }.isFailure)
        }
    }
}
