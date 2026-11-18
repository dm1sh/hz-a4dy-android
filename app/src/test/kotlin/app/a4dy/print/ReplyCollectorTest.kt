// SPDX-License-Identifier: MIT
package app.a4dy.print

import app.a4dy.print.bluetooth.ReplyCollector
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ReplyCollectorTest {
    @Test fun gathersFragmentedRepliesUntilQuietGap() = runBlocking {
        val input = Channel<ByteArray>(4)
        launch { input.send("O".toByteArray()); delay(5); input.send("K".toByteArray()) }
        assertArrayEquals("OK".toByteArray(), ReplyCollector.collect(input, 500, quietMs = 30))
    }
    @Test fun timesOutWithoutData() = runBlocking {
        assertEquals(0, ReplyCollector.collect(Channel(), 5).size)
    }
    @Test fun closedConnectionDoesNotHang() = runBlocking {
        val input = Channel<ByteArray>(); input.close()
        assertEquals(0, ReplyCollector.collect(input, 500).size)
    }
    @Test fun oversizedUnsolicitedReplyFailsBoundedly() = runBlocking {
        val input = Channel<ByteArray>(2); input.send(ByteArray(256)); input.send(byteArrayOf(1))
        assertTrue(runCatching { ReplyCollector.collect(input, 500) }.exceptionOrNull() is IllegalArgumentException)
    }
}
