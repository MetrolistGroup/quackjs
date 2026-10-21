import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

/** Uses only Maven Local artifacts, full original players, and independently generated goldens. */
class PublishedConsumerTest {
    @Test fun faradayHints() = verify(hinted = true)
    @Test fun hintFreeDiscovery() = verify(hinted = false)

    private fun verify(hinted: Boolean) = runBlocking {
        val fixtures = File(checkNotNull(System.getProperty("quackjs.fixtures")))
        val manifest = Json.parseToJsonElement(File(fixtures, "manifest.json").readText()).jsonObject
        val goldens = Json.parseToJsonElement(File(fixtures, "goldens.json").readText()).jsonObject
        for (entry in manifest.getValue("players").jsonArray) {
            val player = entry.jsonObject
            val id = player.text("id")
            val golden = goldens.getValue("players").jsonArray.single { it.jsonObject.text("id") == id }.jsonObject
            val engine = MockEngine { request ->
                assertEquals("www.youtube.com", request.url.host)
                respond(File(fixtures, player.text("file")).readText())
            }
            val client = HttpClient(engine)
            val repository = object : PlayerConfigRepository {
                override val enabled = true
                override val sourceUrl = "https://raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json"
                override val defaultSourceUrl = sourceUrl
                override var cachedJson = """{"schemaVersion":1,"players":{"$id":{"sig":"${player.text("signatureExpression")}","nClass":"${player.text("nClass")}","sts":${player.text("signatureTimestamp")}}}}"""
                override var cachedAtMs = Clock.System.now().toEpochMilliseconds()
                override var cachedSourceUrl = sourceUrl
                override var cachedEtag = ""
            }
            val service = YouTubeCipherService(client, if (hinted) RemotePlayerConfigStore(client, repository) else null)
            val playerUrl = player.text("url")
            val mediaUrl = "https://fixture.googlevideo.com/videoplayback"
            try {
                service.setPreprocessedPlayerCache({ error("EJS cache read") }, { _, _ -> error("EJS cache write") })
                service.prewarmEjs()
                service.preloadPlayerCode(playerUrl)
                for (kind in listOf("signature", "n")) {
                    val cases = golden.getValue(kind).jsonArray.map { it.jsonObject }
                    val formats = cases.map { case ->
                        if (kind == "signature") Format(itag = 140, signatureCipher = "s=${case.text("input")}&sp=sig&url=$mediaUrl", mimeType = "audio/mp4")
                        else Format(itag = 140, url = "$mediaUrl?n=${case.text("input")}", mimeType = "audio/mp4")
                    }
                    repeat(2) {
                        val results = service.processFormats(playerUrl, formats)
                        cases.zip(results).forEach { (case, result) ->
                            val url = Url(checkNotNull(result.url) { "$id hinted=$hinted $kind ${case.text("name")} failed" })
                            assertEquals(case.text("output"), url.parameters[if (kind == "signature") "sig" else "n"], "$id ${case.text("name")}")
                        }
                    }
                    val first = cases.first()
                    val single = if (kind == "signature") service.deobfuscateSignatureCipher(playerUrl, "s=${first.text("input")}&sp=sig&url=$mediaUrl")
                        else service.processNParameter(playerUrl, "$mediaUrl?n=${first.text("input")}")
                    assertEquals(first.text("output"), Url(checkNotNull(single)).parameters[if (kind == "signature") "sig" else "n"])
                }
                assertEquals(1, engine.requestHistory.size, "$id should compile and download once")
            } finally {
                service.dispose()
                client.close()
            }
        }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
