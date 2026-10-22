package meow.nyx.quackjs

// Host expectations stay readable; containers/functions are never unwrapped or copied.
internal fun testHostValue(value: Any?): Any? = when (value) {
    is JsString -> value.value
    is JsNumber -> value.value
    is JsBoolean -> value.value
    JsNull -> null
    is List<*> -> value.map(::testHostValue)
    else -> value
}

internal fun testJsValue(value: Any?): JsValue = when (value) {
    is JsValue -> value
    is String -> JsString(value)
    is Double -> JsNumber(value)
    is Int -> JsNumber(value.toDouble())
    is Boolean -> JsBoolean(value)
    null -> JsNull
    else -> error("Unsupported test value: $value")
}

internal fun <T> assertEquals(expected: T, actual: T, message: String? = null) =
    kotlin.test.assertEquals(testHostValue(expected), testHostValue(actual), message)
