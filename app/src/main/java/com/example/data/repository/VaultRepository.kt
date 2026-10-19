package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.entity.*
import kotlinx.coroutines.flow.Flow

class GoonyRepository(private val db: AppDatabase) {

    // Links / Scenes
    val allLinks: Flow<List<LinkEntity>> = db.linkDao().getAllLinks()
    suspend fun getLinkById(id: String): LinkEntity? = db.linkDao().getLinkById(id)
    suspend fun insertLink(link: LinkEntity) = db.linkDao().insertLink(link)
    suspend fun insertLinks(links: List<LinkEntity>) = db.linkDao().insertLinks(links)
    suspend fun updateLink(link: LinkEntity) = db.linkDao().updateLink(link)
    suspend fun deleteLinkById(id: String) = db.linkDao().deleteLinkById(id)
    suspend fun deleteAllLinks() = db.linkDao().deleteAllLinks()

    // Actors / Performers
    val allActors: Flow<List<ActorEntity>> = db.actorDao().getAllActors()
    suspend fun getActorById(id: String): ActorEntity? = db.actorDao().getActorById(id)
    suspend fun insertActor(actor: ActorEntity) = db.actorDao().insertActor(actor)
    suspend fun insertActors(actors: List<ActorEntity>) = db.actorDao().insertActors(actors)
    suspend fun updateActor(actor: ActorEntity) = db.actorDao().updateActor(actor)
    suspend fun deleteActorById(id: String) = db.actorDao().deleteActorById(id)
    suspend fun deleteAllActors() = db.actorDao().deleteAllActors()

    // Studios
    val allStudios: Flow<List<StudioEntity>> = db.studioDao().getAllStudios()
    suspend fun getStudioById(id: String): StudioEntity? = db.studioDao().getStudioById(id)
    suspend fun insertStudio(studio: StudioEntity) = db.studioDao().insertStudio(studio)
    suspend fun insertStudios(studios: List<StudioEntity>) = db.studioDao().insertStudios(studios)
    suspend fun updateStudio(studio: StudioEntity) = db.studioDao().updateStudio(studio)
    suspend fun deleteStudioById(id: String) = db.studioDao().deleteStudioById(id)
    suspend fun deleteAllStudios() = db.studioDao().deleteAllStudios()

    // Settings
    val settings: Flow<SettingsEntity?> = db.settingsDao().getSettings()
    suspend fun getSettingsOnce(): SettingsEntity = db.settingsDao().getSettingsOnce() ?: SettingsEntity()
    suspend fun updateSettings(settings: SettingsEntity) = db.settingsDao().insertSettings(settings)
}

// Backward-compatible alias for existing components
typealias VaultRepository = GoonyRepository
