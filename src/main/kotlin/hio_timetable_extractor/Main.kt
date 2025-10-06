package de.mbehrmann.hio_timetable_extractor

import org.jsoup.nodes.Element
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime
import kotlin.io.path.Path

const val HIO_INSTANCE = "https://myhaw.haw-hamburg.de:443" // without tailing slash


suspend fun main() {
    HIOClient(HIO_INSTANCE)

    val semester = "Vorlesungsverzeichnis für Wintersemester 2025 2026"
    //val tree = Jsoup.parse(Path("/tmp/tree.html")).getElementsByTag("tbody").first()!!
    //val (semester, tree) = getAndExpandCourseTree(client)
    //Files.writeString(Path("/tmp/tree.html"), tree.toString())
    /*
        val (periodId, courseCatalog) = parseTree(tree)

        addModuleInfoToCourseCatalog(client, courseCatalog, periodId)
        addModulePartInfoToCourseCatalog(client, courseCatalog, periodId)

        Files.writeString(
            Path("/tmp/courseCatalog_${semester.replace(Regex("[ /\\\\]"), "_")}.json"),
            JSON_SERIALIZER.encodeToString(courseCatalog)
        )*/

    val courseCatalogStr =
        Files.readString(Path("/home/malte/Downloads/courseCatalog_${semester.replace(Regex("[ /\\\\]"), "_")}.json"))
    val courseCatalog = JSON_SERIALIZER.decodeFromString<CourseCatalog>(courseCatalogStr)

    writeDirectoryAndEventFiles(Path("/tmp/export"), courseCatalog)
}

private suspend fun addModuleInfoToCourseCatalog(client: HIOClient, courseCatalog: CourseCatalog, periodId: Int) {
    client.forEachUnitGetDetailsPage(courseCatalog.modules, periodId) { _, module, _, _ ->
        with(module) {
            shortName = getText("Kurztext")
            longName = getText("Langtext")
            number = getText("Nummer")
            language = getText("Lehrsprache")
            type = ModuleType.fromHIOString(getText("Teilnahmepflicht") ?: "")
            recommendedSemester = getText("Empfohlenes FS")?.toIntOrNull()
            organisationalUnit = getText("Einrichtungen")
            sws = getText("Semesterwochenstunden")?.toIntOrNull()
            duration = getDescriptionText("Moduldauer")
                ?.substringBefore(' ')
                ?.toIntOrNull()
            interval = ModuleInterval.fromHIOString(getDescriptionText("Angebotshäufigkeit") ?: "")
        }
    }
}

private suspend fun addModulePartInfoToCourseCatalog(client: HIOClient, courseCatalog: CourseCatalog, periodId: Int) {
    client.forEachUnitGetDetailsPage(courseCatalog.moduleParts, periodId) { _, part, document, _ ->
        with(part) {
            shortName = getText("Kurztext")
            longName = getText("Langtext")
            shortComment = getText("Kurzkommentar")
            organisationalUnit = getText("Organisationseinheit")
            interval = ModuleInterval.fromHIOString(getText("Angebotshäufigkeit") ?: "")
            sws = getText("Semesterwochenstunden")?.toDouble()
            enrollmentTimeRanges = getText("Zeitraum")
        }
        val parallelGroups = document.getElementsByAttributeValueMatching(
            "id",
            Regex("^detailViewData:tabContainer:term-planning-container:parallelGroupSchedule_\\d+$").toPattern()
        )
        for (parallelGroup in parallelGroups) {
            val titleElem = parallelGroup.getElementsByTag("h3").first() ?: throw NoSuchElementException()
            val (shortName, name, groupNumber) = parseParallelGroupTitle(
                titleElem.ownText(),
                part,
                parallelGroups.size == 1
            )

            val boxContentElem =
                parallelGroup.getElementsByClass("box_content").first() ?: throw NoSuchElementException()
            val labels = boxContentElem.getElementsByClass("labelWithBG no_pointer")
                .groupingBy { it.ownText() }
                .reduce { _, e, _ -> e }

            val dateTableBody =
                boxContentElem.getElementsByClass("tableWithBorder").first()?.getElementsByTag("tbody")?.first()

            val group = ParallelGroup(
                shortName,
                name,
                groupNumber,
                getText("Semesterwochenstunden", labels)?.toDouble() ?: throw NoSuchElementException(),
                getText("Lehrsprache", labels),
                labels["Verantwortliche/-r"]
                    ?.nextElementSibling()
                    ?.firstElementChild()
                    ?.children()
                    ?.map { it.text() },
                getText("Maximale Anzahl Teilnehmer/-innen")?.toInt(),
                dateTableBody?.let { parseParallelGroupDate(it, courseCatalog) } ?: mutableListOf(),
                titleElem.ownText()
            )

            part.parallelGroups.add(group)
        }
    }
}

