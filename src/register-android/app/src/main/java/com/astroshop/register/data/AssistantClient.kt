// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.data

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/** What the cashier gets back. A failure carries something a person at a counter can act on. */
sealed interface AssistantAnswer {
    data class Ok(val text: String) : AssistantAnswer
    data class Failed(val detail: String) : AssistantAnswer
}

/**
 * The shop assistant, asked from the counter.
 *
 * **The route, and why it changed.** The assistant is Astro Shop's `shop-assistant` service (the
 * chart's `agent` component, renamed by `OTEL_SERVICE_NAME`), serving `POST /prompt` on
 * `agent:8010`. Nothing in `src/frontend-proxy/envoy.tmpl.yaml` routes to it, so the register used
 * to take the only way in from outside the cluster: the `/chatbot/` route to a Gradio UI, which
 * posts the question on to the agent.
 *
 * That path cost the demo its best trace. The chatbot raises no server span at all - measured on
 * hsn, `chatbot` produced 22 spans in six hours and every one was a *client* span - so there is
 * nothing to extract the incoming `traceparent` into, and the agent's work begins a brand new
 * trace. The register's session was left holding a two-span trace of the proxy hop while the
 * LangGraph, Bedrock and tool-call spans sat in a trace nothing pointed at.
 *
 * So the register now asks the frontend, and the frontend asks the agent - the same shape as the
 * pickup API, and the frontend is ours to change. Node auto-instrumentation propagates the context
 * outbound and the agent extracts it, so the assistant's spans hang off the register's own request
 * and a cashier's question in Session Replay leads to the model call that answered it.
 *
 * One call now, not two: Gradio's queue-then-poll API went with the chatbot.
 */
class AssistantClient(baseUrl: String) {

    private val api: AssistantApi = Retrofit.Builder()
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                // A model answering through a tool-calling agent is slow and the request holds the
                // connection open until it finishes. The sale routes' 10 s would cut it off. The
                // frontend gives up at 75 s and returns a readable error, which lands first.
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AssistantApi::class.java)

    suspend fun ask(
        question: String,
        storeId: String,
        registerId: String,
        productId: String,
    ): AssistantAnswer {
        val request = AskRequest(
            question = question,
            storeId = storeId,
            registerId = registerId,
            productId = productId,
        )
        return when (val result = apiCall { api.ask(request) }) {
            is ApiResult.Failed -> AssistantAnswer.Failed(transportDetail(result.status))
            is ApiResult.Ok -> {
                val body = result.value
                val text = body?.answer?.trim().orEmpty()
                when {
                    text.isNotEmpty() -> AssistantAnswer.Ok(text)
                    !body?.error.isNullOrBlank() -> AssistantAnswer.Failed(body.error.orEmpty())
                    else -> AssistantAnswer.Failed("the assistant answered with nothing")
                }
            }
        }
    }

    /**
     * 0 is the register's own word for "the call never reached a server". The frontend answers 504
     * when the model outran its own patience and 502 when the agent could not be reached at all;
     * both are worth saying plainly to whoever is standing at the till.
     */
    private fun transportDetail(status: Int): String = when (status) {
        0 -> "the assistant could not be reached"
        504 -> "the assistant took too long to answer"
        502 -> "the assistant could not be reached"
        else -> "the assistant answered HTTP $status"
    }
}

private data class AskRequest(
    val question: String,
    @SerializedName("storeId") val storeId: String,
    @SerializedName("registerId") val registerId: String,
    @SerializedName("productId") val productId: String,
)

private data class AskResponse(
    val answer: String? = null,
    val error: String? = null,
)

private interface AssistantApi {

    @POST("api/assistant")
    suspend fun ask(@Body body: AskRequest): Response<AskResponse>
}
