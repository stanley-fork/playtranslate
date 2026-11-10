package com.playtranslate.ask

import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.CompletionFailedException
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.classifyBackendFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where one question stands. Everything but [Idle] is "a question is on
 *  screen", which is what keeps a collapsed Ask section's card up. */
sealed class AskState {
    object Idle : AskState()

    /** Sent; no answer yet. [service] is the display name of the service
     *  or model being asked, the next one's when the pass falls through;
     *  null until the pass picks one, which it does before it first
     *  suspends, so on a surface's immediate dispatcher no frame shows a
     *  question without its name. */
    data class Asking(val question: AskQuestion, val service: String? = null) : AskState()

    data class Answered(
        val question: AskQuestion,
        val text: String,
        /** The display name of the service or model that answered. */
        val answeredBy: String,
    ) : AskState()

    /** Nothing answered. [service] and [failure] say which online service
     *  refused first and how, for the reason line; both null when there is
     *  nothing to say beyond "it failed" (an on-device model, or every
     *  service in a cooldown). */
    data class Failed(
        val question: AskQuestion,
        val service: String? = null,
        val failure: BackendFailure? = null,
    ) : AskState()
}

/**
 * One results surface's Ask conversation: the question being asked about the
 * sentence on show, and its answer. The surface's view renders [state];
 * nothing else holds any of it.
 *
 * The session is KEYED BY ITS SENTENCE (the text and the language pair,
 * [AskSentence]): [bind] says what is on show, and binding anything other
 * than what is bound ends the question in hand. A surface calls [bind] from
 * the one place it renders a result, every time, so an answer can never
 * outlive its sentence because some path forgot to reset it.
 *
 * At most one request is in flight. A new question, [close] or a new
 * sentence cancels it; a cancelled request writes nothing.
 */
class AskSession(
    private val scope: CoroutineScope,
    /** Puts a prompt to the user's LLM, naming each one to [onAttempt] as
     *  it starts asking it. The registry's pass in production; a test
     *  substitutes it. */
    private val complete: suspend (
        system: String,
        user: String,
        onAttempt: (TranslationBackend) -> Unit,
    ) -> TranslationBackendRegistry.Completion =
        { system, user, onAttempt -> TranslationBackendRegistry.complete(system, user, onAttempt) },
) {
    private val _state = MutableStateFlow<AskState>(AskState.Idle)
    val state: StateFlow<AskState> = _state.asStateFlow()

    /** The sentence on show, null with none. */
    var sentence: AskSentence? = null
        private set

    private var job: Job? = null

    /** [sentence] is what the surface shows now (null: no result). Binding
     *  the sentence already bound changes nothing, so a surface may call
     *  this on every render. */
    fun bind(sentence: AskSentence?) {
        if (sentence == this.sentence) return
        this.sentence = sentence
        close()
    }

    /** Ask [question] about the bound sentence, in place of whatever was
     *  being asked. A no-op with no sentence bound. */
    fun ask(question: AskQuestion) {
        val about = sentence ?: return
        job?.cancel()
        _state.value = AskState.Asking(question)
        job = scope.launch {
            val next = try {
                val messages = AskPrompts.build(question, about)
                val answer = complete(messages.system, messages.user) { backend ->
                    // Named only while this request is the live one, by the
                    // same promise as the write below: a pass that goes on
                    // after its cancel (a completer that returned or failed
                    // without seeing it) is stopped here, naming nobody.
                    ensureActive()
                    _state.value = AskState.Asking(question, backend.displayName)
                }
                AskState.Answered(question, answer.text.trim(), answer.backend.displayName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompletionFailedException) {
                // The reason line names a service the user can act on, so
                // only an online service's refusal is carried.
                val service = e.backend?.takeIf { it.requiresInternet }
                AskState.Failed(
                    question,
                    service = service?.displayName,
                    failure = service?.let { _ -> e.cause?.let(::classifyBackendFailure) },
                )
            } catch (_: Exception) {
                AskState.Failed(question)
            }
            // "A cancelled request writes nothing" is kept here, not left
            // to the completer: one that returned normally without seeing
            // the cancel would otherwise have its answer shown. (Today's
            // completers all throw the cancellation.)
            ensureActive()
            _state.value = next
        }
    }

    /** Ask the failed question again. A no-op in any other state. */
    fun retry() {
        (_state.value as? AskState.Failed)?.let { ask(it.question) }
    }

    /** Put the question away: the request in flight is cancelled and the
     *  answer is gone. The sentence stays bound. */
    fun close() {
        job?.cancel()
        job = null
        _state.value = AskState.Idle
    }

    /** Take up a finished [answer] to a question about [sentence], as if it
     *  had been asked here, in place of whatever was being asked: a surface
     *  rebuilt around the same result (the capture sheet coming back from a
     *  page) carries its answer over. */
    fun restore(sentence: AskSentence, answer: AskState.Answered) {
        this.sentence = sentence
        close()
        _state.value = answer
    }
}
