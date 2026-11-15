package com.funinstaller.app

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.ServiceUnavailableException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolChoiceAuto
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.anthropic.models.beta.messages.BetaUsage
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration

object Models {
    const val HAIKU = "claude-haiku-5-5"
    const val SONNET = "claude-sonnet-5-5"
    const val OPUS = "claude-opus-5-5"

    fun displayName(model: String) = when (model) {
        HAIKU -> "Haiku 5.5"
        SONNET -> "Sonnet 5.5"
        OPUS -> "Opus 5.5"
        else -> model
    }
}

/**
 * Which model does which job.
 * Saver: Sonnet plans and rescues, Haiku does the step-by-step tapping.
 * Best: Opus plans and rescues, Sonnet taps.
 */
data class ModelSet(val planner: String, val executor: String, val rescuer: String) {
    companion object {
        val SAVER = ModelSet(planner = Models.SONNET, executor = Models.HAIKU, rescuer = Models.SONNET)
        val BEST = ModelSet(planner = Models.OPUS, executor = Models.SONNET, rescuer = Models.OPUS)
        fun forMode(mode: String) = if (mode == "best") BEST else SAVER
    }
}

/** Adds up what a job has cost so far (an estimate from list prices). */
class CostMeter {
    private data class Price(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double)

    private val prices = mapOf(
        Models.HAIKU to Price(0.10, 0.50, 0.01, 0.125),
        Models.SONNET to Price(2.00, 10.00, 0.10, 2.50),
        Models.OPUS to Price(4.00, 20.00, 0.20, 5.00),
    )

    @Volatile
    var dollars = 0.0
        private set
    var calls = 0
        private set

    @Synchronized
    fun add(model: String, usage: BetaUsage) {
        val p = prices[model] ?: prices.getValue(Models.OPUS)
        val searches = usage.serverToolUse().map { it.webSearchRequests() }.orElse(0L)
        dollars += (usage.inputTokens() * p.input +
            usage.cacheReadInputTokens().orElse(0L) * p.cacheRead +
            usage.cacheCreationInputTokens().orElse(0L) * p.cacheWrite +
            usage.outputTokens() * p.output) / 1_000_000.0 + searches * 0.01
        calls++
    }

    fun format(): String = "$" + String.format("%.2f", dollars)
}

class ClaudeException(message: String) : Exception(message)

/** Thin wrapper over the official Anthropic Java SDK (beta Messages endpoint). */
class Claude(apiKey: String) : AutoCloseable {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofMinutes(5))
        .maxRetries(3)
        .build()

    fun send(params: MessageCreateParams, model: String, meter: CostMeter): BetaMessage {
        val msg = try {
            client.beta().messages().create(params)
        } catch (t: Throwable) {
            throw ClaudeException(friendlyError(t))
        }
        meter.add(model, msg.usage())
        return msg
    }

    override fun close() = client.close()

    companion object {
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** A request with the system prompt cached and effort set. */
        fun request(model: String, system: String, effort: BetaOutputConfig.Effort, maxTokens: Long): MessageCreateParams.Builder {
            val b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .systemOfBetaTextBlockParams(listOf(
                    BetaTextBlockParam.builder()
                        .text(system)
                        .cacheControl(BetaCacheControlEphemeral.builder().build())
                        .build(),
                ))
                .outputConfig(BetaOutputConfig.builder().effort(effort).build())
                .toolChoice(BetaToolChoiceAuto.builder().disableParallelToolUse(true).build())
            // Server-side fallback: if a request is declined by a safety classifier,
            // the API retries it on a fallback model. (Haiku has no fallback.)
            if (model != Models.HAIKU) b.addBeta(FALLBACK_BETA).fallbacksDefault()
            return b
        }

        /** A strict client tool: every property is required and no others are allowed. */
        fun tool(name: String, description: String, properties: Map<String, Map<String, Any>>): BetaTool {
            val props = BetaTool.InputSchema.Properties.builder()
            properties.forEach { (k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)) }
            return BetaTool.builder()
                .name(name)
                .description(description)
                .strict(true)
                .inputSchema(BetaTool.InputSchema.builder()
                    .properties(props.build())
                    .required(properties.keys.toList())
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build())
                .build()
        }

        fun firstToolUse(msg: BetaMessage): BetaToolUseBlock? =
            msg.content().firstNotNullOfOrNull { it.toolUse().orElse(null) }

        fun textOf(msg: BetaMessage): String =
            msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()

        fun inputOf(block: BetaToolUseBlock): Map<String, Any?> {
            val raw: Map<*, *> = block._input().convert(Map::class.java) ?: return emptyMap()
            return raw.entries.associate { (k, v) -> k.toString() to v }
        }

        fun isRefusal(msg: BetaMessage): Boolean = msg.stopReason().orElse(null) == BetaStopReason.REFUSAL
        fun isPause(msg: BetaMessage): Boolean = msg.stopReason().orElse(null) == BetaStopReason.PAUSE_TURN

        fun friendlyError(t: Throwable): String = when (t) {
            is UnauthorizedException -> "Your Claude API key was rejected. Check it on the Fun Installer screen."
            is PermissionDeniedException -> "Your API key isn't allowed to use this model."
            is RateLimitException -> "Claude is limiting how fast your account can make requests. Wait a minute and try again."
            is OverloadedException, is InternalServerException, is ServiceUnavailableException ->
                "Claude is busy right now. Try again in a minute."
            is BadRequestException -> "Claude rejected the request: ${t.message}"
            is AnthropicIoException -> "Couldn't reach Claude. Check the internet connection."
            else -> t.message ?: t.toString()
        }
    }
}

// Small helpers for reading tool inputs.
fun Map<String, Any?>.str(key: String): String = this[key]?.toString() ?: ""
fun Map<String, Any?>.int(key: String, default: Int = -1): Int =
    (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toDoubleOrNull()?.toInt() ?: default
fun Map<String, Any?>.num(key: String): Double =
    (this[key] as? Number)?.toDouble() ?: this[key]?.toString()?.toDoubleOrNull() ?: 0.0
fun Map<String, Any?>.bool(key: String): Boolean = this[key] as? Boolean ?: (this[key]?.toString() == "true")
fun Map<String, Any?>.strList(key: String): List<String> =
    (this[key] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
fun Map<String, Any?>.objList(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>)?.mapNotNull { item ->
        (item as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
    } ?: emptyList()
