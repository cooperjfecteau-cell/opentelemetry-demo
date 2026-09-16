// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.data

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/** What the cashier gets back. A failure carries something a person at a counter can act on. */
sealed interface AssistantAnswer {
    data class Ok(val text: String) : AssistantAnswer
    data class Failed(val detail: String) : AssistantAnswer
}

/**
 * The shop assistant, asked from the counter.
 *
 * **The route.** The assistant is Astro Shop's `shop-assistant` service (the chart's `agent`
 * component, renamed by `OTEL_SERVICE_NAME`), and it serves `POST /prompt` on `agent:8010`. Nothing
 * in `src/frontend-proxy/envoy.tmpl.yaml` routes to it: the only way in from outside the cluster is
 * the `/chatbot/` route, which reaches the `chatbot` service - a Gradio UI whose one job is to post
 * the question to `agent:8010/prompt` and hand the answer back. So the register asks the chatbot,
 * and the chatbot asks the assistant. The GenAI spans the demo wants are raised where they always
 * were, in `shop-assistant`, and the register's request joins that trace like any other.
 *
 * **Gradio's HTTP API is two calls**, which is why this is not one Retrofit method: a POST queues
 * the question and answers with an event id, and a GET on that id blocks until the answer is ready
 * and replies in server-sent-event frames. The function is named `respond` after the Gradio handler
 * (`/gradio_api/info` lists it), and it takes the message and a chat history - always empty here,
 * because a cashier asks one question about the item in their hand.
 */
class AssistantClient(baseUrl: String) {

    private val api: AssistantApi = Retrofit.Builder()
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                // A model answering through a tool-calling agent is slow, and the second call holds
                // the connection open until it finishes. The sale routes' 10 s would cut it off.
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AssistantApi::class.java)

    suspend fun ask(question: String): AssistantAnswer {
        val queued = when (val result = apiCall { api.ask(AskRequest(listOf(question, emptyList<Any>()))) }) {
            is ApiResult.Failed -> return AssistantAnswer.Failed(transportDetail(result.status))
            is ApiResult.Ok -> result.value?.eventId
        }
        if (queued.isNullOrBlank()) return AssistantAnswer.Failed("the assistant did not accept the question")

        return when (val result = apiCall { api.answer(queued) }) {
            is ApiResult.Failed -> AssistantAnswer.Failed(transportDetail(result.status))
            is ApiResult.Ok -> parse(result.value?.string().orEmpty())
        }
    }

    /**
     * Pulls the answer out of the event stream.
     *
     * Only the `complete` frame carries it, as `["", history]` where `history` is the chat after the
     * exchange - so the assistant's reply is the last message. Gradio has written that message's
     * `content` as a plain string and as a list of typed parts in different versions, and the demo
     * upgrades the chatbot with the rest of the shop, so both are read.
     */
    private fun parse(body: String): AssistantAnswer {
        var event = ""
        var completed: String? = null
        body.lineSequence().forEach { line ->
            when {
                line.startsWith(EVENT_PREFIX) -> event = line.removePrefix(EVENT_PREFIX).trim()
                line.startsWith(DATA_PREFIX) && event == EVENT_COMPLETE ->
                    completed = line.removePrefix(DATA_PREFIX).trim()
                line.startsWith(DATA_PREFIX) && event == EVENT_ERROR ->
                    return AssistantAnswer.Failed("the assistant reported an error")
            }
        }
        val data = completed ?: return AssistantAnswer.Failed("the assistant sent no answer")

        val history = runCatching { JSONArray(data).optJSONArray(1) }.getOrNull()
        if (history == null || history.length() == 0) {
            return AssistantAnswer.Failed("the assistant's answer could not be read")
        }
        val last = history.optJSONObject(history.length() - 1)
            ?: return AssistantAnswer.Failed("the assistant's answer could not be read")
        val text = textOf(last).trim()

        return when {
            text.isEmpty() -> AssistantAnswer.Failed("the assistant answered with nothing")
            // The chatbot turns an assistant failure into a chat message rather than an HTTP error,
            // so an answer that starts this way is a failure wearing an answer's clothes.
            text.startsWith(CHATBOT_ERROR_PREFIX) ->
                AssistantAnswer.Failed(text.removePrefix(CHATBOT_ERROR_PREFIX).trim())

            else -> AssistantAnswer.Ok(text)
        }
    }

    private fun textOf(message: JSONObject): String = when (val content = message.opt("content")) {
        is String -> content
        is JSONArray -> (0 until content.length())
            .mapNotNull { content.optJSONObject(it)?.optString("text") }
            .joinToString("\n\n")

        else -> ""
    }

    /** 0 is the register's own word for "the call never reached a server". */
    private fun transportDetail(status: Int): String =
        if (status == 0) "the assistant could not be reached" else "the assistant answered HTTP $status"

    private companion object {
        const val EVENT_PREFIX = "event:"
        const val DATA_PREFIX = "data:"
        const val EVENT_COMPLETE = "complete"
        const val EVENT_ERROR = "error"
        const val CHATBOT_ERROR_PREFIX = "Error:"
    }
}

/** Gradio's envelope: positional inputs in, an event id back. */
private data class AskRequest(val data: List<Any>)

private data class AskQueued(@SerializedName("event_id") val eventId: String? = null)

private interface AssistantApi {

    @POST("chatbot/gradio_api/call/respond")
    suspend fun ask(@Body body: AskRequest): Response<AskQueued>

    @GET("chatbot/gradio_api/call/respond/{eventId}")
    suspend fun answer(@Path("eventId") eventId: String): Response<ResponseBody>
}
