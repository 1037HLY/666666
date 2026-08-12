package com.geosurvey.toolbox.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 定位数据DAO
 */
@Dao
interface LocationDao {
    @Insert
    suspend fun insertLocation(location: LocationEntity): Long

    @Insert
    suspend fun insertTrackPoint(track: TrackEntity): Long

    @Query("SELECT * FROM locations ORDER BY time DESC LIMIT 1")
    suspend fun getLatestLocation(): LocationEntity?

    @Query("SELECT * FROM locations ORDER BY time DESC LIMIT 1")
    fun getLatestLocationFlow(): Flow<LocationEntity?>

    @Query("SELECT * FROM tracks ORDER BY time DESC LIMIT 100")
    suspend fun getRecentTracks(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE time BETWEEN :startTime AND :endTime ORDER BY time ASC")
    suspend fun getTracksBetween(startTime: Long, endTime: Long): List<TrackEntity>

    @Query("DELETE FROM tracks WHERE time < :beforeTime")
    suspend fun deleteOldTracks(beforeTime: Long)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun getTrackCount(): Int
}
