package meow.nyx.quackjs.verification

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import meow.nyx.quackjs.PlayerHints
import meow.nyx.quackjs.PlayerProgram

/** Compare both compile modes with the Node goldens written by scripts/faraday-sweep.mjs. */
fun main() {
    val cache = Path.of(System.getProperty("quackjs.faraday.cache"))
    val sweep = Json.parseToJsonElement(Files.readString(cache.resolve("sweep.json"))).jsonObject
    val inputs = sweep.getValue("inputs").jsonObject
    fun samples(kind: String) = inputs.getValue(kind).jsonArray.map { it.jsonObject.text("input") }
    val signatures = samples("signature")
    val ns = samples("n")
    val failed = mutableListOf<String>()
    var ambiguous = 0
    val players = sweep.getValue("players").jsonArray.map { it.jsonObject }
    for (p in players) {
        val id = p.text("id")
        val source = Files.readString(cache.resolve("sources/$id.js"))
        check(sha256(source) == p.text("sha256")) { "$id: source SHA-256 mismatch" }
        fun outputs(kind: String) = p.getValue(kind).jsonArray.map { it.jsonPrimitive.content }
        fun verify(program: PlayerProgram) {
            check(signatures.map(program::decipherSignature) == outputs("signature")) { "signature output mismatch" }
            check(ns.map(program::transformN) == outputs("n")) { "n output mismatch" }
        }
        val hints = PlayerHints(p.text("signatureExpression"), p.text("nClass"), p.getValue("signatureTimestamp").jsonPrimitive.int)
        runCatching { verify(PlayerProgram.compile(source, hints)) }.onFailure { failed += "$id hinted: ${it.message}" }
        runCatching { verify(PlayerProgram.compile(source)) }.onFailure {
            if ("ambiguous" in it.message.orEmpty().lowercase()) ambiguous++ else failed += "$id hintFree: ${it.message}"
        }
    }
    failed.forEach(::println)
    println("Faraday sweep ${sweep.text("commit")}: ${players.size}/${sweep.getValue("total").jsonPrimitive.content} oracle players; ${failed.size} Kotlin failures; $ambiguous hint-free ambiguous; current ${sweep.text("current")}")
    check(failed.isEmpty())
}
