// SPDX-License-Identifier: Apache-2.0
// KUN -->
package eu.kanade.tachiyomi.util.lang

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class HashTest {

    @Test
    fun `extractSha256 returns normalized 64-char hex when sha256 prefix is present`() {
        val digest = "sha256:62f007038c3f7cb87826f12bbc2fcd49a5113ada76bf507e55179d3438d49670"
        Hash.extractSha256(digest) shouldBe "62f007038c3f7cb87826f12bbc2fcd49a5113ada76bf507e55179d3438d49670"
    }

    @Test
    fun `extractSha256 handles uppercase prefix and hex characters`() {
        val digest = "SHA256:62F007038C3F7CB87826F12BBC2FCD49A5113ADA76BF507E55179D3438D49670"
        Hash.extractSha256(digest) shouldBe "62f007038c3f7cb87826f12bbc2fcd49a5113ada76bf507e55179d3438d49670"
    }

    @Test
    fun `extractSha256 handles raw 64-char hex string`() {
        val digest = "62f007038c3f7cb87826f12bbc2fcd49a5113ada76bf507e55179d3438d49670"
        Hash.extractSha256(digest) shouldBe "62f007038c3f7cb87826f12bbc2fcd49a5113ada76bf507e55179d3438d49670"
    }

    @Test
    fun `extractSha256 returns null for invalid formats`() {
        Hash.extractSha256(null) shouldBe null
        Hash.extractSha256("") shouldBe null
        Hash.extractSha256("   ") shouldBe null
        Hash.extractSha256("sha256:short") shouldBe null
        Hash.extractSha256("62f007038c3f7cb87826f12bbc2fcd49a5113ada76bf507e55179d3438d4967z") shouldBe null // non-hex 'z'
        Hash.extractSha256("sha256:62f007038c3f7cb87826f12bbc2fcd49a5113ada76bf507e55179d3438d49670extra") shouldBe null
    }

    @Test
    fun `sha256 on file computes correct digest`() {
        val tempFile = File.createTempFile("hash_test", ".tmp")
        try {
            tempFile.writeText("hello world\n")
            val computed = Hash.sha256(tempFile)
            val expected = Hash.sha256("hello world\n")
            computed shouldBe expected
        } finally {
            tempFile.delete()
        }
    }
}
// KUN <--
