package app.rustmfa.vault

import kotlin.test.Test
import kotlin.test.assertEquals

class VaultKdfTest {
    @Test
    fun matchesRfc9106Argon2idVector() {
        val tag = VaultKdf.argon2id(
            password = ByteArray(32) { 0x01 },
            salt = ByteArray(16) { 0x02 },
            memoryKiB = 32,
            iterations = 3,
            parallelism = 4,
            length = 32,
            secret = ByteArray(8) { 0x03 },
            additional = ByteArray(12) { 0x04 },
        )
        assertEquals(
            "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659",
            tag.joinToString("") { "%02x".format(it) },
        )
    }
}
