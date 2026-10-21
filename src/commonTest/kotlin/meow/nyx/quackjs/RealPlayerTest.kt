package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals

class RealPlayerTest {
    @Test fun eb27e5fdTransformDependenciesMatchNodeOracle() {
        val program = PlayerProgram.compile(realPlayerSlice, PlayerHints("Su(11,3932,INPUT)", "cg", 20724))
        val vectors = listOf(
            Triple("KdrqFlzJXl9EcCwlmEy", "yEmlwCcE9lqXz", "FMSB9mkLYExhqEndTt"),
            Triple("0123456789abcdefghijklmno", "3nml0jihgfedcba9876", "dAnUtb3-oHMvmOcWU7RC1xmM"),
            Triple("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-", "-_9876543210ZYXWVUaSRQPONMLKJIHGFEDCBAzdxwvutsrqponmlyjihg", "m4bL7DwMbqhzWG2-rHWl6T47XB9Bfe86z66bci1Z3aWa1uyzwUy_SUvQCufD3Yv"),
            Triple("a/b=?%\u2603\ud83d\ude00", "\ude00\ud83d=", "ZAI3afmu"),
            Triple("a", "", ""),
            Triple("", "", ""),
        )
        assertEquals(20724, program.signatureTimestamp)
        repeat(2) {
            for ((input, signature, n) in vectors) {
                assertEquals(signature, program.decipherSignature(input), "signature: $input")
                assertEquals(n, program.transformN(input), "n: $input")
            }
        }
    }
}
