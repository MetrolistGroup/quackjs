package meow.nyx.quackjs.verification

import kotlin.test.Test
import kotlin.test.assertEquals
import meow.nyx.quackjs.PlayerHints
import meow.nyx.quackjs.PlayerProgram

class PlayerParityTest {
    @Test fun olderPlayer() = verify("4f38b487")
    @Test fun intermediatePlayer() = verify("10733253")
    @Test fun suppliedPlayer() = verify("eb27e5fd")
    @Test fun latestPublicRegistryPlayer() = verify("5008be0a")

    private fun verify(id: String) {
        val player = PlayerFixtures.load().single { it.id == id }
        val program = PlayerProgram.compile(player.source,
            PlayerHints(player.signatureExpression, player.nClass, player.signatureTimestamp))
        assertEquals(player.signatureTimestamp, program.signatureTimestamp)
        // Reuse one compiled program, reorder challenges, and repeat to expose retained mutable state.
        for (cases in listOf(player.cases, player.cases.reversed(), player.cases)) {
            for (case in cases) {
                val actual = if (case.kind == "signature") program.decipherSignature(case.input) else program.transformN(case.input)
                assertEquals(case.output, actual, "$id/${case.kind}/${case.name}")
            }
        }
    }
}
