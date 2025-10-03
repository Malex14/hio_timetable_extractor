package de.mbehrmann.hio_timetable_extractor

import org.jsoup.nodes.Element

internal suspend fun getAndExpandCourseTree(client: HIOClient): Pair<String, Element> {
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