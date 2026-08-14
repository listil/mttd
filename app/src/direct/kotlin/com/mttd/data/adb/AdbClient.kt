// Ported from RikkaApps/Shizuku (manager/src/main/java/moe/shizuku/manager/adb/AdbClient.kt),
// licensed under Apache License 2.0. See THIRD_PARTY_NOTICES.md.
// Adapted: package renamed; rikka.core.util.BuildUtils.atLeast29 inlined as a direct SDK_INT check.
package com.mttd.data.adb

import android.os.Build
import android.util.Log
import com.mttd.data.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import com.mttd.data.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import com.mttd.data.adb.AdbProtocol.ADB_AUTH_TOKEN
import com.mttd.data.adb.AdbProtocol.A_AUTH
import com.mttd.data.adb.AdbProtocol.A_CLSE
import com.mttd.data.adb.AdbProtocol.A_CNXN
import com.mttd.data.adb.AdbProtocol.A_MAXDATA
import com.mttd.data.adb.AdbProtocol.A_OKAY
import com.mttd.data.adb.AdbProtocol.A_OPEN
import com.mttd.data.adb.AdbProtocol.A_STLS
import com.mttd.data.adb.AdbProtocol.A_STLS_VERSION
import com.mttd.data.adb.AdbProtocol.A_VERSION
import com.mttd.data.adb.AdbProtocol.A_WRTE
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbClient"

/**
 * 페어링 이후 실제 `adb connect` + `shell:<cmd>` 스트림을 여는 클라이언트.
 *
 * [DirectAdbManager] 가 하나의 인스턴스를 계속 물고 있다가([connect] 는 세션당 한 번),
 * [IUserService][com.mttd.IUserService] 의 4개 op 를 매번 [shellCommand] 로 실행한다 —
 * 호출마다 재연결하면 TCP+TLS+ADB 핸드셰이크를 반복해서 폴링 성능에 치명적이므로 주의.
 */
class AdbClient(private val host: String, private val port: Int, private val key: AdbKey) : Closeable {

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false

    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    fun connect() {
        socket = Socket(host, port)
        socket.tcpNoDelay = true
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = read()
        if (message.command == A_STLS) {
            check(Build.VERSION.SDK_INT >= 28) { "Connect to adb with TLS is not supported before Android 9" }
            write(A_STLS, A_STLS_VERSION, 0)

            val sslContext = key.sslContext
            tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.startHandshake()
            Log.d(TAG, "Handshake succeeded.")

            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
            useTls = true

            message = read()
        } else if (message.command == A_AUTH) {
            if (message.command != A_AUTH && message.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

            message = read()
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }

        if (message.command != A_CNXN) error("not A_CNXN")
    }

    // 스트림마다 새 id 필요 — 이 커넥션을 (Shizuku 원본과 달리) 계속 재사용해서 shellCommand()
    // 를 반복 호출하다 보니, id를 고정값(1)으로 쓰면 이전 스트림이 기기 쪽에서 미처 정리되기
    // 전에 같은 id로 새 스트림을 열어 응답이 뒤섞이는 문제가 있었다("not A_WRTE or A_CLSE").
    private var nextLocalId = 1

    // 커넥션 하나를 여러 스트림이 시간차를 두고 재사용하다 보니, 이전 스트림의 뒤늦게 도착한
    // 메시지(arg1 이 우리 localId 와 다름)가 다음 스트림의 응답으로 잘못 해석되는 문제가 있었다
    // ("not A_WRTE or A_CLSE" / "not A_OKAY or A_CLSE"). 이 스트림(localId) 앞으로 온 게
    // 아니면 조용히 버리고 계속 읽는다 — 멀티플렉스 프로토콜에서 정상적인 처리.
    private fun readForStream(localId: Int): AdbMessage {
        while (true) {
            val m = read()
            if (m.arg1 == localId) return m
        }
    }

    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)?) {
        val localId = nextLocalId++
        write(A_OPEN, localId, 0, "shell:$command")

        var message = readForStream(localId)
        when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = readForStream(localId)
                    val remoteId = message.arg0
                    if (message.command == A_WRTE) {
                        if (message.data_length > 0) {
                            listener?.invoke(message.data!!)
                        }
                        write(A_OKAY, localId, remoteId)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    } else {
                        error("not A_WRTE or A_CLSE")
                    }
                }
            }
            A_CLSE -> {
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
            }
            else -> {
                error("not A_OKAY or A_CLSE")
            }
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    private fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

        inputStream.readFully(buffer.array(), 0, 24)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        val data: ByteArray?
        if (dataLength >= 0) {
            data = ByteArray(dataLength)
            inputStream.readFully(data, 0, dataLength)
        } else {
            data = null
        }
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Log.d(TAG, "read ${message.toStringShort()}")
        return message
    }

    override fun close() {
        try {
            plainInputStream.close()
        } catch (e: Throwable) {
        }
        try {
            plainOutputStream.close()
        } catch (e: Throwable) {
        }
        try {
            socket.close()
        } catch (e: Exception) {
        }

        if (useTls) {
            try {
                tlsInputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsOutputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsSocket.close()
            } catch (e: Exception) {
            }
        }
    }
}
