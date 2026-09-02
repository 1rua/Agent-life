package com.openandroidintelligence.conversation.state

/**
 * The four states every remote read can be in.
 *
 * `Empty` and `Failed` are deliberately different: an empty thread list means
 * the Gateway answered and has no threads, while a failure means we could not
 * ask. Collapsing them would let a network error look like "you have no data".
 */
sealed interface Loadable<out T> {
    /** Nothing has been requested yet. */
    data object Idle : Loadable<Nothing>

    data object Loading : Loadable<Nothing>

    data object Empty : Loadable<Nothing>

    data class Ready<out T>(val value: T) : Loadable<T>

    data class Failed(val code: String, val retryable: Boolean = true) : Loadable<Nothing>
}

/** Maps a result onto a [Loadable], using [isEmpty] to distinguish empty from ready. */
fun <T> Result<T>.toLoadable(isEmpty: (T) -> Boolean): Loadable<T> = fold(
    onSuccess = { value -> if (isEmpty(value)) Loadable.Empty else Loadable.Ready(value) },
    onFailure = { cause -> Loadable.Failed(code = errorCodeOf(cause)) },
)

/** Transforms the value inside a Ready state; other states pass through. */
fun <T, R> Loadable<T>.map(transform: (T) -> R): Loadable<R> = when (this) {
    is Loadable.Ready -> Loadable.Ready(transform(value))
    is Loadable.Empty -> Loadable.Empty
    is Loadable.Failed -> this
    Loadable.Idle -> Loadable.Idle
    Loadable.Loading -> Loadable.Loading
}

/**
 * Keeps the Gateway error code when there is one.
 *
 * The UI shows the code because the user acts on it: "re-authenticate" and
 * "retry the attachment" are different buttons, and a generic message would
 * hide which one applies.
 */
fun errorCodeOf(cause: Throwable): String {
    val message = cause.message
    if (!message.isNullOrBlank()) return message
    return cause::class.java.simpleName
}
