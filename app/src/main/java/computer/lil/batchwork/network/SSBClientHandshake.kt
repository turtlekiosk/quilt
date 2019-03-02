package computer.lil.batchwork.network

import com.goterl.lazycode.lazysodium.LazySodiumAndroid
import com.goterl.lazycode.lazysodium.SodiumAndroid
import com.goterl.lazycode.lazysodium.interfaces.Auth
import com.goterl.lazycode.lazysodium.interfaces.Hash
import com.goterl.lazycode.lazysodium.interfaces.SecretBox
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.goterl.lazycode.lazysodium.utils.Key
import computer.lil.batchwork.identity.IdentityHandler
import java.nio.charset.StandardCharsets
import java.util.*

class SSBClientHandshake(identityHandler: IdentityHandler, val remoteKey: ByteArray): Handshake(identityHandler) {
    enum class State {
        STEP1, STEP2, STEP3
    }

    var state = State.STEP1

    var serverEphemeralKey: ByteArray? = null
    private var detachedSignatureA: ByteArray? = null

    fun createAuthenticateMessage(): ByteArray {
        val hash = ByteArray(Hash.SHA256_BYTES)
        ls.cryptoHashSha256(hash, sharedSecretab?.asBytes, sharedSecretab?.asBytes?.size!!.toLong())

        val message = byteArrayOf(*networkId, *remoteKey, *hash)
        detachedSignatureA = identityHandler.signUsingIdentity(message)

        val finalMessage = byteArrayOf(*detachedSignatureA!!, *identityHandler.getIdentityPublicKey())
        val zeroNonce = ByteArray(SecretBox.NONCEBYTES)
        val payload = ByteArray(112)
        val boxKey = ByteArray(Hash.SHA256_BYTES)
        val preKey = byteArrayOf(*networkId, *sharedSecretab!!.asBytes, *sharedSecretaB!!.asBytes)

        ls.cryptoHashSha256(boxKey, preKey, preKey.getLongSize())
        ls.cryptoSecretBoxEasy(payload, finalMessage, finalMessage.getLongSize(), zeroNonce, boxKey)

        return payload
    }

    fun validateServerAcceptResponse(data: ByteArray): Boolean {
        val zeroNonce = ByteArray(SecretBox.NONCEBYTES)
        val responseKey = ByteArray(Hash.SHA256_BYTES)
        val preKey = byteArrayOf(*networkId, *sharedSecretab!!.asBytes, *sharedSecretaB!!.asBytes, *sharedSecretAb!!.asBytes)
        ls.cryptoHashSha256(responseKey, preKey, preKey.getLongSize())
        val hashab = ByteArray(Hash.SHA256_BYTES)
        ls.cryptoHashSha256(hashab, sharedSecretab?.asBytes, sharedSecretab?.asBytes?.size!!.toLong())

        val messageSize = networkId.size + (detachedSignatureA?.size ?: 0) + identityHandler.getIdentityPublicKey().size + hashab.size
        val expectedMessage = byteArrayOf(*networkId, *detachedSignatureA!!, *identityHandler.getIdentityPublicKey(), *hashab)
        val detachedSignatureB = ByteArray(messageSize - SecretBox.MACBYTES)

        return ls.cryptoSecretBoxOpenEasy(detachedSignatureB, data, data.getLongSize(), zeroNonce, responseKey)
                && ls.cryptoSignVerifyDetached(detachedSignatureB, expectedMessage, expectedMessage.getLongSize(), remoteKey)
    }

    override fun computeSharedKeys() {
        val curve25519ServerKey = ByteArray(Sign.CURVE25519_PUBLICKEYBYTES)
        ls.convertPublicKeyEd25519ToCurve25519(curve25519ServerKey, remoteKey)

        sharedSecretab = ls.cryptoScalarMult(localEphemeralKeyPair.secretKey, Key.fromBytes(remoteEphemeralKey))
        sharedSecretaB = ls.cryptoScalarMult(localEphemeralKeyPair.secretKey, Key.fromBytes(curve25519ServerKey))
        sharedSecretAb = Key.fromBytes(identityHandler.keyExchangeUsingIdentitySecret(remoteEphemeralKey!!))
    }
}