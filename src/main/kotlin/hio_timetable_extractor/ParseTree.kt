package de.mbehrmann.hio_timetable_extractor

import org.jsoup.nodes.Element

internal fun parseTree(tree: Element): Pair<Int, CourseCatalog> {
    val courseCatalog = CourseCatalog(
        mutableListOf(),
        mutableMapOf(),
        mutableMapOf(),
        mutableMapOf()
    )

    for (row in tree.children()) {
        parseTreeRow(row, courseCatalog)
    }

    val periodIdRegex = Regex("periodId=(\\d+)")
    val periodId = periodIdRegex.find(
        tree.getElementById("hierarchy:content-container:courseCatalogFieldset:courseCatalog:0:unitLeafElementRootPermalink:permalinkPopup:permalink")
            ?.ownText()
            ?: throw NoSuchElementException("periodId not found")
    )?.groupValues[1]?.toInt() ?: throw NoSuchElementException("periodId not found")

    return Pair(periodId, courseCatalog)
}

private fun parseTreeRow(row: Element, courseCatalog: CourseCatalog) {
    val prefix = "hierarchy:content-container:courseCatalogFieldset:courseCatalog:0:"
    val suffix = ":row"

    val rowId = row.id()
    if (!rowId.startsWith(prefix) || prefix.length >= rowId.length - suffix.length) return

    val nodeIdStr = rowId.substring(prefix.length, rowId.length - suffix.length)
    val nodeId = nodeIdStr.split(':').map(String::toInt)
    val nodeType = row.getElementsByTag("img").first()?.attribute("title")?.value ?: return

    when (nodeType) {
        "Überschriftenelement", "Konto", "Teilmodul" -> parseGroupLikeNode(
            row,
            nodeType,
            nodeId,
            nodeIdStr,
            courseCatalog
        )

        "Modul" -> parseModuleNode(row, nodeId, courseCatalog)

        "Veranstaltung" -> parseModulePart(row, nodeId, courseCatalog)
    }
}

fun getUnitIdFromHref(linkElem: Element): Int {
    val idRegex = Regex("unitId=(\\d+)")
    return idRegex.find(
        linkElem.attribute("href")?.value ?: throw NoSuchElementException()
    )?.groupValues[1]?.toInt()
        ?: throw NoSuchElementException()
}

private fun parseGroupLikeNode(
    row: Element,
    nodeType: String,
    nodeId: List<Int>,
    nodeIdStr: String,
    courseCatalog: CourseCatalog
) {
    val name = if (nodeType == "Überschriftenelement") {
        row.getElementById("hierarchy:content-container:courseCatalogFieldset:courseCatalog:0:${nodeIdStr}:ot_3")
    } else {
        row.getElementsByClass("treeElementName").first()
    }?.ownText() ?: "Unbekanntes Element"

    if (nodeId.size == 1) { // Fakultät
        courseCatalog.faculties.add(Faculty(name, mutableListOf()))
        return
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
        return
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


private fun parseModuleNode(row: Element, nodeId: List<Int>, courseCatalog: CourseCatalog) {
    val faculty = courseCatalog.faculties[nodeId[0]]
    var studyCourseModuleGroups = faculty.studyCourses[nodeId[1]].moduleGroups
    if (2 <= nodeId.size - 2) {
        for (i in nodeId.subList(2, nodeId.size - 2)) {
            studyCourseModuleGroups = studyCourseModuleGroups[i]!!.subGroups
        }
    }
    val moduleGroup = studyCourseModuleGroups[nodeId[nodeId.size - 2]]!!

    val linkElem = row.getElementsByTag("a").first() ?: throw NoSuchElementException()
    val name = linkElem.ownText()
    val id = getUnitIdFromHref(linkElem)

    moduleGroup.modules[nodeId.last()] = id
    courseCatalog.modules[id] = Module(
        id = id, name = name, parts = mutableMapOf(), subModules = mutableMapOf()
    )
}

private fun parseModulePart(row: Element, nodeId: List<Int>, courseCatalog: CourseCatalog) {
    val iterator = nodeId.listIterator(2)
    val faculty = courseCatalog.faculties[nodeId[0]]
    var moduleGroup = faculty.studyCourses[nodeId[1]].moduleGroups[iterator.next()]
        ?: throw NoSuchElementException()
    for (i in iterator) {
        moduleGroup = moduleGroup.subGroups[i] ?: break
    }

    val linkElem = row.getElementsByTag("a").first() ?: throw NoSuchElementException()
    val name = linkElem.ownText()
    val id = getUnitIdFromHref(linkElem)
    val tmp = name.substringAfter(' ')
    val number = name.substringBefore(' ')
    val split = tmp.split(" - ")
    val partName = split.first()
    val type = ModulePartType.fromHIOString(split.last())

    val moduleId = moduleGroup.modules[iterator.previous()]
    val module = courseCatalog.modules[moduleId] ?: return // TODO: throw NoSuchElementException()

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