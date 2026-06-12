package com.rayneo.visionclaw.core.tools

import android.content.Context
import com.rayneo.visionclaw.core.network.ResearchRouter
import com.rayneo.visionclaw.core.storage.ReadableArtifactStore

class ResearchTool(
    context: Context,
    private val researchRouter: ResearchRouter
) : AiTapTool {

    override val name: String = "research_topic"
    private val artifactStore = ReadableArtifactStore(context)

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val topic = args["topic"]?.trim().orEmpty()
        if (topic.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing topic for research request."))
        }

        // mode='links' switches to URL-list output (used when the user wants
        // a researched list of sources, not a prose report). Default 'report'
        // preserves the existing 2–3 paragraph analytical brief.
        val mode = args["mode"]?.trim()?.lowercase().orEmpty()
        val isLinksMode = mode == "links" || mode == "url" || mode == "urls" || mode == "sources"

        // Optional media-type filter: when the user explicitly named a
        // subset (video / podcast / audio / article / pdf), Gemini passes
        // it through here so the research prompt can bias its search
        // toward sites that match those types. Empty/unset means "no
        // restriction"; codex/web_search will default to encyclopedic
        // sources, which is fine for unconstrained research queries.
        val rawMediaTypes = args["mediaTypes"]?.trim().orEmpty().ifBlank {
            args["media_types"]?.trim().orEmpty()
        }
        val mediaTypes = rawMediaTypes
            .split(',', ' ', '|', '/')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()

        val result = if (isLinksMode) {
            researchRouter.researchLinks(topic, mediaTypes)
        } else {
            researchRouter.research(topic)
        }

        return when (result) {
            is ResearchRouter.ResearchResult.Success -> {
                val text = result.text.trim()
                // Save the artifact for the report-mode path; links-mode
                // output is meant to be ephemeral chat-card content, not a
                // saved research report.
                if (!isLinksMode) {
                    artifactStore.saveResearchReport(
                        topic = topic,
                        title = "Research report: $topic",
                        text = text,
                        sourceLabel = result.model
                    )
                }
                Result.success(text)
            }
            is ResearchRouter.ResearchResult.ApiKeyMissing ->
                Result.failure(IllegalStateException("Research provider API key missing."))
            is ResearchRouter.ResearchResult.Error ->
                Result.failure(IllegalStateException(result.message))
        }
    }
}
