package de.mbehrmann.hio_timetable_extractor

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@Serializable
data class CourseCatalog(
    var faculties: MutableList<Faculty>,
    var modules: MutableMap<Int, Module>,
    var moduleParts: MutableMap<Int, ModulePart>,
    var rooms: MutableMap<Int, Room>
)

@Serializable
data class Faculty(
    var name: String,
    var studyCourses: MutableList<StudyCourse>
)

@Serializable
data class StudyCourse(
    var name: String,
    var po: String,
    var moduleGroups: MutableMap<Int, ModuleGroup>
)

@Serializable
data class ModuleGroup(
    var name: String,
    var subGroups: MutableMap<Int, ModuleGroup>,
    var modules: MutableMap<Int, Int>
)

@Serializable
data class Module(
    var id: Int,
    var name: String,
    var shortName: String? = null,
    var longName: String? = null,
    var number: String? = null,
    var language: String? = null,
    var type: ModuleType? = null,
    var recommendedSemester: Int? = null,
    var organisationalUnit: String? = null,
    var sws: Int? = null,
    var duration: Int? = null,
    var interval: ModuleInterval = ModuleInterval.UNKNOWN,
    var parts: MutableMap<Int, Int>,
    var subModules: MutableMap<Int, SubModule>
)

enum class ModuleType {
    MANDATORY, ELECTIVE, UNKNOWN;

    companion object {
        fun fromHIOString(str: String): ModuleType = when (str) {
            "Pflicht" -> MANDATORY
            "Wahlpflicht" -> ELECTIVE
            else -> UNKNOWN
        }
    }
}

@Serializable
data class SubModule(
    var name: String,
    var parts: MutableMap<Int, Int>,
    var subModules: MutableMap<Int, SubModule>
)

//TODO: Prüfungen werden erstmal nicht berücksichtigt, da sie eine andere Struktur als Veranstaltungen
//      haben und die Datenlage/-qualität derzeit schlecht ist.
@Serializable
data class ModulePart(
    var id: Int,
    var name: String,
    var shortName: String? = null,
    var longName: String? = null,
    var number: String,
    var shortComment: String? = null,
    var organisationalUnit: String? = null,
    var type: ModulePartType,
    var interval: ModuleInterval? = null,
    var sws: Double? = null,
    var parallelGroups: MutableList<ParallelGroup>,
    var enrollmentTimeRanges: String? = null //TODO: kann man noch in was schönes parsen...
)

@Serializable
data class ParallelGroup(
    var shortName: String?,
    var name: String?,
    var groupNumber: Int?,
    var sws: Double,
    var language: String?,
    var instructors: List<String>?,
    var maxParticipantCount: Int?,
    var dates: MutableList<ParallelGroupDate>,
    var originalTitle: String,
)

@Serializable
data class ParallelGroupDate( //TODO: vielleicht die daten aus dem "Einzeltermine anzeigen"-Dialog nehmen
    var rhythm: ParallelGroupDateRhythm,
    var weekday: DayOfWeek?,
    var startTime: @Contextual LocalTime,
    var endTime: @Contextual LocalTime,
    var startDate: @Contextual LocalDate,
    var endDate: @Contextual LocalDate,
    var cancellations: List<@Contextual LocalDate>,
    var estimatedParticipantCount: Int?,
    var remarks: String?,
    var instructors: List<String>,
    var room: Int?
)

@Serializable
data class Room(
    var id: Int,
    var number: String,
    var address: String,
    var building: String?,
    //TODO: hier gibt es noch weitere Infos
)

enum class ParallelGroupDateRhythm {
    ONE_TIME,
    WEEKLY,
    BI_WEEKLY,
    TRI_WEEKLY,
    MONTHLY,
    BLOCK,
    UNKNOWN;

    companion object {
        fun fromHIOString(str: String): ParallelGroupDateRhythm = when (str) {
            "Einzeltermin" -> ONE_TIME
            "wöchentlich" -> WEEKLY
            "14-täglich" -> BI_WEEKLY
            "dreiwöchentlich" -> TRI_WEEKLY
            "vierwöchentlich" -> MONTHLY
            "Blockveranstaltung" -> BLOCK
            else -> {
                println("unbekanntes intervall $str")
                UNKNOWN
            }
        }
    }
}

enum class ModulePartType {
    LECTURE,
    LECTURE_AND_EXERCISE,
    SEMINAR_LECTURE,
    SEMINAR,
    SMALL_GROUP_PROJECT,
    LAB_EXERCISE,
    PRACTICAL_EXERCISE,
    THEORETICAL_EXERCISE,
    PROJECT,
    INTERNSHIP,
    PRACTICE_GROUP,
    UNKNOWN;

    companion object {
        fun fromHIOString(str: String): ModulePartType = when (str) {
            "Seminaristischer Unterricht" -> SEMINAR_LECTURE
            "Übung" -> THEORETICAL_EXERCISE
            "Laborpraktikum oder Laborübung" -> LAB_EXERCISE
            "Seminar" -> SEMINAR
            "Vorlesung/Übung" -> LECTURE_AND_EXERCISE
            "Projekt" -> PROJECT
            "Kleingruppenprojekt" -> SMALL_GROUP_PROJECT
            "Praktische Übung" -> PRACTICAL_EXERCISE
            "Vorlesung", "Lehrvortrag" -> LECTURE
            "Praktikum" -> INTERNSHIP
            "Praxisgruppe" -> PRACTICE_GROUP
            else -> UNKNOWN
        }
    }
}

enum class ModuleInterval {
    EVERY_SEMESTER,
    IRREGULAR,
    ONLY_DURING_WINTER,
    ONLY_DURING_SUMMER,
    UNKNOWN;

    companion object {
        fun fromHIOString(str: String): ModuleInterval = when (str) {
            "in jedem Semester" -> EVERY_SEMESTER
            "Unregelmäßig" -> IRREGULAR
            "nur im Wintersemester" -> ONLY_DURING_WINTER
            "nur im Sommersemester" -> ONLY_DURING_SUMMER
            else -> {
                println(str)
                UNKNOWN
            }
        }
    }
}