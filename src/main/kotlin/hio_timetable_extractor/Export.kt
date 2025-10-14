package de.mbehrmann.hio_timetable_extractor

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.*
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name

@Serializable
data class Event(
    val id: String,
    val name: String,
    val location: String,
    val description: String,
    val startTime: @Contextual LocalDateTime,
    val endTime: @Contextual LocalDateTime,
)

@Serializable
data class Directory(
    val name: String,
    val subDirectories: MutableList<Directory> = mutableListOf(),
    val events: MutableList<DirectoryEvent> = mutableListOf(),
)

@Serializable
data class DirectoryEvent(
    val id: String,
    val name: String
)

internal fun writeDirectoryAndEventFiles(path: Path, courseCatalog: CourseCatalog) {
    val directories = mutableListOf<Directory>()
    val events = mutableMapOf<String, List<Event>>()

    for (faculty in courseCatalog.faculties) {
        val facultySubdirectory = Directory(faculty.name)

        for (studyCourse in faculty.studyCourses) {
            val studyCourseSubDirectory = Directory("${studyCourse.name} (${studyCourse.po})")

            fun traverse(moduleGroups: Map<Int, ModuleGroup>): List<Directory> {
                val directories = mutableListOf<Directory>()

                for ((_, group) in moduleGroups.entries.sortedBy { it.key }) {
                    val groupDirectory = Directory(group.name)

                    groupDirectory.subDirectories.addAll(traverse(group.subGroups))

                    for (module in group.modules.entries
                        .sortedBy { it.key }
                        .map { courseCatalog.modules[it.value]!! }
                    ) {
                        processModule(
                            module.name,
                            module.parts,
                            module.subModules,
                            groupDirectory,
                            events,
                            courseCatalog
                        )
                    }

                    if (groupDirectory.subDirectories.isNotEmpty() || groupDirectory.events.isNotEmpty()) {
                        directories.add(groupDirectory)
                    }
                }

                return directories
            }

            traverse(studyCourse.moduleGroups)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    studyCourseSubDirectory.subDirectories.addAll(it)
                }

            facultySubdirectory.subDirectories.add(studyCourseSubDirectory)
        }

        if (facultySubdirectory.subDirectories.all { it.subDirectories.isEmpty() }) {
            // if all study courses from faculty are empty clear them all
            facultySubdirectory.subDirectories.clear()
        }

        directories.add(facultySubdirectory)
    }

    Files.writeString(path.resolve("directory.json"), JSON_PASCAL_CASE_SERIALIZER.encodeToString(directories))

    val invalidEventFiles = Files.newDirectoryStream(path) { it.name != "directory.json" }.use {
        it.asSequence().map { file -> file.name }.toSet() - events.keys.map { id -> "${id}.json" }.toSet()
    }
    println("Deleting old event files: ${invalidEventFiles.joinToString(", ")}")
    for (file in invalidEventFiles) {
        path.resolve(file).deleteIfExists()
    }

    for ((id, events) in events) {
        Files.writeString(path.resolve("$id.json"), JSON_PASCAL_CASE_SERIALIZER.encodeToString(events))
    }
}

private fun processModule(
    name: String,
    moduleParts: Map<Int, Int>,
    subModules: Map<Int, SubModule>,
    directory: Directory,
    events: MutableMap<String, List<Event>>,
    courseCatalog: CourseCatalog
) {
    val partsInModule = moduleParts.size
    val partsInSubmodules = subModules.values.asSequence()
        .map(::getSubmoduleModulePartCount).sum()

    if (partsInModule + partsInSubmodules == 0) {
        println("skipping module '$name' since it is empty")
        return
    }

    when {
        partsInModule == 1 && partsInSubmodules == 0 -> {
            // flatten part
            val part = courseCatalog.moduleParts[moduleParts.values.first()]!!
            processModulePart(part, directory, events, courseCatalog)
        }

        partsInSubmodules == 1 && partsInModule == 0 -> {
            // flatten submodule
            var part: ModulePart? = null

            val queue = ArrayDeque<SubModule>()
            queue.addAll(subModules.entries.sortedByDescending { it.key }.map { it.value })
            while (queue.isNotEmpty() && part == null) {
                val subModule = queue.pop()

                part = subModule.parts.values.firstOrNull()?.let { courseCatalog.moduleParts[it]!! }
                if (part != null) {
                    queue.addAll(subModule.subModules.entries.sortedByDescending { it.key }.map { it.value })
                }
            }

            processModulePart(part!!, directory, events, courseCatalog)
        }

        else -> {
            val moduleDirectory = Directory(name)

            for (part in moduleParts.entries.sortedBy { it.key }.map { courseCatalog.moduleParts[it.value]!! }) {
                processModulePart(part, moduleDirectory, events, courseCatalog)
            }

            if (partsInSubmodules > 0) {
                for (subModule in subModules.entries.sortedBy { it.key }.map { it.value }) {
                    processModule(
                        subModule.name,
                        subModule.parts,
                        subModule.subModules,
                        moduleDirectory,
                        events,
                        courseCatalog
                    )
                }
            }

            if (moduleDirectory.events.isNotEmpty() || moduleDirectory.subDirectories.isNotEmpty()) {
                directory.subDirectories.add(moduleDirectory)
            }
        }
    }
}

