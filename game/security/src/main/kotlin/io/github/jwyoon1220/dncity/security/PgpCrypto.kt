package io.github.jwyoon1220.dncity.security

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import java.io.ByteArrayOutputStream
import java.security.Security

/**
 * Shared OpenPGP (BouncyCastle) helpers for the login-gate handshake -- see
 * [PgpAuthServerEvents]. [sign] runs on the client, using the player's own locally-stored secret
 * key ([PgpPaths.secretKeyFile]); [verify] runs on the server against the operator-registered
 * public key ([PgpKeyRegistry]). Both sides operate on a detached signature over the exact same
 * challenge string bytes (see [PgpChallengeGenerator]) -- never a full clearsign/encrypt, since
 * the challenge itself carries no secret and only needs to prove possession of the private key.
 */
object PgpCrypto {
    init {
        Security.addProvider(BouncyCastleProvider())
    }

    /** Signs [message] with the first signing-capable key in [secretKeyArmored], unlocked with [passphrase]. */
    fun sign(secretKeyArmored: String, passphrase: CharArray, message: ByteArray): String {
        val keyIn = PGPUtil.getDecoderStream(secretKeyArmored.byteInputStream())
        val secretKeyRings = PGPSecretKeyRingCollection(keyIn, JcaKeyFingerprintCalculator())
        val secretKey = secretKeyRings.keyRings.asSequence()
            .flatMap { it.secretKeys.asSequence() }
            .firstOrNull { it.isSigningKey }
            ?: error("No signing-capable key found in the provided secret key")

        val privateKey = secretKey.extractPrivateKey(
            JcePBESecretKeyDecryptorBuilder().setProvider("BC").build(passphrase),
        )
        val signatureGenerator = PGPSignatureGenerator(
            JcaPGPContentSignerBuilder(secretKey.publicKey.algorithm, HashAlgorithmTags.SHA256).setProvider("BC"),
        )
        signatureGenerator.init(PGPSignature.BINARY_DOCUMENT, privateKey)
        signatureGenerator.update(message)

        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armorOut -> signatureGenerator.generate().encode(armorOut) }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Verifies that [signatureArmored] is a valid detached signature over [message] by [publicKeyArmored]. */
    fun verify(publicKeyArmored: String, message: ByteArray, signatureArmored: String): Boolean {
        return try {
            val sigIn = PGPUtil.getDecoderStream(signatureArmored.byteInputStream())
            val signature = (PGPObjectFactory(sigIn, JcaKeyFingerprintCalculator()).nextObject() as PGPSignatureList)[0]

            val keyIn = PGPUtil.getDecoderStream(publicKeyArmored.byteInputStream())
            val publicKeyRings = PGPPublicKeyRingCollection(keyIn, JcaKeyFingerprintCalculator())
            val publicKey = publicKeyRings.getPublicKey(signature.keyID) ?: return false

            signature.init(JcaPGPContentVerifierBuilderProvider().setProvider("BC"), publicKey)
            signature.update(message)
            signature.verify()
        } catch (e: Exception) {
            false
        }
    }
}