private fun parseParallelGroupDate(
    dateTableBody: Element,
    courseCatalog: CourseCatalog
): MutableList<ParallelGroupDate> {
    val result = mutableListOf<ParallelGroupDate>()

    for (row in dateTableBody.children()) {
        val getColText = { col: String ->
            row.getElementsByClass(col).first()?.ownText() ?: throw NoSuchElementException()
        }

        val (startTime, endTime) = getColText("column3").split(" - ").map { LocalTime.parse(it) }
        val (startDate, endDate) = getColText("column5").split(" - ")
            .map { LocalDate.parse(it, GERMAN_DATE_FORMATTER) }
            .let { if (it.size == 1) listOf(it[0], it[0]) else it }

        val roomLinkElem =
            row.getElementsByClass("column9").first()?.lastElementChild()
        var roomId: Int? = null
        if (roomLinkElem != null) {
            val roomIdRegex = Regex("roomId=(\\d+)")
            roomId = roomIdRegex.find(roomLinkElem.attribute("href")?.value ?: "")?.groupValues[1]?.toInt()
                ?: throw NoSuchElementException()
            val roomStr = roomLinkElem.text()
            val roomStrRegex = Regex("(.*?) \\((.*?)(?: \\((.*?)\\))?\\)") // TODO: Ulmenliet ist anders!
            val groupValues = roomStrRegex.find(roomStr)?.groupValues ?: throw NoSuchElementException()

            courseCatalog.rooms[roomId] = Room(
                id = roomId,
                number = groupValues[1],
                address = groupValues[2],
                building = if (groupValues.size == 4) groupValues[3] else null
            )
        }

        result.add(
            ParallelGroupDate(
                rhythm = ParallelGroupDateRhythm.fromHIOString(getColText("column1")),
                weekday = dowFromGermanShortString(getColText("column2")),
                startTime = startTime,
                endTime = endTime,
                startDate = startDate,
                endDate = endDate,
                cancellations = row.getElementsByClass("column4")
                    .first()
                    ?.getElementsByTag("li")
                    ?.map { LocalDate.parse(it.text(), GERMAN_DATE_FORMATTER) } ?: listOf(),
                estimatedParticipantCount = getColText("column6").toIntOrNull(),
                remarks = getColText("column7").takeIf(String::isNotBlank),
                instructors = row.getElementsByClass("column8")
                    .first()
                    ?.getElementsByTag("li")
                    ?.map { it.text().trim() } ?: listOf(),
                room = roomId
            )
        )
    }

    return result
}


