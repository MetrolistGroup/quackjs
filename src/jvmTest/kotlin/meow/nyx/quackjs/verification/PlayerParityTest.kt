package meow.nyx.quackjs.verification

import meow.nyx.quackjs.PlayerHints
import meow.nyx.quackjs.PlayerProgram
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerParityTest {
    @Test fun olderPlayer() = verify("4f38b487")

    @Test fun intermediatePlayer() = verify("10733253")

    @Test fun suppliedPlayer() = verify("eb27e5fd")

    @Test fun latestPublicRegistryPlayer() = verify("5008be0a")
    @Test fun publicPlayerWithCallableArrayNumericCoercion() = verify("60869856")

    @Test fun mathPowRegressionPlayer() {
        verify("55de7e60")
        verify("55de7e60", discover = true)
    }

    private fun verify(
        id: String,
        discover: Boolean = false,
    ) {
        val player = PlayerFixtures.load().single { it.id == id }
        val program =
            if (discover) {
                PlayerProgram.compile(player.source)
            } else {
                PlayerProgram.compile(
                    player.source,
                    PlayerHints(player.signatureExpression, player.nClass, player.signatureTimestamp),
                )
            }
        if (!discover) assertEquals(player.signatureTimestamp, program.signatureTimestamp)
        // Reuse one compiled program, reorder challenges, and repeat to expose retained mutable state.
        for (cases in listOf(player.cases, player.cases.reversed(), player.cases)) {
            for (case in cases) {
                val actual = if (case.kind == "signature") program.decipherSignature(case.input) else program.transformN(case.input)
                assertEquals(case.output, actual, "$id/${case.kind}/${case.name}")
            }
        }
    }
}
