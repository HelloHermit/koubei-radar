package com.koubeiradar.app

import com.koubeiradar.app.platform.ShareInbox
import com.koubeiradar.app.platform.SharePayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShareInboxTest {
    private val payload = SharePayload("1. 鸿运楼老字号烤鸭 4.7分", emptyList())

    /** The bug this guards: with the import screen alive the payload is consumed before the app UI looks at `pending`. */
    @Test
    fun `an arrival is signalled even when the payload is consumed before anyone observes it`() = runTest {
        val inbox = ShareInbox()

        inbox.offer(payload)
        inbox.consume(payload)

        assertNull(inbox.pending.value)
        withTimeout(1_000) { inbox.arrivals.first() }
    }

    @Test
    fun `every offer is signalled once and a later collector sees nothing stale`() = runTest {
        val inbox = ShareInbox()
        inbox.offer(payload)
        inbox.arrivals.first()

        assertNull(withTimeoutOrNull(100) { inbox.arrivals.first() })

        inbox.offer(payload.copy(text = "second"))
        inbox.arrivals.first()
        assertEquals("second", inbox.pending.value?.text)
    }
}
