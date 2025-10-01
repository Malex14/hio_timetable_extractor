package de.mbehrmann.hio_timetable_extractor

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path

const val HIO_INSTANCE = "https://myhaw.haw-hamburg.de:443" // without tailing slash

val GERMAN_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu")

suspend fun main() {
    val client = HIOClient(HIO_INSTANCE)

    //val (semester, tree) = getAndExpandCourseTree(client)
    //Files.writeString(Path("/tmp/tree.html"), tree.toString())
    val semester = "file"
    val tree = Jsoup.parse(Path("/home/malte/Downloads/tree.html")).getElementsByTag("tbody").first()!!

    val (periodId, courseCatalog) = parseTree(tree)

    //addModuleInfoToCourseCatalog(client, courseCatalog, periodId)
    addModulePartInfoToCourseCatalog(client, courseCatalog, periodId)

    Files.writeString(
        Path("/tmp/courseCatalog_${semester.replace(Regex("[ /\\\\]"), "_")}.json"),
        JSON_SERIALIZER.encodeToString(courseCatalog)
    )

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
                dateTableBody?.let { parseParallelGroupDate(it, courseCatalog) } ?: mutableListOf()
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
            val roomStrRegex = Regex("(.*?) \\((.*?)(?: \\((.*?)\\))?\\)")
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
                weekday = when (val str = getColText("column2")) {
                    "Mo" -> DayOfWeek.MONDAY
                    "Di" -> DayOfWeek.TUESDAY
                    "Mi" -> DayOfWeek.WEDNESDAY
                    "Do" -> DayOfWeek.THURSDAY
                    "Fr" -> DayOfWeek.FRIDAY
                    "Sa" -> DayOfWeek.SATURDAY
                    "So" -> DayOfWeek.SUNDAY
                    "" -> null
                    else -> throw IllegalArgumentException("unknown day of week: $str")
                },
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
                    ?.getElementById("li")
                    ?.map(Element::ownText) ?: listOf(),
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

            val shortNameAndNameRegex = Regex("(\\d{2}_+${modulePart.shortName}) ?(.*?) ?\\(?1")
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

private fun parseTree(tree: Element): Pair<Int, CourseCatalog> {
    val prefix = "hierarchy:content-container:courseCatalogFieldset:courseCatalog:0:"
    val suffix = ":row"
    val getId = { linkElem: Element ->
        val idRegex = Regex(".*unitId=(\\d+)&.*")
        idRegex.find(
            linkElem.attribute("href")?.value ?: throw NoSuchElementException()
        )?.groupValues[1]?.toInt()
            ?: throw NoSuchElementException()
    }

    val courseCatalog = CourseCatalog(
        mutableListOf(),
        mutableMapOf(),
        mutableMapOf(),
        mutableMapOf()
    )

    for (row in tree.children()) {
        val rowId = row.id()
        if (!rowId.startsWith(prefix) || prefix.length >= rowId.length - suffix.length) continue

        val nodeIdStr = rowId.substring(prefix.length, rowId.length - suffix.length)
        val nodeId = nodeIdStr.split(':').map(String::toInt)

        val nodeType = row.getElementsByTag("img").first()?.attribute("title")?.value ?: continue

        fun getModuleGroup(courseCatalog: CourseCatalog, nodeId: List<Int>, nonGroupPart: Int = 1): ModuleGroup? {
            val faculty = courseCatalog.faculties[nodeId[0]]
            var studyCourseModuleGroups = faculty.studyCourses[nodeId[1]].moduleGroups
            if (2 <= nodeId.size - 1 - nonGroupPart) {
                for (i in nodeId.subList(2, nodeId.size - 1 - nonGroupPart)) {
                    studyCourseModuleGroups = studyCourseModuleGroups[i]!!.subGroups
                }
            }
            return studyCourseModuleGroups[nodeId[nodeId.size - 1 - nonGroupPart]]
        }

        when (nodeType) {
            "Überschriftenelement", "Konto", "Teilmodul" -> {
                val name = if (nodeType == "Überschriftenelement") {
                    row.getElementById("${prefix}${nodeIdStr}:ot_3")
                } else {
                    row.getElementsByClass("treeElementName").first()
                }?.ownText() ?: "Unbekanntes Element"

                if (nodeId.size == 1) { // Fakultät
                    courseCatalog.faculties.add(Faculty(name, mutableListOf()))
                    continue
                }

                val faculty = courseCatalog.faculties[nodeId[0]]

                if (nodeId.size == 2) { // Studiengang
                    val studyCourseName = name.substringBefore(" (")
                    val po = name.substringAfter('(').trimEnd(')')

                    faculty.studyCourses.add(
                        StudyCourse(
                            studyCourseName, po, mutableMapOf()
                        )
                    )
                    continue
                }

                var moduleGroups = faculty.studyCourses[nodeId[1]].moduleGroups
                var moduleGroup: ModuleGroup? = null
                val iterator = nodeId.listIterator(2)
                for (i in iterator) {
                    moduleGroup = moduleGroups[i] ?: break
                    moduleGroups = moduleGroup.subGroups
                }

                var subModules = courseCatalog.modules[moduleGroup?.modules[iterator.previous()]]?.subModules
                if (nodeType == "Teilmodul" && subModules != null) { // Submodul (außer, wenn statt Konto aus Versehen Teilmodul ausgewählt wurde
                    iterator.next() // correct iterator position

                    for (i in iterator) {
                        subModules = subModules!![i]?.subModules ?: break
                    }

                    subModules[nodeId.last()] = SubModule(
                        name, mutableMapOf(), mutableMapOf()
                    )

                } else { // Modul-Gruppe
                    moduleGroups[nodeId.last()] = ModuleGroup(
                        name, mutableMapOf(), mutableMapOf()
                    )
                }
            }

            "Modul" -> {
                val moduleGroup = getModuleGroup(courseCatalog, nodeId)!!

                val linkElem = row.getElementsByTag("a").first() ?: throw NoSuchElementException()
                val name = linkElem.ownText()
                val id = getId(linkElem)

                moduleGroup.modules[nodeId.last()] = id
                courseCatalog.modules[id] = Module(
                    id = id, name = name, parts = mutableMapOf(), subModules = mutableMapOf()
                )
            }

            "Veranstaltung" -> {
                val iterator = nodeId.listIterator(2)
                val faculty = courseCatalog.faculties[nodeId[0]]
                var moduleGroup = faculty.studyCourses[nodeId[1]].moduleGroups[iterator.next()]
                    ?: throw NoSuchElementException()
                for (i in iterator) {
                    moduleGroup = moduleGroup.subGroups[i] ?: break
                }

                val linkElem = row.getElementsByTag("a").first() ?: throw NoSuchElementException()
                val name = linkElem.ownText()
                val id = getId(linkElem)
                val tmp = name.substringAfter(' ')
                val number = name.substringBefore(' ')
                val split = tmp.split(" - ")
                val partName = split.first()
                val type = ModulePartType.fromHIOString(split.last())

                val moduleId = moduleGroup.modules[iterator.previous()]
                val module = courseCatalog.modules[moduleId] ?: continue // TODO: throw NoSuchElementException()

                if (iterator.nextIndex() == nodeId.size - 2) { // there are no submodules
                    module.parts[nodeId.last()] = id
                } else { // we need to traverse submodules
                    iterator.next() // correct iterator position

                    var subModule: SubModule =
                        module.subModules[iterator.next()] ?: throw NoSuchElementException()
                    for (i in iterator) {
                        subModule = subModule.subModules[i] ?: break
                    }

                    subModule.parts[iterator.previous()] = id
                }

                courseCatalog.moduleParts[id] = ModulePart(
                    id = id,
                    name = partName,
                    number = number,
                    type = type,
                    parallelGroups = mutableListOf(),
                )
            }

        }
    }

    val periodIdRegex = Regex("periodId=(\\d+)")
    val periodId = periodIdRegex.find(
        tree.getElementById("hierarchy:content-container:courseCatalogFieldset:courseCatalog:0:unitLeafElementRootPermalink:permalinkPopup:permalink")
            ?.ownText()
            ?: throw NoSuchElementException("periodId not found")
    )?.groupValues[1]?.toInt() ?: throw NoSuchElementException("periodId not found")

    return Pair(periodId, courseCatalog)
}


private suspend fun getAndExpandCourseTree(client: HIOClient): Pair<String, Element> {
    val flow = "showCourseCatalog-flow"
    val page = "cm/exa/coursecatalog/showCourseCatalog.xhtml"
    val (flowExecutionKey) = client.startFlow(flow)

    suspend fun getTree(): Pair<String, Element> {
        val document = client.getPage(page, flow, flowExecutionKey)

        val fieldset =
            document.getElementById("hierarchy:content-container:courseCatalogFieldset:courseCatalogFieldset_innerFieldset")
                ?: throw NoSuchElementException("expected element not found")
        val legend = fieldset.getElementsByTag("legend")
            .first()
            ?.text()
            ?: throw NoSuchElementException("expected element not found")
        val tableBody = fieldset.getElementsByClass("treeTableWithIcons")
            .first()
            ?.firstElementChild()
            ?: throw NoSuchElementException("expected element not found")

        return Pair(legend, tableBody)
    }

    val getAction = { element: Element ->
        element.getElementsByTag("div")
            .first()
            ?.attribute("id")
            ?.value
            ?: throw NoSuchElementException("action not found")
    }

    val (_, unexpandedTree) = getTree()

    val facultiesRegex = Regex("hierarchy:content-container:courseCatalogFieldset:courseCatalog:0:\\d+:row")
    for (facultyElement in unexpandedTree.getElementsByAttributeValueMatching("id", facultiesRegex.toPattern())) {
        // .id() is not used here, since we want to detect missing attributes
        val action = getAction(facultyElement)
        val source = facultyElement.getElementsByTag("button")
            .first()
            ?.attribute("id")
            ?.value
            ?: throw NoSuchElementException("source not found")

        client.doAction(
            page,
            flow,
            flowExecutionKey,
            source,
            action
        )
    }

    val (_, partiallyExpandedTree) = getTree()

    val studyCoursesRegex = Regex("hierarchy:content-container:courseCatalogFieldset:courseCatalog:0:\\d+:\\d+:row")
    for (studyCourseElement in partiallyExpandedTree.getElementsByAttributeValueMatching(
        "id",
        studyCoursesRegex.toPattern()
    )) {
        val action = getAction(studyCourseElement)
        val source = studyCourseElement.getElementsByTag("button")
            .last()
            ?.attribute("id")
            ?.value
            ?: throw NoSuchElementException("source not found")

        client.doAction(
            page,
            flow,
            flowExecutionKey,
            source,
            action
        )
    }

    return getTree()
}