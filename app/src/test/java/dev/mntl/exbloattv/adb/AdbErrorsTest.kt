package dev.mntl.exbloattv.adb

import com.google.common.truth.Truth.assertThat
import dadb.AdbAuthException
import dadb.AdbConnectException
import dadb.AdbConnectionClosedException
import dadb.AdbTimeoutException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import org.junit.Test

class AdbErrorsTest {
    @Test fun connectExceptionIsRefused() {
        assertThat(mapAdbException(ConnectException("Connection refused"))).isInstanceOf(AdbError.Refused::class.java)
    }

    @Test fun dadbConnectExceptionIsRefused() {
        assertThat(mapAdbException(AdbConnectException("x", null))).isInstanceOf(AdbError.Refused::class.java)
    }

    @Test fun refusedHiddenInCauseChainIsStillRefused() {
        val wrapped = IOException("wrapper", ConnectException("refused"))
        assertThat(mapAdbException(wrapped)).isInstanceOf(AdbError.Refused::class.java)
    }

    @Test fun socketTimeoutIsTimeout() {
        assertThat(mapAdbException(SocketTimeoutException("read timed out"))).isInstanceOf(AdbError.Timeout::class.java)
        assertThat(mapAdbException(AdbTimeoutException("x", null))).isInstanceOf(AdbError.Timeout::class.java)
    }

    @Test fun authExceptionIsUnauthorized() {
        assertThat(mapAdbException(AdbAuthException("x", null))).isInstanceOf(AdbError.Unauthorized::class.java)
    }

    @Test fun ioErrorDuringHandshakeIsUnauthorized() {
        assertThat(mapAdbException(IOException("closed"), duringHandshake = true))
            .isInstanceOf(AdbError.Unauthorized::class.java)
        assertThat(mapAdbException(EOFException(), duringHandshake = true))
            .isInstanceOf(AdbError.Unauthorized::class.java)
    }

    @Test fun refusedAndTimeoutKeepTheirMeaningDuringHandshake() {
        assertThat(mapAdbException(ConnectException("refused"), duringHandshake = true))
            .isInstanceOf(AdbError.Refused::class.java)
        assertThat(mapAdbException(SocketTimeoutException(), duringHandshake = true))
            .isInstanceOf(AdbError.Timeout::class.java)
    }

    @Test fun midSessionDropsAreDisconnected() {
        assertThat(mapAdbException(EOFException())).isInstanceOf(AdbError.Disconnected::class.java)
        assertThat(mapAdbException(SocketException("Broken pipe"))).isInstanceOf(AdbError.Disconnected::class.java)
        assertThat(mapAdbException(AdbConnectionClosedException("x", null))).isInstanceOf(AdbError.Disconnected::class.java)
        assertThat(mapAdbException(IOException("anything else"))).isInstanceOf(AdbError.Disconnected::class.java)
    }

    @Test fun existingAdbErrorPassesThroughUnchanged() {
        val e = AdbError.Timeout()
        assertThat(mapAdbException(e)).isSameInstanceAs(e)
    }

    @Test fun handshakeSocketCloseWrappedByDadbIsUnauthorized() {
        val e = AdbConnectException("x", IOException("Connection closed"))
        assertThat(mapAdbException(e, duringHandshake = true)).isInstanceOf(AdbError.Unauthorized::class.java)
    }

    @Test fun handshakeTimeoutWrappedByDadbIsTimeout() {
        val e = AdbConnectException("x", SocketTimeoutException("t"))
        assertThat(mapAdbException(e, duringHandshake = true)).isInstanceOf(AdbError.Timeout::class.java)
    }

    @Test fun handshakeRefusalWrappedByDadbIsRefused() {
        val e = AdbConnectException("x", ConnectException("refused"))
        assertThat(mapAdbException(e, duringHandshake = true)).isInstanceOf(AdbError.Refused::class.java)
    }
}
