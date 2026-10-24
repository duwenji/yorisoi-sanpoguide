package io.github.duwenji.yorisoi.sanpoguide.station

import io.github.duwenji.yorisoi.core.format.TalkEventKind
import io.github.duwenji.yorisoi.kit.channel.Station
import io.github.duwenji.yorisoi.sanpoguide.prompt.StationPrompts

/** Variables for `guide/system`. */
fun Station.guideSystemVars(): Map<String, Any?> = StationPrompts.guideSystemVars(::slot, guideLength)

/** Variables for `companion/system`. */
fun Station.companionSystemVars(): Map<String, Any?> = StationPrompts.companionSystemVars(::slot)

/** This channel's extra instructions for [kind], appended after the event's own prompt; null if none. */
fun Station.eventInstructions(kind: TalkEventKind): String? = StationPrompts.eventInstructions(::slot, kind)
