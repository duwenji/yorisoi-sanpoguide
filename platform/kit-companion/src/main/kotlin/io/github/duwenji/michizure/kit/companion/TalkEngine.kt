package io.github.duwenji.michizure.kit.companion

/**
 * The situation a [TalkEngine] decides on. The app says which of its gates are open now; how long
 * a gap is, or what counts as standing still, is the app's business (RFC-003 D-10).
 *
 * @param G the app's gates, e.g. "the small-talk gap has passed" or "not right after a channel switch"
 */
interface TalkGates<G> {
    fun isOpen(gate: G): Boolean
}

/**
 * One kind of thing the companion may say (RFC-003 D-11). [decide] is asked only while all of
 * [gates] are open, and returns what to say, or null to let the next rule speak.
 *
 * @param safety a channel can't switch this off (ADR-003 E-6, E-13). A declaration for the app's
 *   profile and tests; the engine treats the rule like any other (RFC-003 D-13).
 */
class TalkRule<S : TalkGates<G>, D : Any, G>(
    val name: String,
    val gates: Set<G> = emptySet(),
    val safety: Boolean = false,
    val decide: (S) -> D?,
)

/**
 * Asks [rules] in order and takes the first decision; [nothing] when none speaks (RFC-003 D-12).
 * Holds no values and no state.
 */
class TalkEngine<S : TalkGates<G>, D : Any, G>(val rules: List<TalkRule<S, D, G>>, private val nothing: D) {
    init {
        require(rules.map { it.name }.toSet().size == rules.size) { "Rule names must be unique: ${rules.map { it.name }}" }
    }

    fun decide(situation: S): D {
        for (rule in rules) {
            if (rule.gates.all(situation::isOpen)) rule.decide(situation)?.let { return it }
        }
        return nothing
    }
}
