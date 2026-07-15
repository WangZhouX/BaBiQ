package com.wzx.huitai.integration.http

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException

class KtorHuitaiTransport(
    baseUrl: String,
    private val httpClient: HttpClient,
) : HuitaiTransport {
    private val baseUrl = baseUrl.validateBaseUrl()

    override suspend fun send(request: HuitaiRequest): HuitaiTransportOutcome {
        return try {
            httpClient.prepareRequest(baseUrl + request.relativePath) {
                method = HttpMethod(request.method)
                headers {
                    request.headers.forEach { (name, value) -> append(name, value) }
                }
                setBody(request.body)
            }.execute { response ->
                try {
                    val responseHeaders = linkedMapOf<String, List<String>>()
                    response.headers.entries().forEach { (name, values) ->
                        responseHeaders[name] = values.toList()
                    }
                    HuitaiTransportOutcome.ResponseReceived(
                        httpStatus = response.status.value,
                        headers = responseHeaders,
                        body = response.bodyAsBytes(),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Error) {
                    throw error
                } catch (_: IOException) {
                    HuitaiTransportOutcome.AmbiguousAfterSend
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Error) {
            throw error
        } catch (_: UnresolvedAddressException) {
            return HuitaiTransportOutcome.NotSent
        } catch (_: ConnectException) {
            return HuitaiTransportOutcome.NotSent
        } catch (_: IOException) {
            HuitaiTransportOutcome.AmbiguousAfterSend
        }
    }

    private fun String.validateBaseUrl(): String {
        val parsed = Url(this)
        require(parsed.protocol.name == "http" || parsed.protocol.name == "https") {
            "baseUrl must use HTTP or HTTPS"
        }
        require(parsed.user == null && parsed.password == null) { "baseUrl must not contain user info" }
        require(parsed.fragment.isEmpty()) { "baseUrl must not contain a fragment" }
        require(parsed.parameters.isEmpty()) { "baseUrl must not contain query parameters" }
        return trimEnd('/')
    }
}
