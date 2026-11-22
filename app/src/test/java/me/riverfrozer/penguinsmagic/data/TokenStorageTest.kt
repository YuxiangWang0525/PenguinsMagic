package me.riverfrozer.penguinsmagic.data

import java.security.KeyStoreException
import org.junit.Assert.assertEquals
import org.junit.Test

/** 保存失败时界面只能靠这条异常链说话，格式必须稳定。 */
class TokenStorageTest {

    @Test
    fun `diagnosis walks to the bottom of the cause chain`() {
        val failure = KeyStoreException("cannot get keystore service", IllegalStateException("keystore2 unavailable"))
        assertEquals(
            "KeyStoreException: cannot get keystore service ← IllegalStateException: keystore2 unavailable",
            failure.diagnosis(),
        )
    }

    @Test
    fun `diagnosis of a lone exception names just itself`() {
        assertEquals(
            "TokenStorageFailure: 没有可用的 AES 密钥",
            TokenStorageFailure("没有可用的 AES 密钥").diagnosis(),
        )
    }
}
