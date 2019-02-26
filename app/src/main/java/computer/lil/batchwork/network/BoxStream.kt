package computer.lil.batchwork.network

import com.goterl.lazycode.lazysodium.LazySodiumAndroid
import com.goterl.lazycode.lazysodium.SodiumAndroid
import com.goterl.lazycode.lazysodium.interfaces.SecretBox
import com.goterl.lazycode.lazysodium.utils.Key
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.ceil
import kotlin.math.min

class BoxStream(val clientToServerKey: Key, val serverToClientKey: Key, val clientToServerNonce: ByteArray, val serverToClientNonce: ByteArray) {
    companion object {
        const val HEADER_SIZE = 34
        const val MAX_MESSAGE_SIZE = 4096
    }

    private val ls = LazySodiumAndroid(SodiumAndroid(), StandardCharsets.UTF_8)

    private fun ByteArray.increment(): ByteArray {
        for (i in size - 1 downTo 0) {
            if (this[i] == 0xFF.toByte()) {
                this[i] = 0x00.toByte()
            } else {
                ++this[i]
                break
            }
        }
        return this
    }

    fun sendToClient(message: ByteArray): ByteArray {
        return encryptMessage(message, serverToClientKey.asBytes, serverToClientNonce)
    }

    fun readFromClient(message: ByteArray): ByteArray {
        return decryptMessage(message, clientToServerKey.asBytes, clientToServerNonce)
    }

    fun sendToServer(message: ByteArray): ByteArray {
        return encryptMessage(message, clientToServerKey.asBytes, clientToServerNonce)
    }

    fun readFromServer(message: ByteArray): ByteArray {
        return decryptMessage(message, serverToClientKey.asBytes, serverToClientNonce)
    }

    fun createGoodbye(key: ByteArray, nonce: ByteArray): ByteArray {
        return encryptMessage(ByteArray(18), key, nonce)
    }

    fun decryptMessage(encryptedMessage: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val messages: MutableList<Byte> = mutableListOf()
        var currentIndex = 0

        while (currentIndex < encryptedMessage.size) {
            val decryptedMessage = decryptSingle(encryptedMessage.sliceArray(currentIndex until encryptedMessage.size), key, nonce)
            currentIndex += (decryptedMessage.size + HEADER_SIZE)
            messages.addAll(decryptedMessage.toList())
        }

        return messages.toByteArray()
    }

    private fun decryptSingle(encryptedSegment: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val headerNonce = nonce.copyOf()
        val bodyNonce = nonce.increment().copyOf()
        nonce.increment()

        val encryptedHeader = encryptedSegment.sliceArray(0 until HEADER_SIZE)
        val header = ByteArray(HEADER_SIZE - SecretBox.MACBYTES)
        ls.cryptoSecretBoxOpenEasy(header, encryptedHeader, encryptedHeader.size.toLong(), headerNonce, key)

        val messageLength = ByteBuffer.wrap(header.sliceArray(0 until 2)).int
        val bodyTag = header.sliceArray(2 until header.size)

        val encryptedBody = byteArrayOf(*bodyTag, *encryptedSegment.sliceArray(HEADER_SIZE until HEADER_SIZE + messageLength))
        val decryptedBody = ByteArray(messageLength)
        ls.cryptoSecretBoxOpenEasy(decryptedBody, encryptedBody, encryptedBody.size.toLong(), bodyNonce, key)

        return decryptedBody
    }

    fun encryptMessage(message: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val messageCount = ceil(message.size.toFloat() / MAX_MESSAGE_SIZE.toFloat()).toInt()
        val encryptedMessages = ByteArray(messageCount * HEADER_SIZE + message.size)

        for (i in 0 until messageCount) {
            val messageStart = i * MAX_MESSAGE_SIZE
            val messageSize = min(message.size, MAX_MESSAGE_SIZE)
            val messageSegment = message.sliceArray(messageStart until (messageStart + messageSize))
            val encryptedMessage = encryptSingle(messageSegment, key, nonce)
            encryptedMessage.copyInto(encryptedMessages, i * HEADER_SIZE + messageStart)
        }

        return encryptedMessages
    }

    private fun encryptSingle(messageSegment: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val headerNonce = nonce.copyOf()
        val bodyNonce = nonce.increment().copyOf()
        nonce.increment()

        val encryptedBody = ByteArray(messageSegment.size + SecretBox.MACBYTES)
        ls.cryptoSecretBoxEasy(encryptedBody, messageSegment, messageSegment.size.toLong(), bodyNonce, key)

        val headerValue = byteArrayOf(
            *ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putInt(encryptedBody.size - SecretBox.MACBYTES).array(),
            *encryptedBody.sliceArray(0 until SecretBox.MACBYTES)
        )

        val encryptedHeader = ByteArray(headerValue.size + SecretBox.MACBYTES)
        ls.cryptoSecretBoxEasy(encryptedHeader, headerValue, headerValue.size.toLong(), headerNonce, key)

        return byteArrayOf(*encryptedHeader, *encryptedBody.sliceArray(SecretBox.MACBYTES until encryptedBody.size))
    }
}