package computer.lil.quilt.protocol

import com.goterl.lazycode.lazysodium.interfaces.SecretBox
import com.goterl.lazycode.lazysodium.interfaces.Sign
import computer.lil.quilt.identity.IdentityHandler
import computer.lil.quilt.protocol.Constants.Companion.SSB_NETWORK_ID
import computer.lil.quilt.protocol.Crypto.Companion.toByteString
import computer.lil.quilt.protocol.Crypto.Companion.toKey
import okio.ByteString

class ClientHandshake(
    identityHandler: IdentityHandler,
    networkId: ByteString = SSB_NETWORK_ID,
    serverKey: ByteString
) : Handshake(identityHandler, networkId) {
    init {
        remoteKey = serverKey
    }

    private var detachedSignatureA: ByteString? = null

    fun createAuthenticateMessage(): ByteString {
        val hash = ByteString.of(*sharedSecretab!!.asBytes).sha256()
        val message = ByteString.of(*networkId.toByteArray(), *remoteKey!!.toByteArray(), *hash.toByteArray())
        detachedSignatureA = identityHandler.signUsingIdentity(message)

        val finalMessage = ByteString.of(*detachedSignatureA!!.toByteArray(), *identityHandler.getIdentityPublicKey().toByteArray())
        val zeroNonce = ByteArray(SecretBox.NONCEBYTES).toByteString()
        val boxKey = ByteString.of(*networkId.toByteArray(), *sharedSecretab!!.asBytes, *sharedSecretaB!!.asBytes).sha256()
        return Crypto.secretBoxSeal(finalMessage, boxKey, zeroNonce)
    }

    fun verifyServerAcceptResponse(data: ByteString): Boolean {
        val zeroNonce = ByteArray(SecretBox.NONCEBYTES).toByteString()
        val responseKey = ByteString.of(*networkId.toByteArray(), *sharedSecretab!!.asBytes, *sharedSecretaB!!.asBytes, *sharedSecretAb!!.asBytes).sha256()
        val hashab = ByteString.of(*sharedSecretab!!.asBytes).sha256()

        val expectedMessage = ByteString.of(*networkId.toByteArray(), *detachedSignatureA!!.toByteArray(), *identityHandler.getIdentityPublicKey().toByteArray(), *hashab.toByteArray())

        Crypto.secretBoxOpen(data, responseKey, zeroNonce)?.let {
            completed = Crypto.verifySignDetached(it, expectedMessage, remoteKey!!)
            return completed
        }

        return false
    }

    override fun computeSharedKeys() {
        val curve25519ServerKey = ByteArray(Sign.CURVE25519_PUBLICKEYBYTES)
        ls.convertPublicKeyEd25519ToCurve25519(curve25519ServerKey, remoteKey!!.toByteArray())

        sharedSecretab = ls.cryptoScalarMult(localEphemeralKeyPair.secretKey, remoteEphemeralKey?.toKey())
        sharedSecretaB = ls.cryptoScalarMult(localEphemeralKeyPair.secretKey, curve25519ServerKey.toByteString().toKey())
        sharedSecretAb = identityHandler.keyExchangeUsingIdentitySecret(ByteString.of(*remoteEphemeralKey!!.toByteArray())).toKey()
    }
}