package meow.nyx.quackjs.verification

import kotlin.test.Test
import kotlin.test.assertEquals

class QuickJsOracleTest {
    @Test
    fun originalFullPlayersMatchIndependentNodeGoldens() {
        for (player in PlayerFixtures.load()) {
            QuickJsPlayer.open(player).use { js ->
                val batch = js.prepareBatch(player.cases)
                repeat(2) {
                    assertEquals(expectedJson(player.cases), js.runBatch(batch), "${player.id}: compiled bridge pass $it")
                }
                assertEquals(expectedJson(player.cases.reversed()), js.solve(player.cases.reversed()), "${player.id}: reversed inputs")
                assertEquals(expectedJson(player.firstPair), js.solve(player.firstPair), "${player.id}: first pair")
            }
        }
    }
}
