package com.handykit.app.core.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicle ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicle ORDER BY createdAt ASC, id ASC")
    suspend fun all(): List<Vehicle>

    @Query("SELECT * FROM vehicle WHERE id = :id")
    suspend fun get(id: Long): Vehicle?

    @Insert
    suspend fun insert(v: Vehicle): Long

    @Update
    suspend fun update(v: Vehicle)

    @Query("DELETE FROM vehicle WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface FuelDao {
    @Query("SELECT * FROM fuel_record WHERE vehicleId = :vehicleId AND invalidSegment = 0 ORDER BY date ASC, id ASC")
    fun observeValid(vehicleId: Long): Flow<List<FuelRecord>>

    @Query("SELECT * FROM fuel_record WHERE vehicleId = :vehicleId ORDER BY date ASC, id ASC")
    fun observeAll(vehicleId: Long): Flow<List<FuelRecord>>

    @Query("SELECT * FROM fuel_record WHERE vehicleId = :vehicleId ORDER BY date ASC, id ASC")
    suspend fun byVehicle(vehicleId: Long): List<FuelRecord>

    @Query("SELECT * FROM fuel_record ORDER BY vehicleId ASC, date ASC, id ASC")
    suspend fun all(): List<FuelRecord>

    @Query("SELECT * FROM fuel_record WHERE id = :id")
    suspend fun get(id: Long): FuelRecord?

    @Query("SELECT COUNT(*) FROM fuel_record WHERE vehicleId = :vehicleId AND date = :date AND odometer = :odometer")
    suspend fun countDuplicate(vehicleId: Long, date: java.time.LocalDate, odometer: Int): Int

    @Insert
    suspend fun insert(r: FuelRecord): Long

    @Insert
    suspend fun insertAll(rs: List<FuelRecord>)

    @Update
    suspend fun update(r: FuelRecord)

    @Query("DELETE FROM fuel_record WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ScaleDao {
    @Query("SELECT * FROM scale_member ORDER BY name ASC")
    fun observeMembers(): Flow<List<ScaleMember>>

    @Query("SELECT * FROM scale_member")
    suspend fun members(): List<ScaleMember>

    @Upsert
    suspend fun upsertMember(m: ScaleMember)

    @Delete
    suspend fun deleteMember(m: ScaleMember)

    @Query("SELECT * FROM measurement ORDER BY measuredAtEpochMs DESC")
    fun observeMeasurements(): Flow<List<Measurement>>

    @Query("SELECT * FROM measurement ORDER BY measuredAtEpochMs DESC")
    suspend fun measurements(): List<Measurement>

    @Query("SELECT * FROM measurement WHERE memberId = :memberId ORDER BY measuredAtEpochMs DESC")
    fun measurementsFor(memberId: String): Flow<List<Measurement>>

    @Upsert
    suspend fun upsertMeasurement(m: Measurement)

    @Query("DELETE FROM measurement WHERE id = :id")
    suspend fun deleteMeasurement(id: String)

    @Query("DELETE FROM measurement WHERE memberId = :memberId")
    suspend fun deleteByMember(memberId: String)
}

@Dao
interface AiDao {
    @Query("SELECT * FROM ai_session ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<AiSession>>

    @Query("SELECT * FROM ai_session ORDER BY updatedAt DESC")
    suspend fun sessions(): List<AiSession>

    @Query("SELECT * FROM ai_session WHERE id = :id")
    suspend fun session(id: String): AiSession?

    @Upsert
    suspend fun upsertSession(s: AiSession)

    @Query("DELETE FROM ai_session WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("SELECT * FROM ai_message WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(sessionId: String): Flow<List<AiMessage>>

    @Query("SELECT * FROM ai_message WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun messages(sessionId: String): List<AiMessage>

    @Upsert
    suspend fun upsertMessage(m: AiMessage)

    @Query("DELETE FROM ai_message WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("SELECT * FROM ai_role ORDER BY createdAt ASC")
    fun observeRoles(): Flow<List<AiRole>>

    @Query("SELECT * FROM ai_role")
    suspend fun roles(): List<AiRole>

    @Upsert
    suspend fun upsertRole(r: AiRole)

    @Query("DELETE FROM ai_role WHERE id = :id")
    suspend fun deleteRole(id: String)

    @Query("SELECT * FROM ai_provider ORDER BY createdAt ASC")
    fun observeProviders(): Flow<List<AiProvider>>

    @Query("SELECT * FROM ai_provider")
    suspend fun providers(): List<AiProvider>

    @Query("SELECT * FROM ai_provider WHERE id = :id")
    suspend fun provider(id: String): AiProvider?

    @Upsert
    suspend fun upsertProvider(p: AiProvider)

    @Query("DELETE FROM ai_provider WHERE id = :id")
    suspend fun deleteProvider(id: String)

    @Upsert
    suspend fun upsertAttachment(a: AiAttachment)

    @Query("SELECT * FROM ai_attachment WHERE id IN (:ids)")
    suspend fun attachments(ids: List<String>): List<AiAttachment>

    @Query("SELECT * FROM ai_attachment WHERE sessionId = :sessionId")
    suspend fun attachmentsBySession(sessionId: String): List<AiAttachment>

    @Query("DELETE FROM ai_attachment WHERE sessionId = :sessionId")
    suspend fun deleteAttachments(sessionId: String)
}

@Dao
interface MusicDao {
    @Query("SELECT * FROM music_track ORDER BY addedAt DESC")
    fun observeTracks(): Flow<List<MusicTrack>>

    @Query("SELECT * FROM music_track")
    suspend fun tracks(): List<MusicTrack>

    @Query("SELECT * FROM music_track WHERE id = :id")
    suspend fun track(id: String): MusicTrack?

    @Query("SELECT * FROM music_track WHERE bvid = :bvid AND cid = :cid LIMIT 1")
    suspend fun findBili(bvid: String, cid: String): MusicTrack?

    @Upsert
    suspend fun upsertTrack(t: MusicTrack)

    @Query("DELETE FROM music_track WHERE id = :id")
    suspend fun deleteTrack(id: String)

    @Query("SELECT * FROM music_playlist ORDER BY createdAt ASC")
    fun observePlaylists(): Flow<List<MusicPlaylist>>

    @Upsert
    suspend fun upsertPlaylist(p: MusicPlaylist)

    @Query("DELETE FROM music_playlist WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("SELECT * FROM music_playlist_track WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun playlistTracks(playlistId: String): List<PlaylistTrack>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPlaylistTrack(pt: PlaylistTrack)

    @Query("DELETE FROM music_playlist_track WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removePlaylistTrack(playlistId: String, trackId: String)

    @Query("DELETE FROM music_playlist_track WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: String)

    @Upsert
    suspend fun upsertLyric(l: MusicLyric)

    @Query("SELECT * FROM music_lyric WHERE trackId = :trackId")
    suspend fun lyric(trackId: String): MusicLyric?
}
