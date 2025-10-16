package de.mbehrmann.hio_timetable_extractor

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private val logger = KotlinLogging.logger {}

class HIOClient(val instance: String) {
    val httpClient = HttpClient(CIO) {
        install(HttpCookies)
        install(HttpTimeout) {
            requestTimeoutMillis = 600 * 1000 // hio instances can take a long time to respond...
        }

        followRedirects = true
    }
    val authToken = runBlocking { getSessionAuthToken() }

    private suspend fun getSessionAuthToken(): String {
        logger.info { "getting session auth token" }

        // This is technically not a valid HIO page, but it makes the auth token available
        val response = httpClient.get("${instance}/qisserver/pages/startFlow.xhtml")
        val document = Jsoup.parse(response.bodyAsText())
        return document.getElementById("jsForm")
            ?.getElementsByAttributeValueMatching("name", "authenticity_token")
            ?.`val`()
            ?: run {
                logger.error { "no auth token was found" }
                throw NoSuchElementException("No auth token was found")
            }
    }

    suspend fun startFlow(
        flow: String,
        extraParameters: List<Pair<String, String>> = emptyList()
    ): Pair<String, Document> {
        logger.info { "starting flow $flow with parameters $extraParameters" }

        val response = httpClient.get("${instance}/qisserver/pages/startFlow.xhtml") {
            url {
                parameter("_flowId", flow)
                for ((key, value) in extraParameters) {
                    parameter(key, value)
                }
            }
        }
        if (response.status.value == 200) {
            return Pair(
                response.request.url.parameters["_flowExecutionKey"]
                    ?: throw NoSuchElementException("No flow execution key was found"),
                Jsoup.parse(response.bodyAsText())
            )
        } else {
            val errorStr =
                "HTTP call to ${instance}/qisserver/pages/startFlow.xhtml?_flowId=${flow} failed with status code ${response.status}"
            logger.error { errorStr }
            throw HTTPException(errorStr)
        }
    }

    suspend fun doAction(
        page: String,
        flow: String,
        flowExecutionKey: String,
        source: String,
        action: String,
        render: Boolean = true
    ) {
        logger.info { "doing action $action ($source) on ${instance}/qisserver/pages/${page}?_flowId=${flow}&_flowExecutionKey=${flowExecutionKey}" }

        val response = httpClient.submitForm(
            url = "${instance}/qisserver/pages/${page}",
            formParameters = parameters {
                append("authenticity_token", authToken)
                append("javax.faces.ViewState", flowExecutionKey)
                append("javax.faces.source", source)
                append("javax.faces.partial.execute", action)
                append("javax.faces.behavior.event", "action")
                if (render) append("javax.faces.partial.render", action)
            }
        ) {
            url {
                parameter("_flowId", flow)
                parameter("_flowExecutionKey", flowExecutionKey)
            }
            headers {
                append("Faces-Request", "partial/ajax")
            }
        }

        if (response.status.value != 200 && response.status.value != 302) {
            val errorStr =
                "HTTP call to ${instance}/qisserver/pages/${page}?_flowId=${flow}&_flowExecutionKey=${flowExecutionKey} failed with status code ${response.status}"
            logger.error { errorStr }
            throw HTTPException()
        }
    }

    suspend fun getPage(
        page: String,
        flow: String,
        flowExecutionKey: String,
        expectedStatus: Int? = 200
    ): Document {
        logger.info { "getting page: ${instance}/qisserver/pages/${page}?_flowId=${flow}&_flowExecutionKey=${flowExecutionKey}" }

        val response = httpClient.get("${instance}/qisserver/pages/${page}") {
            url {
                parameter("_flowId", flow)
                parameter("_flowExecutionKey", flowExecutionKey)
            }
        }

        if (expectedStatus == null || response.status.value == expectedStatus) {
            return Jsoup.parse(response.bodyAsText())
        } else {
            val errorStr =
                "HTTP call to ${instance}/qisserver/pages/${page}?_flowId=${flow}&_flowExecutionKey=${flowExecutionKey} failed with status code ${response.status}"
            logger.error { errorStr }
            throw HTTPException()
        }
    }


    interface UnitDetailsHelper {
        fun getText(label: String, labels: Map<String, Element>): String?
        fun getText(label: String): String?
        val getDescriptionText: (String) -> String?
    }

    suspend fun <T> forEachUnitGetDetailsPage(
        unitMap: Map<Int, T>,
        periodId: Int,
        fn: UnitDetailsHelper.(unitId: Int, unit: T, document: Document, flowExecutionKey: String) -> Unit
    ) {
        val flow = "detailView-flow"
        val units = unitMap.size
        var i = 1

        for ((unitId, unit) in unitMap) {
            logger.info { "getting details page for unit $unitId (${i}/$units | ${i * 100 / units} %)" }

            val (flowExecutionKey, document) = startFlow(
                flow,
                listOf(
                    Pair("unitId", unitId.toString()),
                    Pair("periodId", periodId.toString())
                )
            )

            val labels = document.getElementsByClass("labelWithBG no_pointer")
                .groupingBy { it.ownText() }
                .reduce { _, e, _ -> e }

            object : UnitDetailsHelper {
                override fun getText(label: String, labels: Map<String, Element>) =
                    labels[label]?.nextElementSibling()?.text()

                override fun getText(label: String): String? = getText(label, labels)
                override val getDescriptionText = { label: String ->
                    document.getElementsByTag("legend")
                        .firstOrNull { it.ownText() == label }
                        ?.parent()
                        ?.ownText()
                }
            }.fn(unitId, unit, document, flowExecutionKey)

            i++
        }
    }
}