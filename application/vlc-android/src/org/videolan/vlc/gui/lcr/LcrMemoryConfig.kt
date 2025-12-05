package org.videolan.vlc.gui.lcr

import android.util.Log

/**
 * LCR 内存配置
 * 硬上限 200MB，在此范围内最大化利用
 */
object LcrMemoryConfig {
    private const val TAG = "LCR/MemoryConfig"
    
    // 硬上限 200MB
    const val MAX_TOTAL_MEMORY_BYTES = 200L * 1024 * 1024
    
    // 3个声道
    const val CHANNEL_COUNT = 3
    
    // 每声道可用内存
    const val MAX_MEMORY_PER_CHANNEL_BYTES = MAX_TOTAL_MEMORY_BYTES / CHANNEL_COUNT
    
    // 采样率
    const val SAMPLE_RATE = 44100
    
    // 每个采样点字节数 (Float = 4 bytes)
    const val BYTES_PER_SAMPLE = 4
    
    // 每声道最大采样点数
    val MAX_SAMPLES_PER_CHANNEL = (MAX_MEMORY_PER_CHANNEL_BYTES / BYTES_PER_SAMPLE).toInt()
    
    // 每声道最大缓冲时长（秒）
    val MAX_BUFFER_DURATION_SEC = MAX_SAMPLES_PER_CHANNEL.toFloat() / SAMPLE_RATE
    
    // 预读提前量（秒）- 当剩余缓冲小于此值时开始预读
    const val PRELOAD_THRESHOLD_SEC = 30f
    
    // 每次预读块大小（秒）
    const val PRELOAD_CHUNK_SEC = 10f
    
    init {
        Log.i(TAG, "=== LCR 内存配置 ===")
        Log.i(TAG, "总内存上限: ${MAX_TOTAL_MEMORY_BYTES / 1024 / 1024}MB")
        Log.i(TAG, "每声道内存: ${MAX_MEMORY_PER_CHANNEL_BYTES / 1024 / 1024}MB")
        Log.i(TAG, "每声道最大采样点: $MAX_SAMPLES_PER_CHANNEL")
        Log.i(TAG, "每声道最大缓冲时长: ${String.format("%.1f", MAX_BUFFER_DURATION_SEC)}秒")
        Log.i(TAG, "====================")
    }
    
    /**
     * 计算指定时长需要的内存
     */
    fun calculateMemoryForDuration(durationSec: Float): Long {
        return (durationSec * SAMPLE_RATE * BYTES_PER_SAMPLE).toLong()
    }
    
    /**
     * 判断是否可以全量加载
     */
    fun canLoadFully(durationSec: Float): Boolean {
        return durationSec <= MAX_BUFFER_DURATION_SEC
    }
    
    /**
     * 获取实际应该分配的缓冲区大小（采样点数）
     */
    fun getActualBufferSize(durationSec: Float): Int {
        val requiredSamples = (durationSec * SAMPLE_RATE).toInt()
        return minOf(requiredSamples, MAX_SAMPLES_PER_CHANNEL)
    }
}