private fun processModulePart(
    modulePart: ModulePart,
    directory: Directory,
    events: MutableMap<String, List<Event>>,
    courseCatalog: CourseCatalog
) {
    when (modulePart.parallelGroups.size) {
        0 -> {}

        1 -> {
            val parallelGroup = modulePart.parallelGroups.first()
            val id = generateId(modulePart, parallelGroup, true)
            val name = modulePart.shortName?.let { "$it (${modulePart.name})" } ?: modulePart.name

            directory.events.add(DirectoryEvent(id, name))
            events[id] = generateEventsFromParallelGroup(parallelGroup, id, name, courseCatalog)
        }

        else -> {
            val partDirectory = Directory(modulePart.name)
            for (group in modulePart.parallelGroups.sortedBy { it.shortName }) {
                val id = generateId(modulePart, group)
                val name = group.shortName?.let { "$it (${group.name})" } ?: group.name
                ?: modulePart.name // TODO: schöner machen   // pg integrieren

                partDirectory.events.add(DirectoryEvent(id, name))
                events[id] = generateEventsFromParallelGroup(group, id, name, courseCatalog)
            }
            directory.subDirectories.add(partDirectory)
        }
    }
}

private fun generateEventsFromParallelGroup(
    parallelGroup: ParallelGroup,
    id: String,
    name: String,
    courseCatalog: CourseCatalog
): List<Event> {
    val events = mutableListOf<Event>()

    // FIXME: Es gibt einträge die identisch, bis auf den raum sind -> deduplizieren (Priorität: eher niedrig)

    for (pdDate in parallelGroup.dates) {
        when (pdDate.rhythm) {
            ParallelGroupDateRhythm.ONE_TIME, ParallelGroupDateRhythm.BLOCK, ParallelGroupDateRhythm.UNKNOWN -> {
                if (pdDate.cancellations.isEmpty()) {
                    events.add(
                        Event(
                            id = id,
                            name = name,
                            location = pdDate.room?.let { generateRoomString(courseCatalog.rooms[it]!!) }
                                ?: "(kein Raum angegeben)",
                            description = generateDescription(parallelGroup, pdDate),
                            startTime = pdDate.startDate.atTime(pdDate.startTime),
                            endTime = pdDate.endDate.atTime(pdDate.endTime),
                        )
                    )
                }
            }

            else -> {
                var currentDate = pdDate.startDate
                do {
                    if (!pdDate.cancellations.contains(currentDate)) {
                        events.add(
                            Event(
                                id = id,
                                name = name,
                                location = pdDate.room?.let { generateRoomString(courseCatalog.rooms[it]!!) }
                                    ?: "(kein Raum angegeben)",
                                description = generateDescription(parallelGroup, pdDate),
                                startTime = currentDate.atTime(pdDate.startTime),
                                endTime = currentDate.atTime(pdDate.endTime),
                            )
                        )
                    }
                    currentDate = currentDate.plusDays(pdDate.rhythm.asDays()!!)
                } while (!currentDate.isAfter(pdDate.endDate))
            }
        }
    }

    return events
}

private fun generateDescription(parallelGroup: ParallelGroup, pgDate: ParallelGroupDate): String {
    return buildString {
        if (pgDate.instructors.isNotEmpty()) {
            append(
                "Dozent${if (pgDate.instructors.size != 1) "en" else ""}: ${
                    pgDate.instructors.joinToString(", ")
                }\n"
            )
        } else if ((parallelGroup.instructors?.size ?: 0) > 0) {
            append(
                "Dozent${if (parallelGroup.instructors!!.size != 1) "en" else ""}: ${
                    parallelGroup.instructors!!.joinToString(", ")
                }\n"
            )
        }

        if (parallelGroup.groupNumber != null) {
            append("Parallelgruppe: ${parallelGroup.groupNumber}\n")
        }

        when {
            pgDate.estimatedParticipantCount != null && parallelGroup.maxParticipantCount != null -> {
                append("Teilnehmer/-innen: ${pgDate.estimatedParticipantCount} / ${parallelGroup.maxParticipantCount}\n")
            }

            pgDate.estimatedParticipantCount != null -> {
                append("Teilnehmer/-innen: ${pgDate.estimatedParticipantCount}")
            }

            parallelGroup.maxParticipantCount != null -> {
                append("Max. Anzahl von Teilnehmer/-innen: ${parallelGroup.maxParticipantCount}\n")
            }
        }

        append("Rhythmus: ${pgDate.rhythm}\n")
        append("\nOriginaler Titel: ${parallelGroup.originalTitle}")
    }
}

private fun generateRoomString(room: Room): String = "${room.number}, ${room.address}"

private fun generateId(modulePart: ModulePart, parallelGroup: ParallelGroup, isOnlyGroup: Boolean = false): String =
    "${modulePart.id}_${parallelGroup.groupNumber ?: if (isOnlyGroup) 1 else "x"}"

private fun getSubmoduleModulePartCount(subModule: SubModule): Int {
    var count = subModule.parts.size
    val subModuleQueue = ArrayDeque<SubModule>()
    subModuleQueue.addAll(subModule.subModules.values)

    while (subModuleQueue.isNotEmpty()) {
        val subModule = subModuleQueue.pop()
        subModuleQueue.addAll(subModule.subModules.values)
        count += subModule.parts.size
    }

    return count
}