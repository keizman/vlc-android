/**
 * LCR 三声道播放器 - 声道信息数据类
 * 对标 Python config.py 的 ChannelInfo 和 PlayMode
 */

package org.videolan.vlc.gui.lcr

import android.net.Uri

/**
 * 播放模式枚举
 * 对标 Python PlayMode
 */
enum class LcrPlayMode(val displayName: String) {
    SEPARATE("Separate"),      // LR立体声 + Center独立流（三声道效果）
    LEFT_ONLY("Left Only"),    // 仅左声道
    RIGHT_ONLY("Right Only"),  // 仅右声道
    CENTER_ONLY("Center Only") // 仅中心声道
}

/**
 * 声道类型
 */
enum class LcrChannelType {
    LEFT,
    CENTER,
    RIGHT
}

/**
 * 声道信息 - 代表一个独立的音频源
 * 对标 Python ChannelInfo
 * 
 * 支持两种模式：
 * 1. 全量加载（小文件）：数据在 pcmData 中
 * 2. 流式加载（大文件）：数据在 streamingSource 中
 */
class LcrChannelInfo {
    // 旧模式：直接 PCM 数据（兼容保留，小文件用）
    var pcmData: FloatArray? = null
    
    // 新模式：流式音频源（大文件用）
    var streamingSource: StreamingAudioSource? = null
    
    var duration: Float = 0f             // 时长（秒）
    var volume: Float = 1.0f             // 音量 (0.0-1.0)
    var proximity: Float = 0.5f          // 距离感 (0.0=远, 1.0=近)
    var uri: Uri? = null                 // 文件URI
    var fileName: String? = null         // 文件名
    var inverted: Boolean = false        // 是否反转声道
    var sampleRate: Int = 44100          // 采样率
    
    /**
     * 是否使用流式模式
     */
    val isStreaming: Boolean
        get() = streamingSource != null
    
    /**
     * 获取数据块（统一接口，自动选择数据源）
     * @param positionSample 起始采样点
     * @param size 需要的采样点数
     */
    fun getChunk(positionSample: Long, size: Int): FloatArray {
        // 优先使用流式源
        streamingSource?.let {
            return it.getChunk(positionSample, size)
        }
        
        // 回退到直接 PCM 数据
        val data = pcmData ?: return FloatArray(size)
        val pos = positionSample.toInt()
        if (pos >= data.size) return FloatArray(size)
        
        val result = FloatArray(size)
        val copyLen = minOf(size, data.size - pos)
        System.arraycopy(data, pos, result, 0, copyLen)
        return result
    }
    
    /**
     * Seek 到指定位置（流式模式需要预加载）
     */
    suspend fun seekTo(positionSample: Long) {
        streamingSource?.seekTo(positionSample)
    }
    
    fun clear() {
        pcmData = null
        streamingSource?.release()
        streamingSource = null
        duration = 0f
        uri = null
        fileName = null
        inverted = false
    }
    
    fun hasData(): Boolean {
        return streamingSource != null || (pcmData != null && pcmData!!.isNotEmpty())
    }
    
    /**
     * 获取当前内存使用（字节）
     */
    fun getMemoryUsage(): Long {
        streamingSource?.let {
            return it.getMemoryUsage()
        }
        return (pcmData?.size ?: 0) * 4L
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LcrChannelInfo

        if (pcmData != null) {
            if (other.pcmData == null) return false
            if (!pcmData.contentEquals(other.pcmData)) return false
        } else if (other.pcmData != null) return false
        if (duration != other.duration) return false
        if (volume != other.volume) return false
        if (uri != other.uri) return false
        if (inverted != other.inverted) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pcmData?.contentHashCode() ?: 0
        result = 31 * result + duration.hashCode()
        result = 31 * result + volume.hashCode()
        result = 31 * result + (uri?.hashCode() ?: 0)
        result = 31 * result + inverted.hashCode()
        return result
    }
}

/**
 * 播放状态
 */
data class LcrPlaybackState(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val isSeeking: Boolean = false,      // 是否正在加载缓冲区
    val position: Long = 0,              // 当前位置（采样点）
    val positionSeconds: Float = 0f,     // 当前位置（秒）
    val durationSeconds: Float = 0f,     // 总时长（秒）
    val playMode: LcrPlayMode = LcrPlayMode.SEPARATE,
    val isSwitched: Boolean = false,     // 是否交换了左右声道（左右耳声道互换）
    val memoryUsageMB: Float = 0f,       // 内存使用（MB）
    val playbackSpeed: Float = 1.0f,     // 播放速度 (1.0 - 3.0)
    val isLooping: Boolean = true        // 是否循环播放
)
