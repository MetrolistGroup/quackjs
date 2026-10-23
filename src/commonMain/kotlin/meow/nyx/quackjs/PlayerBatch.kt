package meow.nyx.quackjs

/** A decoded challenge. Equal inputs of different kinds are separate batch entries. */
public sealed class PlayerChallenge {
    public abstract val input: String

    public data class Signature(override val input: String) : PlayerChallenge()
    public data class N(override val input: String) : PlayerChallenge()
}

/** A batch entry never substitutes the encrypted input when a transform fails. */
public sealed class PlayerTransformResult {
    public data class Success(val value: String) : PlayerTransformResult()
    public data class Failure(val message: String) : PlayerTransformResult()
}
