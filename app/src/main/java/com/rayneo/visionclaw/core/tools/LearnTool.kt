package com.rayneo.visionclaw.core.tools

import com.rayneo.visionclaw.core.network.LearnLmRouter

class LearnTool(
    private val learnLmRouter: LearnLmRouter
) : AiTapTool {

    override val name: String = "learn_topic"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val query = args["query"]?.trim().orEmpty().ifBlank {
            args["topic"]?.trim().orEmpty()
        }
        if (query.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing learning query."))
        }

        return when (val result = learnLmRouter.teach(query)) {
            is LearnLmRouter.LearnResult.Success ->
                Result.success(LearnLmRouter.formatForDisplay(result))
            is LearnLmRouter.LearnResult.ApiKeyMissing ->
                Result.failure(IllegalStateException("LearnLM tutor API key missing."))
            is LearnLmRouter.LearnResult.Error ->
                Result.failure(IllegalStateException(result.message))
        }
    }
}
