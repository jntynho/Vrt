package com.example.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class StashPerformer(
    val id: String,
    val name: String,
    val disambiguation: String? = null,
    val aliases: List<String> = emptyList(),
    val gender: String? = null,
    val country: String? = null,
    val imageUrl: String? = null,
    val images: List<String> = emptyList()
)

data class StashStudio(
    val id: String,
    val name: String,
    val parentName: String? = null,
    val logoUrl: String? = null,
    val childIds: List<String> = emptyList()
)

data class StashScene(
    val id: String,
    val title: String,
    val details: String? = null,
    val date: String? = null,
    val studioId: String? = null,
    val studioName: String? = null,
    val studioLogo: String? = null,
    val coverUrl: String? = null,
    val femalePerformers: List<StashPerformer> = emptyList()
)

data class StashSceneQueryResult(
    val count: Int = 0,
    val scenes: List<StashScene> = emptyList()
)

object StashDbApiService {
    private const val GRAPHQL_ENDPOINT = "https://stashdb.org/graphql"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private const val SCENE_QUERY = """
        query QueryScenes(${'$'}input: SceneQueryInput!) {
          queryScenes(input: ${'$'}input) {
            count
            scenes {
              id
              title
              details
              date
              images { url }
              studio {
                id
                name
                urls { url type }
                images { url }
                parent {
                  id
                  name
                  urls { url type }
                  images { url }
                }
              }
              performers {
                as
                performer {
                  id
                  name
                  gender
                  images { url }
                }
              }
            }
          }
        }
    """

    private const val SEARCH_PERFORMERS_QUERY = """
        query SearchPerformers(${'$'}term: String!) {
          searchPerformers(term: ${'$'}term, limit: 30) {
            count
            performers {
              id
              name
              disambiguation
              aliases
              gender
              country
              images { url }
            }
          }
        }
    """

    private const val FIND_PERFORMER_QUERY = """
        query FindPerformer(${'$'}id: ID!) {
          findPerformer(id: ${'$'}id) {
            id
            images { url }
          }
        }
    """

    private const val SEARCH_STUDIOS_QUERY = """
        query SearchStudios(${'$'}term: String!) {
          searchStudio(term: ${'$'}term, limit: 30) {
            id
            name
            urls { url type }
            images { url }
            parent {
              id
              name
              urls { url type }
              images { url }
              parent {
                id
                name
                images { url }
              }
            }
            child_studios {
              id
              name
            }
          }
        }
    """

    private const val FIND_STUDIO_QUERY = """
        query FindStudio(${'$'}id: ID!) {
          findStudio(id: ${'$'}id) {
            id
            name
            urls { url type }
            images { url }
            parent {
              id
              name
              urls { url type }
              images { url }
            }
            child_studios {
              id
              name
            }
          }
        }
    """

    private const val STUDIO_HIERARCHY_QUERY = """
        query FindStudioHierarchy(${'$'}id: ID!) {
          findStudio(id: ${'$'}id) {
            id
            child_studios {
              id
              child_studios { id }
            }
          }
        }
    """

    private val dateFormats = arrayOf(
        "yyyy-MM-dd",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM",
        "yyyy"
    )

