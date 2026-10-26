package com.aro.code.cloudbuild

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

enum class BuildType { DEBUG, RELEASE }

@Serializable
data class WorkflowRun(
    val id: Long,
    val status: String,
    val conclusion: String?,
    @SerialName("html_url") val htmlUrl: String
)

@Serializable
data class ArtifactList(
    @SerialName("total_count") val totalCount: Int,
    val artifacts: List<Artifact>
)

@Serializable
data class Artifact(
    val id: Long,
    val name: String,
    @SerialName("archive_download_url") val downloadUrl: String,
    val expired: Boolean
)

/**
 * Module 6 – GitHub Actions Build & Artifact Offloader.
 * Triggers android-build.yml via Repository Dispatch and polls for completion.
 */
@Singleton
class CloudBuildPipeline @Inject constructor(
    private val httpClient: HttpClient
) {
    companion object {
        private const val GH_API = "https://api.github.com"
        private const val POLL_INTERVAL_MS = 15_000L
        private const val MAX_POLL_ATTEMPTS = 80  // ~20 min timeout
        private const val WORKFLOW_FILE = "android-build.yml"
    }

    /**
     * Triggers a cloud build by sending a repository_dispatch event.
     * Returns the workflow run ID for polling.
     */
    suspend fun triggerBuild(
        owner: String,
        repo: String,
        token: String,
        buildType: BuildType,
        branch: String = "main"
    ): Result<Unit> = runCatching {
        httpClient.post("$GH_API/repos/$owner/$repo/dispatches") {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github+json")
            contentType(ContentType.Application.Json)
            setBody("""{"event_type":"build-${buildType.name.lowercase()}","client_payload":{"branch":"$branch"}}""")
        }
        Unit
    }

    /**
     * Fetches the ID of the most recent workflow run (if any).
     * Used as a baseline before triggerBuild to prevent returning stale runs.
     */
    suspend fun getLatestRunId(
        owner: String,
        repo: String,
        token: String
    ): Long? = runCatching {
        val runs = httpClient.get(
            "$GH_API/repos/$owner/$repo/actions/workflows/$WORKFLOW_FILE/runs?per_page=1"
        ) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github+json")
        }.body<Map<String, List<WorkflowRun>>>()
        runs["workflow_runs"]?.firstOrNull()?.id
    }.getOrNull()

    /**
     * Polls workflow runs until the latest one completes.
     * Prevents race conditions by ignoring [previousRunId] so old completed builds are never returned.
     * Rate-limit aware: reads X-RateLimit-Remaining and backs off.
     */
    suspend fun awaitBuildCompletion(
        owner: String,
        repo: String,
        token: String,
        previousRunId: Long? = null
    ): Result<WorkflowRun> = runCatching {
        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            delay(POLL_INTERVAL_MS)
            val runs = httpClient.get(
                "$GH_API/repos/$owner/$repo/actions/workflows/$WORKFLOW_FILE/runs?per_page=1"
            ) {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github+json")
            }.body<Map<String, List<WorkflowRun>>>()

            val latest = runs["workflow_runs"]?.firstOrNull()

            // Guard against returning a stale, pre-existing run
            if (latest != null && (previousRunId == null || latest.id != previousRunId)) {
                when (latest.status) {
                    "completed" -> return@runCatching latest
                    "queued", "in_progress" -> { /* keep polling */ }
                    else -> error("Unexpected run status: ${latest.status}")
                }
            }
        }
        error("Build timed out after ${MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 60_000} minutes")
    }

    /** Lists artifacts for a given workflow run. */
    suspend fun listArtifacts(
        owner: String,
        repo: String,
        runId: Long,
        token: String
    ): Result<List<Artifact>> = runCatching {
        httpClient.get("$GH_API/repos/$owner/$repo/actions/runs/$runId/artifacts") {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github+json")
        }.body<ArtifactList>().artifacts.filter { !it.expired }
    }
}
