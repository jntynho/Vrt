package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkDao {
    @Query("SELECT * FROM links ORDER BY createdAt DESC")
    fun getAllLinks(): Flow<List<LinkEntity>>

    @Query("SELECT * FROM links WHERE id = :id")
    suspend fun getLinkById(id: String): LinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLink(link: LinkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<LinkEntity>)

    @Update
    suspend fun updateLink(link: LinkEntity)

    @Query("DELETE FROM links WHERE id = :id")
    suspend fun deleteLinkById(id: String)

    @Query("DELETE FROM links")
    suspend fun deleteAllLinks()
}

@Dao
interface ActorDao {
    @Query("SELECT * FROM actors ORDER BY name ASC")
    fun getAllActors(): Flow<List<ActorEntity>>

    @Query("SELECT * FROM actors WHERE id = :id")
    suspend fun getActorById(id: String): ActorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActor(actor: ActorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActors(actors: List<ActorEntity>)

    @Update
    suspend fun updateActor(actor: ActorEntity)

    @Query("DELETE FROM actors WHERE id = :id")
    suspend fun deleteActorById(id: String)

    @Query("DELETE FROM actors")
    suspend fun deleteAllActors()
}

@Dao
interface StudioDao {
    @Query("SELECT * FROM studios ORDER BY name ASC")
    fun getAllStudios(): Flow<List<StudioEntity>>

    @Query("SELECT * FROM studios WHERE id = :id")
    suspend fun getStudioById(id: String): StudioEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudio(studio: StudioEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudios(studios: List<StudioEntity>)

    @Update
    suspend fun updateStudio(studio: StudioEntity)

    @Query("DELETE FROM studios WHERE id = :id")
    suspend fun deleteStudioById(id: String)

    @Query("DELETE FROM studios")
    suspend fun deleteAllStudios()
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun getSettings(): Flow<SettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettingsOnce(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: SettingsEntity)
}
