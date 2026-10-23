package meow.nyx.quackjs

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import meow.nyx.quackjs.verification.PlayerFixtures

class PlayerConcurrencyTest {
    @Test fun compiledPlayersHaveNoSharedInvocationState() {
        val players = PlayerFixtures.load().map { player ->
            player to PlayerProgram.compile(player.source, PlayerHints(player.signatureExpression, player.nClass, player.signatureTimestamp))
        }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = List(32) {
                Callable {
                    for ((player, program) in players) for (case in player.cases.reversed()) {
                        val actual = if (case.kind == "signature") program.decipherSignature(case.input) else program.transformN(case.input)
                        assertEquals(case.output, actual, "${player.id}/${case.name}")
                    }
                    for ((player, program) in players) {
                        val requests = player.cases.map {
                            if (it.kind == "signature") PlayerChallenge.Signature(it.input) else PlayerChallenge.N(it.input)
                        }
                        assertEquals(player.cases.map { PlayerTransformResult.Success(it.output) },
                            program.transformBatch(requests), "${player.id}/batch")
                    }
                }
            }
            pool.invokeAll(tasks, 30, TimeUnit.SECONDS).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }
}
