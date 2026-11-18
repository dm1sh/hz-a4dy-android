// SPDX-License-Identifier: MIT
package app.a4dy.print.bluetooth

import android.os.SystemClock
import app.a4dy.core.ReplyBuffer
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

/** No self-identifying delimiter was recovered. This quiet-gap framing is a bounded heuristic.
 * The vendor instead polls available() after 100 ms; neither is hardware validation.
 */
internal object ReplyCollector {
    suspend fun collect(source: ReceiveChannel<ByteArray>, timeoutMs: Long, quietMs: Long = 120, windowMs: Long = 1500): ByteArray {
        val first = withTimeoutOrNull(timeoutMs) { source.receiveCatching().getOrNull() } ?: return byteArrayOf()
        val buffer = ReplyBuffer(); buffer.append(first)
        val deadline = SystemClock.elapsedRealtime() + windowMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)
            val more = withTimeoutOrNull(minOf(quietMs, remaining)) { source.receiveCatching().getOrNull() } ?: break
            buffer.append(more)
        }
        return buffer.bytes()
    }
}
