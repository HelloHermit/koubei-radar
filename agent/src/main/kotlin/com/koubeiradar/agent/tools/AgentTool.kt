package com.koubeiradar.agent.tools

import com.koubeiradar.agent.llm.ToolSpec
import kotlinx.serialization.json.JsonObject

/** A function the model may call. Tools are READ-ONLY with respect to the outside world (no ordering, no payments). */
interface AgentTool {
    val spec: ToolSpec

    /** Never throws for bad model input; returns `ToolOutput(isError = true)` with an explanation the model can act on. */
    suspend fun execute(arguments: JsonObject): ToolOutput
}

data class ToolOutput(val content: String, val isError: Boolean = false)
