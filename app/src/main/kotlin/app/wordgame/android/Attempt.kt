package app.wordgame.android

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching], but lets coroutine cancellation through. Leaving a screen or going to the
 * background cancels its requests: that is not an error to show ("StandaloneCoroutine was cancelled").
 */
inline fun <T> attempt(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
