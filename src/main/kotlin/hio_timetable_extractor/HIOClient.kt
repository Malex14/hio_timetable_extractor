package de.mbehrmann.hio_timetable_extractor

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

class HIOClient(val instance: String) {
    val httpClient = HttpClient(CIO) {
        install(HttpCookies)
        install(HttpTimeout) {
            requestTimeoutMillis = 60 * 1000
        }

        followRedirects = true
    }
    val authToken = runBlocking { getSessionAuthToken() }

    private suspend fun getSessionAuthToken(): String {
        // This is technically not a valid HIO page, but it makes the auth token available
        val response = httpClient.get("${instance}/qisserver/pages/startFlow.xhtml")
        val document = Jsoup.parse(response.bodyAsText())
        return document.getElementById("jsForm")
            ?.getElementsByAttributeValueMatching("name", "authenticity_token")
            ?.`val`()
            ?: throw NoSuchElementException("No auth token was found")
    }

    suspend fun startFlow(
        flow: String,
        extraParameters: List<Pair<String, String>> = emptyList()
    ): Pair<String, Document> {
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
            throw HTTPException("HTTP call to ${instance}/qisserver/pages/startFlow.xhtml?_flowId=${flow} failed with status code ${response.status}")
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
        println("doing action $action ($source) on ${instance}/qisserver/pages/${page}?_flowId=${flow}&_flowExecutionKey=${flowExecutionKey}")

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
            throw HTTPException("HTTP call to ${instance}/qisserver/pages/${page}?_flowId=${flow}&_flowExecutionKey=${flowExecutionKey} failed with status code ${response.status}")
        }
    }

    suspend fun getPage(
        page: String,
        flow: String,
        flowExecutionKey: String,
        expectedStatus: Int? = 200
    ): Document {
        println("getting page: ${instance}/qisserver/pages/${page}?_flowId=${flow}&_flowExecutionKey=${flowExecutionKey}")

        val response = httpClient.get("${instance}/qisserver/pages/${page}") {
            url {
                parameter("_flowId", flow)
                parameter("_flowExecutionKey", flowExecutionKey)
            }

        }

        if (expectedStatus == null || response.status.value == expectedStatus) {
            return Jsoup.parse(response.bodyAsText())
        } else {
            throw HTTPException("HTTP call to ${instance}/qisserver/pages/${page}?_flowId=${flow}&_flowExecutionKey=${flowExecutionKey} failed with status code ${response.status}")
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

        for ((unitId, unit) in unitMap) {
            println("getting details page for unit $unitId")

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
        }
    }
}