package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.example.data.TestDefaults
import com.example.data.local.converters.RoomConverters

@Entity(tableName = "links")
@TypeConverters(RoomConverters::class)
data class LinkEntity(
    @PrimaryKey val id: String,
    val stashDbId: String? = null,
    val title: String,
    val urlHD: String? = null,
    val url4K: String? = null,
    val urlVR: String? = null,
    val magnet: String? = null,
    val magnet4K: String? = null,
    val magnetVR: String? = null,
    val torrentUrlHD: String? = null,
    val torrentUrl4K: String? = null,
    val torrentUrlVR: String? = null,
    val torrentSiteName: String? = null,
    val coverImage: String = "",
    val coverOffset: Float = 50f,
    val aspectRatio: String = "16:9",
    val galleryUrls: List<String> = emptyList(),
    val originalGalleryUrls: List<String> = emptyList(),
    val qualityPreference: String = "original",
    val galleryScraperUrl: String? = null,
    val actorIds: List<String> = emptyList(),
    val studioIds: List<String> = emptyList(),
    val assignedDate: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "actors")
@TypeConverters(RoomConverters::class)
data class ActorEntity(
    @PrimaryKey val id: String,
    val stashDbId: String? = null,
    val name: String,
    val imageUrl: String = "",
    val originalImageUrl: String? = null,
    val imagePositionX: Float = 50f,
    val imagePositionY: Float = 0f,
    val imageZoom: Float = 1.0f,
    val instagramUrl: String? = null,
    val twitterUrl: String? = null,
    val onlyFansUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "studios")
data class StudioEntity(
    @PrimaryKey val id: String,
    val stashDbId: String? = null,
    val name: String,
    val imageUrl: String? = null,
    val logoUrl: String? = null,
    val logoBgColor: String? = null,
    val originalImageUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val currentTheme: String = "Dark",
    val accentColorHex: String = "tokyo_night", // Tokyo Night default
    val torboxApiKey: String = "",
    val realDebridApiKey: String = TestDefaults.RD_KEY,
    val stashDbApiKey: String = TestDefaults.STASHDB_KEY,
    val geminiApiKey: String = "",
    val blurCovers: Boolean = false,
    val betaTestPrivacy: Boolean = false,
    val defaultAspectRatio: String = "16:9",
    val showManagementCards: Boolean = true,
    val enableVideoPlayerGestures: Boolean = true,
    val transitionStyle: Int = 0,
    val lastSyncTime: Long = 0L,
    val defaultKeysSeeded: Boolean = false,
    val debridOrder: String = "AUTO", // "AUTO", "REAL_DEBRID_FIRST", "TORBOX_FIRST"
    val enableStudioFilter: Boolean = true,
    val blockMalePerformers: Boolean = true,
    val blockedStudioNames: List<String> = emptyList(),
    val blockedStudioIds: List<String> = emptyList(),
    val navBarHeightDp: Int = 70,
    val navBarTransparency: Float = 0.65f,
    val navBarBlurDp: Int = 24,
    val navBarActiveTabBlurDp: Int = 16,
    val navBarActiveTabIntensity: Float = 0.6f,
    val isPasscodeEnabled: Boolean = false,
    val passcodeHash: String = ""
)