private fun parseParallelGroupTitle(
    title: String,
    modulePart: ModulePart,
    isOnlyGroup: Boolean,
): Triple<String?, String?, Int?> {
    println(modulePart.shortName + " - " + modulePart.name)

    val groupNumberRegex = Regex("(\\d+)\\. Parallelgruppe")
    val groupNumber = groupNumberRegex.find(title)?.groupValues?.getOrNull(1)?.toInt()

    var shortName: String? = null
    var name: String? = null
    when {
        title == modulePart.name || title == modulePart.shortName -> {
            println("plain: $title")

            name = modulePart.name
            shortName = modulePart.shortName
        }

        modulePart.number == "1INF-PRO.LV-P" || modulePart.number == "1INF-AIS.LV-P" -> { // projects / seminars in faculty inf
            val projectRegex = Regex("(.*?) \\(\\d+\\. Parallelgruppe\\)")
            projectRegex.find(title)?.groupValues?.let {
                name = it[1]
            }
        }

        modulePart.number == "1AI-ENG1.LV" -> {
            name = modulePart.name
            shortName = "${modulePart.shortName}/${title.substringBefore('_')}"
        }

        title.startsWith("WP/WPP") -> {
            name = title.substring("WP/WPP ".length)
        }

        title.startsWith("VL+Ü") -> {
            name = title.substring("VL+Ü ".length)
            shortName = modulePart.shortName
        }

        title.startsWith(modulePart.name) -> { // faculty inf without shortName
            println("inf wo: $title")

            name = modulePart.name
            shortName = modulePart.shortName
        }

        title.matches(Regex("\\d{2}_+${modulePart.shortName}.*")) -> { // faculty inf with shortName
            println("inf w0: $title")

            val shortNameAndNameRegex = Regex("(\\d{2}_+${modulePart.shortName}) ?(.*?) ?\\(")
            shortNameAndNameRegex.find(title)?.groupValues?.let {
                shortName = "${modulePart.shortName}/${it[1].substringBefore('_')}"
                name = it[2]
            }
        }

        title.matches(Regex("${modulePart.shortName}/\\d{2}.*")) -> { // faculty emi
            println("emi: $title")

            shortName = title.substringBefore(' ')
            name = modulePart.name
        }

        title.matches(Regex("\\d{2} ${modulePart.name}.*${modulePart.number}.*")) -> { // faculty inf with shortName, but different ;)
            println("inf w1: $title")

            shortName = "${modulePart.shortName}/${title.substringBefore(' ')}"
            name = title.substringAfter(' ').substringBefore(" (")
        }

        title.matches(Regex("(\\(online\\) )?\\d{2}( \\(online\\))? (${modulePart.shortName}|.*${modulePart.number}).*")) -> { // faculty inf with shortName. another variant :(
            println("inf w2: $title")

            shortName = if (title.startsWith("(online)")) {
                val start = "(online) ".length
                "${modulePart.shortName}/${title.substring(start, start + 2)}"
            } else {
                "${modulePart.shortName}/${title.take(2)}"
            }
            name = modulePart.name
        }

        title.matches(Regex("\\d{2}\\+\\d{2} .*?")) -> {
            println("minf: $title")
            shortName = "${modulePart.shortName}/${title.take(5)}"
            name = modulePart.name
        }

        title.matches(Regex("\\d+\\.?G - .*")) -> {
            println("ful: $title")
            var dotIndex = title.indexOf('.')
            if (dotIndex < 0) dotIndex = Int.MAX_VALUE

            val numberEndIndex = title.indexOf('G').coerceAtMost(dotIndex)
            shortName = "${modulePart.shortName}/${title.take(numberEndIndex)}"
            name = modulePart.name
        }

        title.matches(Regex("\\d*\\.? ?Termingruppe.*")) -> {
            shortName = if (isOnlyGroup) {
                modulePart.shortName
            } else {
                "${modulePart.shortName}/${groupNumber.toString().padStart(2, '0')}"
            }
            name = modulePart.name
        }

        // parallel group in "Fakultät Soziale Arbeit und Kindheitspädagogik"
        title.startsWith("BABE ")
                || title.startsWith("MASA ")
                || title.matches(Regex("((M ?(\\d+\\.)*(\\d|[A-Z])+ ?\\+ ?)*M ?(\\d+\\.)*(\\d|[A-Z])+( ?\\(gekoppelt\\))?) ?(.*?)( ?\\(\\d+. Par.*|$)")) -> {
            println("sauk: $title")
            val shortNameAndNameRegex =
                Regex("((?:M? ?(?:\\d+\\.)*(?:\\d|[A-Z])+ ?\\+ ?)*M? ?(?:\\d+\\.)*(?:\\d|[A-Z])+(?: ?\\(gekoppelt\\))?) ?(.*?)(?: ?\\(\\d+. Par|$)")
            val cleanedTitle = if (title.startsWith("BABE ") || title.startsWith("MASA ")) {
                title.substring(5)
            } else {
                title
            }

            shortNameAndNameRegex.find(cleanedTitle)?.groupValues?.let {
                shortName = if (it[1].contains(' ')) {
                    if (it[1][0] == ' ') {
                        "M ${it[1].trim()}"
                    } else {
                        it[1]
                    }
                } else {
                    "${it[1][0]} ${it[1].substring(1)}"
                }
                name = it[2]
            }
        }

        else -> { // all other strange entries
            println("unbekannt: $title")

            name = modulePart.name
            shortName = if (title[0].isDigit() && title[1].isDigit()) {
                "${modulePart.shortName}/${title.take(2)}"
            } else if (groupNumber != null && !isOnlyGroup) {
                "${modulePart.shortName}/${groupNumber.toString().padStart(2, '0')}"
            } else {
                modulePart.shortName
            }
        }
    }

    println("'$shortName' - '$name' - '$groupNumber'")

    return Triple(shortName, name, groupNumber)
}
