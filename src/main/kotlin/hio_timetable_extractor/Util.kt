package de.mbehrmann.hio_timetable_extractor

import java.time.DayOfWeek

val envs: Map<String, String> = System.getenv();

fun dowFromGermanShortString(str: String): DayOfWeek? = when (str) {
    "Mo" -> DayOfWeek.MONDAY
    "Di" -> DayOfWeek.TUESDAY
    "Mi" -> DayOfWeek.WEDNESDAY
    "Do" -> DayOfWeek.THURSDAY
    "Fr" -> DayOfWeek.FRIDAY
    "Sa" -> DayOfWeek.SATURDAY
    "So" -> DayOfWeek.SUNDAY
    "" -> null
    else -> throw IllegalArgumentException("unknown day of week: $str")
}

fun getEnvOrThrow(name: String, allowEmpty: Boolean = false): String =
    getEnvAndMapOrThrow(name, allowEmpty) { it }

fun <R> getEnvAndMapOrThrow(name: String, allowEmpty: Boolean = false, block: (String) -> R?): R =
    envs[name]
        .takeIf { !it.isNullOrBlank() || allowEmpty }
        ?.let(block)
        ?: throw IllegalArgumentException("'$name' environment variable is not set or invalid")