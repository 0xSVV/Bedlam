package ru.shapovalov.bedlam.testing

import com.arkivanov.mvikotlin.core.rx.observer
import com.arkivanov.mvikotlin.core.store.Bootstrapper
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store

fun <S : Any, M : Any> Reducer<S, M>.reduceAll(state: S, vararg messages: M): S =
    messages.fold(state) { acc, message -> acc.reduce(message) }

fun <L : Any> Store<*, *, L>.recordLabels(): List<L> {
    val recorded = mutableListOf<L>()
    labels(observer(onNext = { recorded += it }))
    return recorded
}

inline fun <S : Store<*, *, *>, R> S.disposeAfter(block: (S) -> R): R =
    try {
        block(this)
    } finally {
        dispose()
    }

class TestBootstrapper<A : Any> : Bootstrapper<A> {

    private var consumer: ((A) -> Unit)? = null

    override fun init(actionConsumer: (A) -> Unit) {
        consumer = actionConsumer
    }

    override fun invoke() = Unit

    override fun dispose() {
        consumer = null
    }

    fun send(action: A) {
        checkNotNull(consumer) { "bootstrapper is not attached to a store" }.invoke(action)
    }
}
