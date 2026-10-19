package com.example.network.debrid

enum class DebridErrorType {
    InvalidKey,
    NotPremium,
    NotCached,
    Timeout,
    RateLimited,
    Infringing,
    AccountLimit,
    FileNotFound,
    Network,
    Unsupported,
    Unknown
}

enum class CacheState {
    CACHED,
    NOT_CACHED,
    UNKNOWN
}

sealed class DebridResult {
    data class Success(
        val streamUrl: String,
        val filename: String? = null,
        val sizeBytes: Long? = null,
        val headers: Map<String, String> = emptyMap(),
        val providerName: String
    ) : DebridResult()

    data class Error(
        val type: DebridErrorType = DebridErrorType.Unknown,
        val message: String,
        val statusCode: Int? = null,
        val providerName: String,
        val retryable: Boolean = false
    ) : DebridResult()
}

interface DebridProvider {
    val name: String
    fun isConfigured(): Boolean
    suspend fun checkCache(hash: String): CacheState
    suspend fun resolveStream(magnetOrQuery: String): DebridResult
}
