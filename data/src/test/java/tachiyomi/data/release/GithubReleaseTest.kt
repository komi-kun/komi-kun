// SPDX-License-Identifier: Apache-2.0
// KUN -->
package tachiyomi.data.release

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class GithubReleaseTest {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun `parses release with asset digest and download link`() {
        val payload = """
            {
              "tag_name": "r10697",
              "body": "Release notes body",
              "html_url": "https://github.com/komi-kun/komi-kun/releases/tag/r10697",
              "prerelease": true,
              "draft": false,
              "assets": [
                {
                  "name": "Komikun-arm64-v8a-r10697.apk",
                  "browser_download_url": "https://github.com/komi-kun/komi-kun/releases/download/r10697/Komikun-arm64-v8a-r10697.apk",
                  "digest": "sha256:62f007038c3f7cb87826f12bbc2fcd49a5113ada76bf507e55179d3438d49670"
                },
                {
                  "name": "Komikun-r10697.apk",
                  "browser_download_url": "https://github.com/komi-kun/komi-kun/releases/download/r10697/Komikun-r10697.apk",
                  "digest": "sha256:bcfbef0db2cd69e483c38ef33afd7997900fd94d2b5f5cee54098934725d0dc7"
                }
              ]
            }
        """.trimIndent()

        val release = json.decodeFromString<GithubRelease>(payload)
        release.version shouldBe "r10697"
        release.assets.size shouldBe 2

        val arm64Asset = release.assets[0]
        arm64Asset.name shouldBe "Komikun-arm64-v8a-r10697.apk"
        arm64Asset.digest shouldBe "sha256:62f007038c3f7cb87826f12bbc2fcd49a5113ada76bf507e55179d3438d49670"

        val universalAsset = release.assets[1]
        universalAsset.name shouldBe "Komikun-r10697.apk"
        universalAsset.digest shouldBe "sha256:bcfbef0db2cd69e483c38ef33afd7997900fd94d2b5f5cee54098934725d0dc7"
    }

    @Test
    fun `parses release with asset without digest for backwards compatibility`() {
        val payload = """
            {
              "tag_name": "v1.0.0",
              "body": "Older release",
              "html_url": "https://github.com/komi-kun/komi-kun/releases/tag/v1.0.0",
              "assets": [
                {
                  "name": "Komikun-v1.0.0.apk",
                  "browser_download_url": "https://github.com/komi-kun/komi-kun/releases/download/v1.0.0/Komikun-v1.0.0.apk"
                }
              ]
            }
        """.trimIndent()

        val release = json.decodeFromString<GithubRelease>(payload)
        release.version shouldBe "v1.0.0"
        release.assets.size shouldBe 1
        release.assets[0].digest shouldBe null
    }
}
// KUN <--
