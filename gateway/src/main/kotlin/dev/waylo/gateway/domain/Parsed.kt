package dev.waylo.gateway.domain

/**
 * Result of parsing untrusted input into a domain type: either the value, or *all* problems found.
 * ("Parse, don't validate": once you hold a domain value it is known to be valid.)
 */
sealed interface Parsed<out T> {
    data class Valid<out T>(val value: T) : Parsed<T>
    data class Invalid(val errors: List<FieldError>) : Parsed<Nothing> {
        constructor(field: String, message: String) : this(listOf(FieldError(field, message)))
    }

    fun <R> map(transform: (T) -> R): Parsed<R> = when (this) {
        is Valid -> Valid(transform(value))
        is Invalid -> this
    }

    fun <R> flatMap(transform: (T) -> Parsed<R>): Parsed<R> = when (this) {
        is Valid -> transform(value)
        is Invalid -> this
    }

    fun nested(prefix: String): Parsed<T> = when (this) {
        is Valid -> this
        is Invalid -> Invalid(errors.map { it.copy(field = joinPath(prefix, it.field)) })
    }

    companion object {
        fun invalid(message: String): Invalid = Invalid("", message)
    }
}

data class FieldError(val field: String, val message: String)

/**
 * Collects the results of several independent field parsers and only assembles the target once all
 * of them succeeded, so the caller gets every error at once:
 *
 * ```
 * parsing {
 *     val weight = field("weightGrams", Weight.parse(dto.weightGrams))
 *     val dims = field("dimensions", ...)
 *     build { Parcel(weight(), dims()) }
 * }
 * ```
 */
class ParseScope @PublishedApi internal constructor() {
    private val errors = mutableListOf<FieldError>()

    fun interface Field<out T> {
        operator fun invoke(): T
    }

    fun <T> field(name: String, parsed: Parsed<T>): Field<T> = when (val p = parsed.nested(name)) {
        is Parsed.Valid -> Field { p.value }
        is Parsed.Invalid -> {
            errors += p.errors
            Field { error("field '$name' is invalid; build {} must not run") }
        }
    }

    fun <T> build(assemble: () -> T): Parsed<T> =
        if (errors.isEmpty()) Parsed.Valid(assemble()) else Parsed.Invalid(errors.toList())
}

inline fun <T> parsing(block: ParseScope.() -> Parsed<T>): Parsed<T> = ParseScope().block()

/** Parses a required value: `null` becomes a "missing" error, anything else goes through [parse]. */
inline fun <R : Any, T> required(raw: R?, parse: (R) -> Parsed<T>): Parsed<T> =
    if (raw == null) Parsed.invalid("is required") else parse(raw)

/** Parses every element of a list and reports errors per index (`items[2]`). */
fun <R : Any, T> List<R?>.parseEach(parse: (R) -> Parsed<T>): Parsed<List<T>> {
    val parsed = mapIndexed { i, raw -> required(raw, parse).nested("[$i]") }
    val errors = parsed.filterIsInstance<Parsed.Invalid>().flatMap { it.errors }
    return if (errors.isEmpty()) Parsed.Valid(parsed.map { (it as Parsed.Valid).value }) else Parsed.Invalid(errors)
}

private fun joinPath(prefix: String, field: String) = when {
    field.isEmpty() -> prefix
    field.startsWith("[") -> prefix + field
    else -> "$prefix.$field"
}