    fun parseDateToMillis(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        val clean = dateStr.trim()
        if (clean.length == 10 && clean[4] == '-' && clean[7] == '-') {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return sdf.parse(clean)?.time
            } catch (_: Exception) {}
        }
        for (fmt in dateFormats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(clean)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {}
        }
        return null
    }

    private fun extractDomain(urlStr: String): String? {
        return try {
            val cleanUrl = if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
                "https://$urlStr"
            } else urlStr
            val uri = java.net.URI(cleanUrl)
            val host = uri.host ?: return null
            if (host.startsWith("www.")) host.substring(4) else host
        } catch (_: Exception) {
            null
        }
    }

    private fun extractStudioLogo(studioObj: JSONObject?): String? {
        if (studioObj == null) return null

        val directImages = studioObj.optJSONArray("images")
        if (directImages != null && directImages.length() > 0) {
            val url = directImages.optJSONObject(0)?.optString("url")?.trim()
            if (!url.isNullOrBlank()) return url
        }

        val parentObj = studioObj.optJSONObject("parent")
        if (parentObj != null) {
            val parentImages = parentObj.optJSONArray("images")
            if (parentImages != null && parentImages.length() > 0) {
                val url = parentImages.optJSONObject(0)?.optString("url")?.trim()
                if (!url.isNullOrBlank()) return url
            }

            val grandParentObj = parentObj.optJSONObject("parent")
            if (grandParentObj != null) {
                val grandParentImages = grandParentObj.optJSONArray("images")
                if (grandParentImages != null && grandParentImages.length() > 0) {
                    val url = grandParentImages.optJSONObject(0)?.optString("url")?.trim()
                    if (!url.isNullOrBlank()) return url
                }
            }
        }

        val urlsArray = studioObj.optJSONArray("urls") ?: parentObj?.optJSONArray("urls")
        if (urlsArray != null) {
            for (i in 0 until urlsArray.length()) {
                val urlItem = urlsArray.optJSONObject(i)?.optString("url")
                if (!urlItem.isNullOrBlank()) {
                    val domain = extractDomain(urlItem)
                    if (!domain.isNullOrBlank() && !domain.contains("stashdb.org")) {
                        return "https://www.google.com/s2/favicons?domain=$domain&sz=128"
                    }
                }
            }
        }
        return null
    }

    private suspend fun executeGraphQL(
        query: String,
        variables: JSONObject,
        apiKey: String
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("StashDB API Key is required"))
        }
        try {
            val bodyJson = JSONObject().apply {
                put("query", query.trimIndent())
                put("variables", variables)
            }

            val request = Request.Builder()
                .url(GRAPHQL_ENDPOINT)
                .header("ApiKey", apiKey.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            NetworkClient.okHttpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("StashDB Error: HTTP ${response.code}"))
                }

                val json = JSONObject(rawBody)
                val errors = json.optJSONArray("errors")
                if (errors != null && errors.length() > 0) {
                    val errorMsg = errors.optJSONObject(0)?.optString("message") ?: "GraphQL query error"
                    return@withContext Result.failure(Exception(errorMsg))
                }

                val dataObj = json.optJSONObject("data") ?: JSONObject()
                Result.success(dataObj)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchPerformers(query: String, apiKey: String, blockMalePerformers: Boolean = true): Result<List<StashPerformer>> {
        val vars = JSONObject().apply { put("term", query) }
        return executeGraphQL(SEARCH_PERFORMERS_QUERY, vars, apiKey).map { dataObj ->
            val performersArray = dataObj.optJSONObject("searchPerformers")?.optJSONArray("performers") ?: JSONArray()
            val results = mutableListOf<StashPerformer>()

            for (i in 0 until performersArray.length()) {
                val item = performersArray.optJSONObject(i) ?: continue
                val gender = item.optString("gender", "").uppercase()
                if (blockMalePerformers && gender != "FEMALE") continue

                val id = item.optString("id")
                val name = item.optString("name")
                if (id.isBlank() || name.isBlank()) continue

                val disambiguation = item.optString("disambiguation").ifBlank { null }
                val country = item.optString("country").ifBlank { null }

                val aliasesList = mutableListOf<String>()
                val aliasesArray = item.optJSONArray("aliases")
                if (aliasesArray != null) {
                    for (j in 0 until aliasesArray.length()) {
                        val alias = aliasesArray.optString(j)
                        if (alias.isNotBlank()) aliasesList.add(alias)
                    }
                }

                val imagesSet = LinkedHashSet<String>()
                val imagesArray = item.optJSONArray("images")
                if (imagesArray != null) {
                    for (j in 0 until imagesArray.length()) {
                        val url = imagesArray.optJSONObject(j)?.optString("url")?.trim()
                        if (!url.isNullOrBlank()) imagesSet.add(url)
                    }
                }

                results.add(
                    StashPerformer(
                        id = id,
                        name = name,
                        disambiguation = disambiguation,
                        aliases = aliasesList,
                        gender = gender,
                        country = country,
                        imageUrl = imagesSet.firstOrNull(),
                        images = imagesSet.toList()
                    )
                )
            }
            results.distinctBy { it.id }
        }
    }

    suspend fun fetchPerformerAllImages(stashDbId: String?, performerName: String, apiKey: String): List<String> {
        val imagesSet = LinkedHashSet<String>()
        if (apiKey.isNotBlank() && !stashDbId.isNullOrBlank()) {
            val vars = JSONObject().apply { put("id", stashDbId) }
            executeGraphQL(FIND_PERFORMER_QUERY, vars, apiKey).onSuccess { dataObj ->
                val imagesArr = dataObj.optJSONObject("findPerformer")?.optJSONArray("images")
                if (imagesArr != null) {
                    for (i in 0 until imagesArr.length()) {
                        val url = imagesArr.optJSONObject(i)?.optString("url")?.trim()
                        if (!url.isNullOrBlank()) imagesSet.add(url)
                    }
                }
            }
        }

        if (imagesSet.isEmpty() && apiKey.isNotBlank() && performerName.isNotBlank()) {
            val searchRes = searchPerformers(performerName, apiKey).getOrNull()
            val match = searchRes?.firstOrNull { it.id == stashDbId } ?: searchRes?.firstOrNull()
            match?.images?.let { imagesSet.addAll(it) }
        }

        return imagesSet.toList()
    }

    suspend fun searchStudios(query: String, apiKey: String): Result<List<StashStudio>> {
        val vars = JSONObject().apply { put("term", query) }
        return executeGraphQL(SEARCH_STUDIOS_QUERY, vars, apiKey).map { dataObj ->
            val studiosArray = dataObj.optJSONArray("searchStudio") ?: JSONArray()
            val results = mutableListOf<StashStudio>()

            for (i in 0 until studiosArray.length()) {
                val item = studiosArray.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                if (id.isBlank() || name.isBlank()) continue

                val parentName = item.optJSONObject("parent")?.optString("name")?.ifBlank { null }
                val logoUrl = extractStudioLogo(item)

                val childIds = mutableListOf<String>()
                val childArray = item.optJSONArray("child_studios")
                if (childArray != null) {
                    for (c in 0 until childArray.length()) {
                        val cId = childArray.optJSONObject(c)?.optString("id")
                        if (!cId.isNullOrBlank()) childIds.add(cId)
                    }
                }

                results.add(
                    StashStudio(
                        id = id,
                        name = name,
                        parentName = parentName,
                        logoUrl = logoUrl,
                        childIds = childIds
                    )
                )
            }
            results.distinctBy { it.id }
        }
    }

    suspend fun findStudio(id: String, apiKey: String = ""): StashStudio? {
        if (id.isBlank()) return null
        val vars = JSONObject().apply { put("id", id) }
        val result = executeGraphQL(FIND_STUDIO_QUERY, vars, apiKey)
        val studioObj = result.getOrNull()?.optJSONObject("findStudio") ?: return null

        val name = studioObj.optString("name")
        val parentName = studioObj.optJSONObject("parent")?.optString("name")?.ifBlank { null }
        val logoUrl = extractStudioLogo(studioObj)
        val childIds = mutableListOf<String>()
        val childArr = studioObj.optJSONArray("child_studios")
        if (childArr != null) {
            for (i in 0 until childArr.length()) {
                val cId = childArr.optJSONObject(i)?.optString("id")
                if (!cId.isNullOrBlank()) childIds.add(cId)
            }
        }

        return StashStudio(
            id = id,
            name = name,
            parentName = parentName,
            logoUrl = logoUrl,
            childIds = childIds
        )
    }

    private suspend fun fetchStudioHierarchyIds(studioId: String, apiKey: String): List<String> {
        val vars = JSONObject().apply { put("id", studioId) }
        val result = executeGraphQL(STUDIO_HIERARCHY_QUERY, vars, apiKey)
        val studioObj = result.getOrNull()?.optJSONObject("findStudio") ?: return listOf(studioId)

        val idsList = LinkedHashSet<String>().apply { add(studioId) }
        val childArray = studioObj.optJSONArray("child_studios")
        if (childArray != null) {
            for (i in 0 until childArray.length()) {
                val child = childArray.optJSONObject(i) ?: continue
                val cId = child.optString("id")
                if (cId.isNotBlank()) idsList.add(cId)
                val subChildArr = child.optJSONArray("child_studios")
                if (subChildArr != null) {
                    for (j in 0 until subChildArr.length()) {
                        val subId = subChildArr.optJSONObject(j)?.optString("id")
                        if (!subId.isNullOrBlank()) idsList.add(subId)
                    }
                }
            }
        }
        return idsList.toList()
    }

    private suspend fun queryScenesInternal(
        inputObj: JSONObject,
        apiKey: String,
        blockMalePerformers: Boolean = true
    ): Result<StashSceneQueryResult> {
        val vars = JSONObject().apply { put("input", inputObj) }
        return executeGraphQL(SCENE_QUERY, vars, apiKey).map { dataObj ->
            val queryScenesObj = dataObj.optJSONObject("queryScenes")
            val count = queryScenesObj?.optInt("count", 0) ?: 0
            val scenesArray = queryScenesObj?.optJSONArray("scenes") ?: JSONArray()
            StashSceneQueryResult(count = count, scenes = parseScenesJson(scenesArray, blockMalePerformers))
        }
    }

    suspend fun queryPerformerScenes(
        performerId: String,
        apiKey: String,
        page: Int = 1,
        perPage: Int = 20,
        blockMalePerformers: Boolean = true
    ): Result<StashSceneQueryResult> {
        val inputObj = JSONObject().apply {
            put("performers", JSONObject().apply {
                put("value", JSONArray().apply { put(performerId) })
                put("modifier", "INCLUDES")
            })
            put("page", page)
            put("per_page", perPage)
            put("direction", "DESC")
            put("sort", "DATE")
        }
        return queryScenesInternal(inputObj, apiKey, blockMalePerformers)
    }

    suspend fun queryStudioScenes(
        studioId: String,
        apiKey: String,
        page: Int = 1,
        perPage: Int = 20,
        providedChildIds: List<String> = emptyList(),
        blockMalePerformers: Boolean = true
    ): Result<StashSceneQueryResult> {
        if (apiKey.isBlank()) return Result.failure(IllegalArgumentException("StashDB API Key is required"))
        val allStudioIds = if (providedChildIds.isNotEmpty()) {
            (listOf(studioId) + providedChildIds).distinct()
        } else {
            fetchStudioHierarchyIds(studioId, apiKey)
        }

        val inputObj = JSONObject().apply {
            put("studios", JSONObject().apply {
                val idsArray = JSONArray()
                allStudioIds.forEach { idsArray.put(it) }
                put("value", idsArray)
                put("modifier", "INCLUDES")
            })
            put("page", page)
            put("per_page", perPage)
            put("direction", "DESC")
            put("sort", "DATE")
        }
        return queryScenesInternal(inputObj, apiKey, blockMalePerformers)
    }

    private fun parseScenesJson(scenesArray: JSONArray, blockMalePerformers: Boolean = true): List<StashScene> {
        val results = mutableListOf<StashScene>()
        for (i in 0 until scenesArray.length()) {
            val item = scenesArray.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val title = item.optString("title")
            if (id.isBlank() || title.isBlank()) continue

            val details = item.optString("details").ifBlank { null }
            val date = item.optString("date").ifBlank { null }

            val imagesArray = item.optJSONArray("images")
            val coverUrl = if (imagesArray != null && imagesArray.length() > 0) {
                imagesArray.optJSONObject(0)?.optString("url")?.ifBlank { null }
            } else null

            val studioObj = item.optJSONObject("studio")
            val studioId = studioObj?.optString("id")?.ifBlank { null }
            val studioName = studioObj?.optString("name")?.ifBlank { null }
            val studioLogo = extractStudioLogo(studioObj)

            val femalePerformers = mutableListOf<StashPerformer>()
            val performersArray = item.optJSONArray("performers")
            if (performersArray != null) {
                for (j in 0 until performersArray.length()) {
                    val perfEntry = performersArray.optJSONObject(j) ?: continue
                    val perfObj = perfEntry.optJSONObject("performer") ?: continue
                    val gender = perfObj.optString("gender", "").uppercase()
                    if (blockMalePerformers && gender != "FEMALE") continue

                    val perfId = perfObj.optString("id")
                    val perfName = perfObj.optString("name")
                    if (perfId.isBlank() || perfName.isBlank()) continue

                    val perfImages = perfObj.optJSONArray("images")
                    val perfImage = if (perfImages != null && perfImages.length() > 0) {
                        perfImages.optJSONObject(0)?.optString("url")?.ifBlank { null }
                    } else null

                    femalePerformers.add(
                        StashPerformer(
                            id = perfId,
                            name = perfName,
                            gender = gender,
                            imageUrl = perfImage
                        )
                    )
                }
            }

            results.add(
                StashScene(
                    id = id,
                    title = title,
                    details = details,
                    date = date,
                    studioId = studioId,
                    studioName = studioName,
                    studioLogo = studioLogo,
                    coverUrl = coverUrl,
                    femalePerformers = femalePerformers
                )
            )
        }
        return results.distinctBy { it.id }
    }
}
