package meow.nyx.quackjs.verification

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class Challenge(val kind: String, val name: String, val input: String, val output: String)

internal data class RealPlayer(
    val id: String,
    val source: String,
    val globals: String,
    val signatureExpression: String,
    val nClass: String,
    val signatureTimestamp: Int,
    val cases: List<Challenge>,
) {
    val firstPair: List<Challenge>
        get() = listOf(cases.single { it.kind == "signature" && it.name == "seeded-86" },
            cases.single { it.kind == "n" && it.name == "faraday-probe" })

    fun exportedSource(): String {
        require(Regex("[A-Za-z0-9_$]+\\(\\d+,\\d+,INPUT\\)").matches(signatureExpression))
        require(Regex("[A-Za-z0-9_$]+").matches(nClass))
        val trailer = "})(_yt_player);"
        require(source.split(trailer).size == 2) { "$id: expected exactly one player trailer" }
        val exports = ";globalThis.__quackSig=function(input){return ${signatureExpression.replace("INPUT", "input")};};" +
            "globalThis.__quackN=function(input){return new g.$nClass(" +
            "'https://x.googlevideo.com/videoplayback?n='+encodeURIComponent(input),true).get('n');};"
        return source.replace(trailer, exports + trailer)
    }
}

internal object PlayerFixtures {
    fun load(): List<RealPlayer> {
        val directory = Path.of(System.getProperty("quackjs.fixtures", "fixtures"))
        fun read(name: String) = Files.readString(directory.resolve(name))
        val manifest = Json.parseToJsonElement(read("manifest.json")).jsonObject
        val goldens = Json.parseToJsonElement(read("goldens.json")).jsonObject
        check(manifest.getValue("schemaVersion").jsonPrimitive.int == 1)
        check(goldens.getValue("schemaVersion").jsonPrimitive.int == 1)
        val globals = read("oracle-globals.js")
        check(sha256(globals) == goldens.getValue("oracle").jsonObject.text("globalsSha256"))
        val expected = goldens.getValue("players").jsonArray.associate { it.jsonObject.text("id") to it.jsonObject }
        val players = manifest.getValue("players").jsonArray.map { value ->
            val player = value.jsonObject
            val id = player.text("id")
            val source = read(player.text("file"))
            check(source.toByteArray(Charsets.UTF_8).size == player.getValue("bytes").jsonPrimitive.int) { "$id: size mismatch" }
            check(sha256(source) == player.text("sha256")) { "$id: player SHA-256 mismatch" }
            val golden = expected.getValue(id)
            check(golden.text("sha256") == player.text("sha256")) { "$id: golden source mismatch" }
            val cases = listOf("signature", "n").flatMap { kind ->
                golden.getValue(kind).jsonArray.map { sample ->
                    val c = sample.jsonObject
                    Challenge(kind, c.text("name"), c.text("input"), c.text("output"))
                }
            }
            check(cases.map { it.kind to it.name }.toSet().size == cases.size) { "$id: duplicate cases" }
            val nCases = cases.filter { it.kind == "n" }
            check(nCases.isNotEmpty() && nCases.all {
                it.input != it.output && Regex("[A-Za-z0-9_-]{5,}").matches(it.output)
            }) { "$id: unchanged or invalid n oracle output" }
            RealPlayer(id, source, globals, player.text("signatureExpression"), player.text("nClass"),
                player.getValue("signatureTimestamp").jsonPrimitive.int, cases)
        }
        check(players.size >= 3 && players.map { it.id }.toSet() == expected.keys)
        return players
    }
}

internal fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let {
    check(it.isString) { "$key must be a JSON string" }
    it.content
}
internal fun resultsJson(values: List<String>): String = JsonArray(values.map(::JsonPrimitive)).toString()
internal fun expectedJson(cases: List<Challenge>): String = resultsJson(cases.map { it.output })
internal fun inputsJson(cases: List<Challenge>): String = JsonArray(cases.map {
    JsonArray(listOf<JsonElement>(JsonPrimitive(it.kind), JsonPrimitive(it.input)))
}).toString()
