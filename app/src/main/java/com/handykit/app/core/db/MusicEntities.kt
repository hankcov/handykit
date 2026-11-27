package com.handykit.app.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 曲目。source=local 或 bili。
 * bili 用 bvid+cid 去重；本地用 contentHash 去重。
 * filePath 为空且 source=bili 表示需要重新缓存。
 */
@Entity(tableName = "music_track", indices = [Index("bvid"), Index("contentHash")])
data class MusicTrack(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val source: String,
    /** B 站视频 BV 号，本地为空 */
    val bvid: String = "",
    /** 分 P 标识 */
    val cid: String = "",
    val sourceUrl: String = "",
    /** 本地音频绝对路径；空表示需重新缓存 */
    val filePath: String = "",
    /** 封面本地路径 */
    val coverPath: String = "",
    val contentHash: String = "",
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    /** ready / queued / parsing / downloading / verifying / failed / missing */
    val status: String = "ready",
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "music_playlist_track",
    foreignKeys = [ForeignKey(
        entity = MusicTrack::class,
        parentColumns = ["id"],
        childColumns = ["trackId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("playlistId"), Index("trackId")],
)
data class PlaylistTrack(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: String,
    val trackId: String,
    val position: Int,
)

@Entity(tableName = "music_playlist")
data class MusicPlaylist(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 歌词。格式 lrc / txt，内容 UTF-8。 */
@Entity(tableName = "music_lyric")
data class MusicLyric(
    @PrimaryKey val trackId: String,
    val format: String,
    val content: String,
    val source: String = "manual",
)
