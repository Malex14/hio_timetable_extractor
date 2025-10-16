package de.mbehrmann.hio_timetable_extractor

import java.time.DayOfWeek

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