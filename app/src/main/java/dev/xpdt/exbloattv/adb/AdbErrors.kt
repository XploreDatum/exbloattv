package dev.xpdt.exbloattv.adb

import dadb.AdbAuthException
import dadb.AdbConnectException
import dadb.AdbTimeoutException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/**
 * Maps any failure from dadb / the socket layer to an [AdbError].
 *
 * The cause chain is inspected, so a refusal wrapped by another exception is still a refusal.
 * [duringHandshake] is true while connecting: the TV simply closes the socket while it waits for
 * the person to accept the key, so a plain I/O failure there means "not approved yet".
 */
fun mapAdbException(t: Throwable, duringHandshake: Boolean = false): AdbError {
    if (t is AdbError) return t
    val chain = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(10).toList()
    return when {
        chain.any { it is AdbAuthException } -> AdbError.Unauthorized()
        chain.any { it is ConnectException || it is NoRouteToHostException } -> AdbError.Refused()
        chain.any { it is SocketTimeoutException || it is AdbTimeoutException } -> AdbError.Timeout()
        duringHandshake -> AdbError.Unauthorized()
        chain.any { it is AdbConnectException } -> AdbError.Refused()
        else -> AdbError.Disconnected()
    }
}
