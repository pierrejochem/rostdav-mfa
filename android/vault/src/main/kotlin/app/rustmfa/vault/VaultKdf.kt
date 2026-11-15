package app.rustmfa.vault

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Argon2id (version 0x13) over Bouncy Castle. */
object VaultKdf {
    fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        length: Int = 32,
        secret: ByteArray = ByteArray(0),
        additional: ByteArray = ByteArray(0),
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSecret(secret)
            .withAdditional(additional)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(length)
        generator.generateBytes(password, out)
        return out
    }
}
