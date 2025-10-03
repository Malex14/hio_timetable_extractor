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

/**
 * source: https://gist.github.com/mayankmkh/92084bdf2b59288d3e74c3735cccbf9f
 */
fun Any.prettyString(): String {

    var indentLevel = 0
    val indentWidth = 4

    fun padding() = "".padStart(indentLevel * indentWidth)

    val toString = toString()

    val stringBuilder = StringBuilder(toString.length)

    var i = 0
    while (i < toString.length) {
        when (val char = toString[i]) {
            '(', '[', '{' -> {
                indentLevel++
                stringBuilder.appendLine(char).append(padding())
            }

            ')', ']', '}' -> {
                indentLevel--
                stringBuilder.appendLine().append(padding()).append(char)
            }

            ',' -> {
                stringBuilder.appendLine(char).append(padding())
                // ignore space after comma as we have added a newline
                val nextChar = toString.getOrElse(i + 1) { char }
                if (nextChar == ' ') i++
            }

            else -> {
                stringBuilder.append(char)
            }
        }
        i++
    }

    return stringBuilder.toString()
}

fun Any.prettyPrint() {
    println(prettyString())
}