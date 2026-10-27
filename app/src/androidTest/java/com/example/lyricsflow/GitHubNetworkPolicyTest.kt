package com.example.lyricsflow

import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.AppGraph
import com.example.lyricsflow.data.*
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GitHubNetworkPolicyTest {
    private class StalledConnection : HttpURLConnection(URL("https://api.github.com/user")) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disconnected = CountDownLatch(1)

        override fun connect() {}

        override fun usingProxy() = false

        override fun disconnect() {
            disconnected.countDown()
        }

        override fun getResponseCode(): Int {
            started.countDown()
            // Model a vendor DNS/socket that ignores both interruption and disconnect.
            while (true) try {
                release.await()
                break
            } catch (_: InterruptedException) {}
            return 200
        }

        override fun getInputStream() = ByteArrayInputStream("{}".toByteArray())
    }

    @Test
    fun hardDeadlineReturnsWithoutWaitingForBlockedVendorIoAndDropsLateReply() = runBlocking {
        val connection = StalledConnection()
        val http = GitHubHttp(timeoutMs = 250) { connection }
        val delivered = AtomicBoolean(false)
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            try {
                http.send("GET", "/user", null, "")
                delivered.set(true)
                fail("timeout missing")
            } catch (_: TimeoutCancellationException) {}
            assertTrue(connection.started.await(2, TimeUnit.SECONDS))
            assertTrue(
                "Cancellation should not wait for vendor IO",
                android.os.SystemClock.elapsedRealtime() - start < 2000,
            )
            assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS))
            connection.release.countDown()
            delay(100)
            assertFalse(delivered.get())
        } finally {
            connection.release.countDown()
        }
    }

    @Test
    fun initialDeviceCodeRequestCanBeCancelledBeforeAnyCodeIsReturned() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = AppGraph(context.applicationContext as android.app.Application)
        val slot = "github-cancel-initial-test"
        val started = CompletableDeferred<Unit>()
        val transport = GitHubTransport { _, _, _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        val sync = GitHubSync(graph, GitHubApi(transport), slot)
        try {
            assertEquals("Ov23liBtXrG7lSZomxKz", sync.state.value.clientId)
            withContext(Dispatchers.Main) { sync.signIn() }
            withTimeout(2000) { started.await() }
            assertTrue(sync.state.value.busy)
            assertTrue(sync.state.value.userCode.isEmpty())
            withContext(Dispatchers.Main) { sync.cancelSignIn() }
            withTimeout(2000) { while (sync.state.value.busy) delay(10) }
            assertEquals("", sync.state.value.login)
            assertEquals("已取消登录", sync.state.value.message)
        } finally {
            graph.scope.cancel()
            context.getSharedPreferences(slot, 0).edit().clear().commit()
        }
    }
}
