/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.channels

import kotlinx.coroutines.internal.*
import kotlinx.coroutines.selects.*
import kotlin.jvm.*
import kotlin.native.concurrent.*

@JvmField
@SharedImmutable
internal val EMPTY = Symbol("EMPTY")

/**
 * Channel that buffers at most one element and conflates all subsequent `send` and `offer` invocations,
 * so that the receiver always gets the most recently sent element.
 * Back-to-send sent elements are _conflated_ -- only the most recently sent element is received,
 * while previously sent elements **are lost**.
 * Sender to this channel never suspends and [offer] always returns `true`.
 *
 * This channel is created by `Channel(Channel.CONFLATED)` factory function invocation.
 */
internal open class ConflatedChannel<E> : AbstractChannel<E>() {
    protected final override val isBufferAlwaysEmpty: Boolean get() = false
    protected final override val isBufferEmpty: Boolean get() = state.value === EMPTY
    protected final override val isBufferAlwaysFull: Boolean get() = false
    protected final override val isBufferFull: Boolean get() = false

    override val isEmpty: Boolean get() = state.withLock { isEmptyImpl }

    private val state = ConflatedChannelState()

    // result is `OFFER_SUCCESS | Closed`
    protected override fun offerInternal(element: E): Any {
        var receive: ReceiveOrClosed<E>? = null
        var token: Any? = null
        state.withLock {
            closedForSend?.let { return it }
            // if there is no element written in buffer
            if (state.value === EMPTY) {
                // check for receivers that were waiting on the empty buffer
                loop@ while(true) {
                    receive = takeFirstReceiveOrPeekClosed() ?: break@loop // break when no receivers queued
                    if (receive is Closed) {
                        return receive!!
                    }
                    token = receive!!.tryResumeReceive(element, null)
                    if (token != null) return@withLock
                }
            }
            state.value = element
            return OFFER_SUCCESS
        }
        // breaks here if offer meets receiver
        receive!!.completeResumeReceive(element, token!!)
        return receive!!.offerResult
    }

    // result is `ALREADY_SELECTED | OFFER_SUCCESS | Closed`
    protected override fun offerSelectInternal(element: E, select: SelectInstance<*>): Any {
        var receive: ReceiveOrClosed<E>? = null
        var token: Any? =  null
        state.withLock {
            closedForSend?.let { return it }
            if (state.value === EMPTY) {
                loop@ while(true) {
                    val offerOp = describeTryOffer(element)
                    val failure = select.performAtomicTrySelect(offerOp)
                    when {
                        failure == null -> { // offered successfully
                            receive = offerOp.result
                            token = offerOp.takeToken()
                            return@withLock
                        }
                        failure === OFFER_FAILED -> break@loop // cannot offer -> Ok to queue to buffer
                        failure === RETRY_ATOMIC -> {} // retry
                        failure === ALREADY_SELECTED || failure is Closed<*> -> return failure
                        else -> error("performAtomicTrySelect(describeTryOffer) returned $failure")
                    }
                }
            }
            // try to select sending this element to buffer
            if (!select.trySelect()) {
                return ALREADY_SELECTED
            }
            state.value = element
            return OFFER_SUCCESS
        }
        // breaks here if offer meets receiver
        receive!!.completeResumeReceive(element, token!!)
        return receive!!.offerResult
    }

    // result is `E | POLL_FAILED | Closed`
    protected override fun pollInternal(): Any? {
        var result: Any? = null
        state.withLock {
            if (state.value === EMPTY) return closedForSend ?: POLL_FAILED
            result = state.value
            state.value = EMPTY
        }
        return result
    }

    // result is `E | POLL_FAILED | Closed`
    protected override fun pollSelectInternal(select: SelectInstance<*>): Any? {
        var result: Any? = null
        state.withLock {
            if (state.value === EMPTY) return closedForSend ?: POLL_FAILED
            if (!select.trySelect())
                return ALREADY_SELECTED
            result = state.value
            state.value = EMPTY
        }
        return result
    }

    protected override fun onCancelIdempotent(wasClosed: Boolean) {
        if (wasClosed) {
            state.withLock {
                state.value = EMPTY
            }
        }
        super.onCancelIdempotent(wasClosed)
    }

    override fun enqueueReceiveInternal(receive: Receive<E>): Boolean = state.withLock {
        super.enqueueReceiveInternal(receive)
    }

    // ------ debug ------

    override val bufferDebugString: String
        get() = "(value=${state.value})"
}
