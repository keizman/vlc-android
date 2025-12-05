/**
 * LCR 三声道播放器 - 双流播放器核心
 * 完全对标 Python dual_stream_player.py
 * 
 * 架构：
 * - 流1 (Stereo Stream): 输出 (L, R) 立体声
 * - 流2 (Center Stream): 独立输出 (C, C)
 * 
 * 两个流同时播放，由音频系统混合，实现三声道效果：
 * - 左耳听到：L（来自流1）+ C（来自流2，由系统混合）
 * - 右耳听到：R（来自流1）+ C（来自流2，由系统混合）
 * 
 * 关键：C 不与 L/R 在代码中相加，而是作为独立的声音层播放
 * 
 * 内存管理：
 * - 硬上限 200MB（所有声道总计）
 * - 小文件（<6分钟）：全量加载
 * - 大文件（>6分钟）：流式加载，滑动窗口缓冲
 */

package org.videolan.vlc.gui.lcr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

private const val TAG = "LCR-Player"

/**
 * 双流音频播放器 - 实现真正的三声道效果
 * 
 * 流1: Stereo (L, R) - 左耳听L，右耳听R
 * 流2: Center (C, C) - 独立的Center层
 * 
 * 对标 Python DualStreamPlayer
 */
class LcrDualStreamPlayer(private val context: Context) {
    
    companion object {
        const val SAMPLE_RATE = 44100
        const val CHUNK_SIZE_MS = 50  // 50ms chunk
    }
    
    private val chunkSize = SAMPLE_RATE * CHUNK_SIZE_MS / 1000
    
    // 声道信息（对标 Python 的 left_info, center_info, right_info）
    val leftInfo = LcrChannelInfo()
    val centerInfo = LcrChannelInfo()
    val rightInfo = LcrChannelInfo()
    
    // 播放状态
    private var _isPlaying = AtomicBoolean(false)
    private var _isPaused = AtomicBoolean(false)
    
    // 当前位置（采样点）
    @Volatile
    private var currentPosition: Long = 0
    private val positionLock = ReentrantLock()
    
    // 播放模式
    @Volatile
    var playMode: LcrPlayMode = LcrPlayMode.SEPARATE
        private set
    
    // Switch 状态（交换左右声道输出，左右耳声道互换）
    @Volatile
    var isSwitched: Boolean = false
        private set
    
    // 播放速度 (1.0 - 3.0)
    @Volatile
    var playbackSpeed: Float = 1.0f
        private set
    
    // 循环播放（默认开启）
    @Volatile
    var isLooping: Boolean = true
        private set
    
    // 双流（对标 Python 的 stream_stereo, stream_center）
    private var audioTrackStereo: AudioTrack? = null
    private var audioTrackCenter: AudioTrack? = null
    
    // 线程同步（对标 Python 的 stop_event, stereo_stopped, center_stopped）
    private val stopEvent = AtomicBoolean(false)
    private val stereoStopped = AtomicBoolean(true)
    private val centerStopped = AtomicBoolean(true)
    
    // 播放线程（对标 Python 的 thread_stereo, thread_center）
    private var threadStereo: Thread? = null
    private var threadCenter: Thread? = null
    
    // 状态流
    private val _playbackState = MutableStateFlow(LcrPlaybackState())
    val playbackState: StateFlow<LcrPlaybackState> = _playbackState.asStateFlow()
    
    // 播放结束回调
    var onPlaybackEnd: (() -> Unit)? = null
    
    // 加载进度回调
    var onLoadProgress: ((channel: LcrChannelType, loadedSec: Float, totalSec: Float) -> Unit)? = null
    
    init {
        Log.i(TAG, "============================================")
        Log.i(TAG, "LCR 双流播放器初始化")
        Log.i(TAG, "  采样率: $SAMPLE_RATE Hz")
        Log.i(TAG, "  Chunk大小: $CHUNK_SIZE_MS ms ($chunkSize samples)")
        Log.i(TAG, "  架构: 双AudioTrack (Stereo + Center)")
        Log.i(TAG, "  内存上限: ${LcrMemoryConfig.MAX_TOTAL_MEMORY_BYTES / 1024 / 1024}MB")
        Log.i(TAG, "  每声道缓冲上限: ${String.format("%.1f", LcrMemoryConfig.MAX_BUFFER_DURATION_SEC)}s")
        Log.i(TAG, "============================================")
    }
    
    /**
     * 加载音频文件到指定声道（流式加载）
     * 对标 Python load_audio()
     */
    suspend fun loadAudio(uri: Uri, channel: LcrChannelType, fileName: String) = withContext(Dispatchers.IO) {
        Log.i(TAG, "--------------------------------------------")
        Log.i(TAG, "加载音频到声道: $channel")
        Log.i(TAG, "  文件名: $fileName")
        Log.i(TAG, "  URI: $uri")
        
        val channelInfo = when (channel) {
            LcrChannelType.LEFT -> leftInfo
            LcrChannelType.CENTER -> centerInfo
            LcrChannelType.RIGHT -> rightInfo
        }
        
        val inverted = when (channel) {
            LcrChannelType.LEFT -> leftInfo.inverted
            LcrChannelType.RIGHT -> rightInfo.inverted
            else -> false
        }
        
        Log.d(TAG, "  反转模式: $inverted")
        
        // 释放旧的流式源
        channelInfo.streamingSource?.release()
        channelInfo.pcmData = null
        
        // 创建新的流式音频源
        val source = StreamingAudioSource(context, channel, inverted)
        
        val success = source.initialize(uri)
        if (!success) {
            Log.e(TAG, "加载失败: $channel")
            throw IllegalStateException("无法加载音频文件")
        }
        
        // 更新声道信息
        channelInfo.streamingSource = source
        channelInfo.duration = source.totalDurationSec
        channelInfo.sampleRate = source.sampleRate
        channelInfo.uri = uri
        channelInfo.fileName = fileName
        
        val memoryMB = source.getMemoryUsage() / 1024f / 1024f
        val isFullLoad = LcrMemoryConfig.canLoadFully(source.totalDurationSec)
        
        Log.i(TAG, "加载完成: $channel")
        Log.i(TAG, "  时长: ${String.format("%.2f", source.totalDurationSec)}s")
        Log.i(TAG, "  加载模式: ${if (isFullLoad) "全量加载" else "流式加载"}")
        Log.i(TAG, "  缓冲内存: ${String.format("%.2f", memoryMB)} MB")
        Log.i(TAG, "--------------------------------------------")
        
        // 打印当前所有声道状态
        logChannelStatus()
        logMemoryStatus()
        
        updateState()
        
        // 如果正在播放，需要重启以应用变更
        if (_isPlaying.get()) {
            val currentPos = positionLock.withLock { currentPosition }
            Log.d(TAG, "正在播放中，新声道需要 seek 到当前位置: ${currentPos / SAMPLE_RATE.toFloat()}s")
            
            // 确保新加载的声道缓冲区包含当前播放位置
            channelInfo.streamingSource?.let { source ->
                if (!source.isPositionInBuffer(currentPos)) {
                    Log.d(TAG, "新声道缓冲区不包含当前位置，执行 seek")
                    kotlinx.coroutines.runBlocking {
                        channelInfo.seekTo(currentPos)
                    }
                }
            }
            
            restartIfPlaying()
        }
    }
    
    /**
     * 打印所有声道状态
     */
    private fun logChannelStatus() {
        Log.d(TAG, "当前声道状态:")
        Log.d(TAG, "  Left:   ${if (leftInfo.hasData()) "✓ ${leftInfo.fileName} (${String.format("%.1f", leftInfo.duration)}s)" else "×"}")
        Log.d(TAG, "  Center: ${if (centerInfo.hasData()) "✓ ${centerInfo.fileName} (${String.format("%.1f", centerInfo.duration)}s)" else "×"}")
        Log.d(TAG, "  Right:  ${if (rightInfo.hasData()) "✓ ${rightInfo.fileName} (${String.format("%.1f", rightInfo.duration)}s)" else "×"}")
    }
    
    /**
     * 打印内存使用状态
     */
    private fun logMemoryStatus() {
        val leftMem = leftInfo.getMemoryUsage() / 1024f / 1024f
        val centerMem = centerInfo.getMemoryUsage() / 1024f / 1024f
        val rightMem = rightInfo.getMemoryUsage() / 1024f / 1024f
        val totalMem = leftMem + centerMem + rightMem
        val maxMem = LcrMemoryConfig.MAX_TOTAL_MEMORY_BYTES / 1024f / 1024f
        
        Log.i(TAG, "内存使用:")
        Log.i(TAG, "  Left:   ${String.format("%.1f", leftMem)} MB")
        Log.i(TAG, "  Center: ${String.format("%.1f", centerMem)} MB")
        Log.i(TAG, "  Right:  ${String.format("%.1f", rightMem)} MB")
        Log.i(TAG, "  总计:   ${String.format("%.1f", totalMem)} / ${String.format("%.0f", maxMem)} MB")
    }
    
    /**
     * 获取总内存使用（MB）
     */
    fun getTotalMemoryUsageMB(): Float {
        val total = leftInfo.getMemoryUsage() + centerInfo.getMemoryUsage() + rightInfo.getMemoryUsage()
        return total / 1024f / 1024f
    }
    
    /**
     * 清除指定声道
     * 对标 Python clear_audio()
     */
    fun clearAudio(channel: LcrChannelType) {
        Log.i(TAG, "清除声道: $channel")
        
        when (channel) {
            LcrChannelType.LEFT -> leftInfo.clear()
            LcrChannelType.CENTER -> centerInfo.clear()
            LcrChannelType.RIGHT -> rightInfo.clear()
        }
        
        logMemoryStatus()
        updateState()
        
        if (_isPlaying.get()) {
            restartIfPlaying()
        }
    }
    
    /**
     * 切换反转状态
     * 对标 Python toggle_invert()
     */
    suspend fun toggleInvert(channel: LcrChannelType): Boolean {
        val info = when (channel) {
            LcrChannelType.LEFT -> leftInfo
            LcrChannelType.RIGHT -> rightInfo
            else -> throw IllegalArgumentException("只有 LEFT/RIGHT 支持反转")
        }
        
        info.inverted = !info.inverted
        Log.i(TAG, "切换反转: $channel, inverted=${info.inverted}")
        
        // 如果有文件，需要重新加载
        info.uri?.let { uri ->
            info.fileName?.let { fileName ->
                loadAudio(uri, channel, fileName)
            }
        }
        
        return info.inverted
    }
    
    /**
     * 切换 Switch 状态（交换左右声道输出）
     * 对标 Python toggle_switch()
     */
    fun toggleSwitch(): Boolean {
        isSwitched = !isSwitched
        Log.i(TAG, "切换Switch: $isSwitched")
        updateState()
        return isSwitched
    }
    
    /**
     * 设置播放模式
     * 对标 Python set_play_mode()
     */
    fun setPlayMode(mode: LcrPlayMode) {
        val oldMode = playMode
        playMode = mode
        Log.i(TAG, "切换模式: $oldMode -> $mode")
        
        updateState()
        
        if (_isPlaying.get()) {
            restartIfPlaying()
        }
    }
    
    /**
     * 设置音量
     * 对标 Python set_volume()
     */
    fun setVolume(channel: LcrChannelType, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        when (channel) {
            LcrChannelType.LEFT -> leftInfo.volume = v
            LcrChannelType.CENTER -> centerInfo.volume = v
            LcrChannelType.RIGHT -> rightInfo.volume = v
        }
    }
    
    /**
     * 设置播放速度 (0.5 - 2.0)
     * 注意：Android AudioTrack 通常只支持 0.5-2.0 范围
     * 
     * 重要：由于在播放中修改 PlaybackParams 可能导致 AudioTrack 崩溃，
     * 我们需要重启播放来应用新速度
     */
    fun setSpeed(speed: Float) {
        val newSpeed = speed.coerceIn(0.5f, 2.0f)
        if (playbackSpeed != newSpeed) {
            playbackSpeed = newSpeed
            Log.i(TAG, "设置播放速度: ${String.format("%.2f", newSpeed)}x")
            
            // 如果正在播放，需要重启以应用新速度
            // 不能在播放中直接修改 PlaybackParams，会导致 buffer 状态不一致崩溃
            if (_isPlaying.get() && !_isPaused.get()) {
                Log.d(TAG, "正在播放中，重启以应用新速度")
                restartIfPlaying()
            }
            
            updateState()
        }
    }
    
    /**
     * 安全地应用播放速度到 AudioTrack
     * 只在 AudioTrack 创建后、play() 之后立即调用
     * 不要在播放循环中调用！
     */
    private fun applySpeedToTrack(track: AudioTrack?) {
        if (track == null) return
        if (playbackSpeed == 1.0f) return  // 1.0x 不需要设置
        
        // 方法1：尝试使用 PlaybackParams（推荐，保持音调）
        try {
            val params = android.media.PlaybackParams()
                .allowDefaults()
                .setSpeed(playbackSpeed)
                .setPitch(1.0f)  // 保持音调不变
                .setAudioFallbackMode(android.media.PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT)
            track.playbackParams = params
            Log.d(TAG, "播放速度已应用 (PlaybackParams): ${playbackSpeed}x")
            return
        } catch (e: Exception) {
            Log.w(TAG, "PlaybackParams 失败: ${e.message}, 尝试备用方法")
        }
        
        // 方法2：使用 setPlaybackRate（改变采样率，会改变音调）
        try {
            val newRate = (SAMPLE_RATE * playbackSpeed).toInt()
            track.playbackRate = newRate
            Log.d(TAG, "播放速度已应用 (PlaybackRate): ${playbackSpeed}x -> ${newRate}Hz")
        } catch (e: Exception) {
            Log.e(TAG, "setPlaybackRate 也失败: ${e.message}")
        }
    }
    
    /**
     * 设置循环播放
     */
    fun setLooping(loop: Boolean) {
        isLooping = loop
        Log.i(TAG, "设置循环播放: $loop")
        updateState()
    }
    
    /**
     * 获取音量
     * 对标 Python get_volume()
     */
    fun getVolume(channel: LcrChannelType): Float {
        return when (channel) {
            LcrChannelType.LEFT -> leftInfo.volume
            LcrChannelType.CENTER -> centerInfo.volume
            LcrChannelType.RIGHT -> rightInfo.volume
        }
    }
    
    /**
     * 开始播放（双流同步）
     * 对标 Python play()
     */
    fun play() {
        if (_isPlaying.get()) {
            Log.w(TAG, "已在播放中，忽略play调用")
            return
        }
        
        // 检查缓冲区是否包含当前位置
        val currentPos = positionLock.withLock { currentPosition }
        val needsBufferReset = checkBufferPosition(currentPos)
        
        if (needsBufferReset) {
            Log.i(TAG, "缓冲区位置不匹配，先加载...")
            // 使用 seek 来确保缓冲区正确
            seek(currentPos / SAMPLE_RATE.toFloat())
            return
        }
        
        val playStereo = shouldPlayStereo()
        val playCenter = shouldPlayCenter()
        
        Log.i(TAG, "============================================")
        Log.i(TAG, "▶ 开始播放")
        Log.i(TAG, "--------------------------------------------")
        Log.i(TAG, "播放配置:")
        Log.i(TAG, "  模式: ${playMode.displayName}")
        Log.i(TAG, "  Switch: ${if (isSwitched) "已交换" else "正常"}")
        Log.i(TAG, "  起始位置: ${positionLock.withLock { currentPosition }} samples")
        Log.i(TAG, "--------------------------------------------")
        Log.i(TAG, "流状态:")
        Log.i(TAG, "  Stereo流 (L+R): ${if (playStereo) "✓ 将启动" else "× 不启动"}")
        Log.i(TAG, "  Center流 (C):   ${if (playCenter) "✓ 将启动" else "× 不启动"}")
        Log.i(TAG, "--------------------------------------------")
        Log.i(TAG, "音量:")
        Log.i(TAG, "  Left:   ${(leftInfo.volume * 100).toInt()}%")
        Log.i(TAG, "  Center: ${(centerInfo.volume * 100).toInt()}%")
        Log.i(TAG, "  Right:  ${(rightInfo.volume * 100).toInt()}%")
        Log.i(TAG, "============================================")
        
        _isPlaying.set(true)
        _isPaused.set(false)
        
        // 重置事件
        stopEvent.set(false)
        stereoStopped.set(false)
        centerStopped.set(false)
        
        // 如果不需要播放，直接设为已停止
        if (!playStereo) {
            stereoStopped.set(true)
            Log.d(TAG, "Stereo流标记为已停止（无需播放）")
        }
        if (!playCenter) {
            centerStopped.set(true)
            Log.d(TAG, "Center流标记为已停止（无需播放）")
        }
        
        // 启动双流线程
        Log.d(TAG, "启动播放线程...")
        threadStereo = thread(name = "LCR-StereoStream") { stereoLoop() }
        threadCenter = thread(name = "LCR-CenterStream") { centerLoop() }
        
        updateState()
    }
    
    /**
     * 暂停/恢复
     * 对标 Python pause()
     */
    fun pause() {
        if (_isPlaying.get()) {
            val newPaused = !_isPaused.get()
            _isPaused.set(newPaused)
            Log.i(TAG, if (newPaused) "暂停" else "继续")
            updateState()
        }
    }
    
    /**
     * 停止播放
     * 对标 Python stop()
     */
    fun stop() {
        if (!_isPlaying.get()) return
        
        Log.i(TAG, "停止播放")
        
        stopEvent.set(true)
        _isPlaying.set(false)
        _isPaused.set(false)
        
        // 等待线程结束
        threadStereo?.join(500)
        threadCenter?.join(500)
        
        // 关闭流
        closeStreams()
        
        positionLock.withLock { currentPosition = 0 }
        
        // 重置缓冲区到位置 0（异步，不阻塞）
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "重置缓冲区到位置 0...")
            val jobs = mutableListOf<Job>()
            if (leftInfo.hasData()) {
                jobs.add(launch { leftInfo.seekTo(0) })
            }
            if (centerInfo.hasData()) {
                jobs.add(launch { centerInfo.seekTo(0) })
            }
            if (rightInfo.hasData()) {
                jobs.add(launch { rightInfo.seekTo(0) })
            }
            jobs.forEach { it.join() }
            Log.d(TAG, "缓冲区已重置")
        }
        
        updateState()
    }
    
    // Seek 状态
    private var _isSeeking = AtomicBoolean(false)
    val isSeeking: Boolean get() = _isSeeking.get()
    
    // Seek 完成回调
    var onSeekComplete: (() -> Unit)? = null
    
    /**
     * 跳转到指定位置
     * 优化：快速启动，只需加载少量数据即可开始播放
     */
    fun seek(positionSeconds: Float) {
        val minDuration = getMinDuration()
        val clampedSeconds = positionSeconds.coerceIn(0f, minDuration)
        val newPosition = (clampedSeconds * SAMPLE_RATE).toLong()
        
        Log.i(TAG, "Seek: ${String.format("%.1f", clampedSeconds)}s / ${String.format("%.1f", minDuration)}s")
        
        val wasPlaying = _isPlaying.get() && !_isPaused.get()
        
        // 停止当前播放
        if (_isPlaying.get()) {
            stopEvent.set(true)
            threadStereo?.join(300)
            threadCenter?.join(300)
            _isPlaying.set(false)
        }
        
        positionLock.withLock {
            currentPosition = newPosition
        }
        
        // 异步快速加载（只加载少量数据即可开始播放）
        _isSeeking.set(true)
        updateState()
        
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Seek: 快速加载缓冲区...")
            val startTime = System.currentTimeMillis()
            
            // 并行加载所有声道（每个只加载5秒数据）
            val jobs = mutableListOf<Job>()
            if (leftInfo.hasData()) {
                jobs.add(launch { leftInfo.seekTo(newPosition) })
            }
            if (centerInfo.hasData()) {
                jobs.add(launch { centerInfo.seekTo(newPosition) })
            }
            if (rightInfo.hasData()) {
                jobs.add(launch { rightInfo.seekTo(newPosition) })
            }
            
            jobs.forEach { it.join() }
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Seek: 快速加载完成 ${elapsed}ms")
            
            _isSeeking.set(false)
            
            withContext(Dispatchers.Main) {
                updateState()
                onSeekComplete?.invoke()
                
                // 如果之前在播放，恢复
                if (wasPlaying) {
                    resumeAfterSeek()
                }
            }
        }
    }
    
    /**
     * 检查缓冲区是否包含指定位置
     * @return true 如果需要重新加载缓冲区
     */
    private fun checkBufferPosition(position: Long): Boolean {
        if (leftInfo.hasData() && leftInfo.streamingSource?.isPositionInBuffer(position) == false) {
            return true
        }
        if (centerInfo.hasData() && centerInfo.streamingSource?.isPositionInBuffer(position) == false) {
            return true
        }
        if (rightInfo.hasData() && rightInfo.streamingSource?.isPositionInBuffer(position) == false) {
            return true
        }
        return false
    }
    
    /**
     * Seek 后恢复播放
     */
    private fun resumeAfterSeek() {
        Log.d(TAG, "Seek 完成，恢复播放")
        stopEvent.set(false)
        stereoStopped.set(false)
        centerStopped.set(false)
        _isPlaying.set(true)
        _isPaused.set(false)
        
        if (!shouldPlayStereo()) stereoStopped.set(true)
        if (!shouldPlayCenter()) centerStopped.set(true)
        
        threadStereo = thread(name = "LCR-StereoStream") { stereoLoop() }
        threadCenter = thread(name = "LCR-CenterStream") { centerLoop() }
        
        updateState()
    }
    
    /**
     * 获取最小时长（用于进度控制）
     * 对标 Python get_min_duration()
     */
    fun getMinDuration(): Float {
        val durations = mutableListOf<Float>()
        if (leftInfo.hasData()) durations.add(leftInfo.duration)
        if (centerInfo.hasData()) durations.add(centerInfo.duration)
        if (rightInfo.hasData()) durations.add(rightInfo.duration)
        return durations.minOrNull() ?: 0f
    }
    
    /**
     * 释放资源
     * 对标 Python close()
     */
    fun close() {
        stop()
        closeStreams()
        
        // 释放流式源
        leftInfo.clear()
        centerInfo.clear()
        rightInfo.clear()
        
        Log.i(TAG, "播放器已关闭")
    }
    
    // ================= 私有方法 =================
    
    /**
     * 判断是否应该播放 Stereo 流
     * 对标 Python _should_play_stereo()
     */
    private fun shouldPlayStereo(): Boolean {
        val hasLR = leftInfo.hasData() || rightInfo.hasData()
        if (!hasLR) return false
        // Center Only 模式不播放 LR
        if (playMode == LcrPlayMode.CENTER_ONLY) return false
        return true
    }
    
    /**
     * 判断是否应该播放 Center 流
     * 对标 Python _should_play_center()
     */
    private fun shouldPlayCenter(): Boolean {
        if (!centerInfo.hasData()) return false
        // Left Only / Right Only 模式不播放 Center
        if (playMode == LcrPlayMode.LEFT_ONLY || playMode == LcrPlayMode.RIGHT_ONLY) return false
        return true
    }
    
    /**
     * 如果正在播放，重启以应用变更
     * 对标 Python _restart_if_playing()
     */
    private fun restartIfPlaying() {
        if (!_isPlaying.get()) return
        
        Log.i(TAG, "重启以应用变更")
        
        val currentPos = positionLock.withLock { currentPosition }
        val wasPaused = _isPaused.get()
        
        // 停止当前线程
        stopEvent.set(true)
        threadStereo?.join(300)
        threadCenter?.join(300)
        
        // 检查是否还有可播放的音频
        val hasAny = leftInfo.hasData() || centerInfo.hasData() || rightInfo.hasData()
        if (!hasAny) {
            Log.i(TAG, "没有可播放的音频，停止")
            _isPlaying.set(false)
            _isPaused.set(false)
            positionLock.withLock { currentPosition = 0 }
            updateState()
            return
        }
        
        // 重置事件
        stopEvent.set(false)
        stereoStopped.set(false)
        centerStopped.set(false)
        
        // 恢复状态
        positionLock.withLock { 
            currentPosition = minOf(currentPos, (getMinDuration() * SAMPLE_RATE).toLong())
        }
        _isPaused.set(wasPaused)
        
        // 根据当前模式和数据决定启动哪些流
        if (!shouldPlayStereo()) stereoStopped.set(true)
        if (!shouldPlayCenter()) centerStopped.set(true)
        
        // 重启线程
        threadStereo = thread(name = "StereoStream") { stereoLoop() }
        threadCenter = thread(name = "CenterStream") { centerLoop() }
        
        Log.i(TAG, "已重启")
    }
    
    /**
     * Stereo 流播放循环
     * 对标 Python _stereo_loop()
     * 
     * Separate 模式：输出 (L, R) - 左耳听 L，右耳听 R
     * Left Only 模式：输出 (L, 0) - 只有左耳
     * Right Only 模式：输出 (0, R) - 只有右耳
     */
    private fun stereoLoop() {
        Log.i(TAG, "[Stereo] ====== 线程启动 ======")
        try {
            if (!shouldPlayStereo()) {
                Log.d(TAG, "[Stereo] 不需要播放，退出线程")
                return
            }
            
            val minDuration = getMinDuration()
            if (minDuration == 0f) {
                Log.w(TAG, "[Stereo] 无有效时长，退出线程")
                return
            }
            
            val minLength = (minDuration * SAMPLE_RATE).toLong()
            var position = positionLock.withLock { currentPosition }
            
            Log.i(TAG, "[Stereo] 播放参数:")
            Log.i(TAG, "[Stereo]   起始位置: ${String.format("%.2f", position / SAMPLE_RATE.toFloat())}s")
            Log.i(TAG, "[Stereo]   总长度: ${String.format("%.2f", minDuration)}s ($minLength samples)")
            Log.i(TAG, "[Stereo]   模式: ${playMode.displayName}")
            Log.i(TAG, "[Stereo]   Switch: $isSwitched")
            
            Log.d(TAG, "[Stereo] 创建 AudioTrack...")
            audioTrackStereo = createAudioTrack()
            audioTrackStereo?.play()
            // 播放后立即应用速度设置
            if (playbackSpeed != 1.0f) {
                applySpeedToTrack(audioTrackStereo)
            }
            Log.d(TAG, "[Stereo] AudioTrack 已启动")
            
            var count = 0
            val startTime = System.currentTimeMillis()
            
            while (position < minLength && !stopEvent.get()) {
                if (_isPaused.get()) {
                    Thread.sleep(50)
                    continue
                }
                
                // 使用新的 getChunk 接口（支持流式）
                val leftChunk = leftInfo.getChunk(position, chunkSize)
                val rightChunk = rightInfo.getChunk(position, chunkSize)
                
                // 应用距离感处理
                applyProximity(leftChunk, leftInfo.proximity)
                applyProximity(rightChunk, rightInfo.proximity)
                
                // 应用音量
                applyVolume(leftChunk, leftInfo.volume)
                applyVolume(rightChunk, rightInfo.volume)
                
                // 根据模式决定输出
                val outputLeft: FloatArray
                val outputRight: FloatArray
                
                when (playMode) {
                    LcrPlayMode.LEFT_ONLY -> {
                        outputLeft = leftChunk
                        outputRight = FloatArray(chunkSize)  // 静音
                    }
                    LcrPlayMode.RIGHT_ONLY -> {
                        outputLeft = FloatArray(chunkSize)  // 静音
                        outputRight = rightChunk
                    }
                    else -> {
                        outputLeft = leftChunk
                        outputRight = rightChunk
                    }
                }
                
                // Switch：交换左右
                val (finalLeft, finalRight) = if (isSwitched) {
                    Pair(outputRight, outputLeft)
                } else {
                    Pair(outputLeft, outputRight)
                }
                
                // 限幅
                clipArray(finalLeft)
                clipArray(finalRight)
                
                // 交织为立体声并转换为 16-bit PCM
                val stereoData = interleave(finalLeft, finalRight)
                val shortData = floatToShort(stereoData)
                audioTrackStereo?.write(shortData, 0, shortData.size, AudioTrack.WRITE_BLOCKING)
                
                // 更新位置
                position += chunkSize
                positionLock.withLock { currentPosition = position }
                
                // 每2秒输出一次日志
                if (count % 40 == 0) {
                    val percent = position * 100 / minLength
                    Log.d(TAG, "[Stereo] 进度: ${String.format("%.1f", position / SAMPLE_RATE.toFloat())}s ($percent%)")
                }
                count++
                
                updateState()
                Thread.sleep(1)
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "[Stereo] ====== 播放完成 ======")
            Log.i(TAG, "[Stereo]   播放时长: ${elapsed}ms")
            Log.i(TAG, "[Stereo]   最终位置: ${position / SAMPLE_RATE.toFloat()}s")
            Log.i(TAG, "[Stereo]   停止原因: ${if (stopEvent.get()) "用户停止" else "自然结束"}")
        
        } catch (e: Exception) {
            Log.e(TAG, "[Stereo] 发生异常", e)
        } finally {
            Log.d(TAG, "[Stereo] 释放资源...")
            audioTrackStereo?.stop()
            audioTrackStereo?.release()
            audioTrackStereo = null
            stereoStopped.set(true)
            Log.d(TAG, "[Stereo] 线程退出")
            checkEnd()
        }
    }
    
    /**
     * Center 流播放循环
     * 对标 Python _center_loop()
     * 
     * 输出：(C, C) - 独立的 Center 层，同时到左右耳
     */
    private fun centerLoop() {
        Log.i(TAG, "[Center] ====== 线程启动 ======")
        try {
            if (!shouldPlayCenter()) {
                Log.d(TAG, "[Center] 不需要播放，退出线程")
                return
            }
            
            if (!centerInfo.hasData()) {
                Log.w(TAG, "[Center] 无数据，退出线程")
                return
            }
            
            val minDuration = getMinDuration()
            if (minDuration == 0f) {
                Log.w(TAG, "[Center] 无有效时长，退出线程")
                return
            }
            
            val minLength = (minDuration * SAMPLE_RATE).toLong()
            var position = positionLock.withLock { currentPosition }
            
            val isCenterOnly = playMode == LcrPlayMode.CENTER_ONLY
            
            Log.i(TAG, "[Center] 播放参数:")
            Log.i(TAG, "[Center]   起始位置: ${String.format("%.2f", position / SAMPLE_RATE.toFloat())}s")
            Log.i(TAG, "[Center]   总长度: ${String.format("%.2f", minDuration)}s")
            Log.i(TAG, "[Center]   Center Only模式: $isCenterOnly")
            Log.i(TAG, "[Center]   独立位置控制: $isCenterOnly")
            
            Log.d(TAG, "[Center] 创建 AudioTrack...")
            audioTrackCenter = createAudioTrack()
            audioTrackCenter?.play()
            // 播放后立即应用速度设置
            if (playbackSpeed != 1.0f) {
                applySpeedToTrack(audioTrackCenter)
            }
            Log.d(TAG, "[Center] AudioTrack 已启动")
            
            var count = 0
            val startTime = System.currentTimeMillis()
            
            while (position < minLength && !stopEvent.get()) {
                if (_isPaused.get()) {
                    Thread.sleep(50)
                    continue
                }
                
                // Center 始终使用自己的 position（与 Stereo 独立但同步启动）
                // 这样避免两个线程读写同一位置导致的回音问题
                val centerChunk = centerInfo.getChunk(position, chunkSize)
                
                // 应用距离感处理
                applyProximity(centerChunk, centerInfo.proximity)
                
                applyVolume(centerChunk, centerInfo.volume)
                clipArray(centerChunk)
                
                // Center 输出到左右耳：(C, C)，转换为 16-bit PCM
                val stereoData = interleave(centerChunk, centerChunk)
                val shortData = floatToShort(stereoData)
                audioTrackCenter?.write(shortData, 0, shortData.size, AudioTrack.WRITE_BLOCKING)
                
                // 更新位置
                position += chunkSize
                
                // Center Only 模式：由 Center 流更新全局位置
                if (isCenterOnly) {
                    positionLock.withLock { currentPosition = position }
                }
                
                // 每2秒输出一次日志
                if (count % 40 == 0) {
                    Log.d(TAG, "[Center] 进度: ${String.format("%.1f", position / SAMPLE_RATE.toFloat())}s")
                }
                count++
                
                if (isCenterOnly) updateState()
                Thread.sleep(1)
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "[Center] ====== 播放完成 ======")
            Log.i(TAG, "[Center]   播放时长: ${elapsed}ms")
            Log.i(TAG, "[Center]   停止原因: ${if (stopEvent.get()) "用户停止" else "自然结束"}")
            
        } catch (e: Exception) {
            Log.e(TAG, "[Center] 发生异常", e)
        } finally {
            Log.d(TAG, "[Center] 释放资源...")
            audioTrackCenter?.stop()
            audioTrackCenter?.release()
            audioTrackCenter = null
            centerStopped.set(true)
            Log.d(TAG, "[Center] 线程退出")
            checkEnd()
        }
    }
    
    /**
     * 检查是否播放结束
     * 对标 Python _check_end()
     */
    private fun checkEnd() {
        if (stereoStopped.get() && centerStopped.get()) {
            if (!stopEvent.get() && _isPlaying.get()) {
                if (isLooping) {
                    // 循环播放：重置位置并重新开始
                    Log.i(TAG, "播放结束，循环播放中...")
                    positionLock.withLock { currentPosition = 0 }
                    
                    // 重置缓冲区到开头
                    CoroutineScope(Dispatchers.IO).launch {
                        leftInfo.seekTo(0)
                        centerInfo.seekTo(0)
                        rightInfo.seekTo(0)
                        
                        // 重新启动播放
                        withContext(Dispatchers.Main) {
                            stopEvent.set(false)
                            stereoStopped.set(false)
                            centerStopped.set(false)
                            
                            if (!shouldPlayStereo()) stereoStopped.set(true)
                            if (!shouldPlayCenter()) centerStopped.set(true)
                            
                            threadStereo = thread(name = "LCR-StereoStream") { stereoLoop() }
                            threadCenter = thread(name = "LCR-CenterStream") { centerLoop() }
                            
                            updateState()
                        }
                    }
                } else {
                    Log.i(TAG, "播放自然结束")
                    _isPlaying.set(false)
                    updateState()
                    onPlaybackEnd?.invoke()
                }
            }
        }
    }
    
    /**
     * 创建 AudioTrack
     * 使用 16-bit PCM 而非 Float PCM，因为 Float PCM 不支持 PlaybackParams 变速
     */
    private fun createAudioTrack(): AudioTrack {
        val bufferSize = chunkSize * 2 * 2  // stereo * 16-bit (2 bytes)
        
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)  // 改用 16-bit PCM 以支持变速
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        
        // 注意：速度需要在 track.play() 之后应用，在 stereoLoop/centerLoop 中处理
        return track
    }
    
    /**
     * Float 数组转换为 Short 数组 (16-bit PCM)
     * Float [-1.0, 1.0] -> Short [-32768, 32767]
     */
    private fun floatToShort(floatData: FloatArray): ShortArray {
        val shortData = ShortArray(floatData.size)
        for (i in floatData.indices) {
            // 限幅并转换
            val clamped = floatData[i].coerceIn(-1f, 1f)
            shortData[i] = (clamped * 32767f).toInt().toShort()
        }
        return shortData
    }
    
    /**
     * 应用音量
     */
    private fun applyVolume(data: FloatArray, volume: Float) {
        for (i in data.indices) {
            data[i] *= volume
        }
    }
    
    /**
     * 应用距离感处理（A+B方案 - 增强版）
     * 
     * @param data 音频数据
     * @param proximity 距离感参数 (0.0=远, 1.0=近)
     * 
     * 处理内容：
     * 1. 增益调整：近距离 +12dB，远距离 -12dB（更强的动态范围）
     * 2. 高频增强：近距离增加高频清晰度（锐化瞬态）
     * 3. 低频增强：近距离增加低频力量感（增加临场感）
     * 4. 中高频增强：增加"在耳边"的感觉
     */
    private fun applyProximity(data: FloatArray, proximity: Float) {
        if (data.isEmpty()) return
        if (proximity == 0.5f) return  // 中性值无需处理
        
        // 1. 增益调整：proximity 0.5 为中性，范围 -12dB 到 +12dB（更强）
        val gainDb = (proximity - 0.5f) * 24f
        val gain = Math.pow(10.0, (gainDb / 20f).toDouble()).toFloat()
        
        // 2. 高频增强系数：proximity 越高，高频混合越多（增强到 50%）
        val highFreqMix = (proximity - 0.5f).coerceIn(0f, 0.5f) * 1.0f  // 0-0.5
        
        // 3. 低频增强系数：proximity 越高，低频增强越多（增强到 40%）
        val lowFreqBoost = (proximity - 0.5f).coerceIn(0f, 0.5f) * 0.8f  // 0-0.4
        
        // 4. 中高频增强（2-4kHz 区域，增加"在耳边"感）
        val midHighBoost = (proximity - 0.5f).coerceIn(0f, 0.5f) * 0.6f  // 0-0.3
        
        // 高通滤波器 (~500Hz) - 提取高频
        val alpha = 0.93f
        var prevInput = data[0]
        var prevHighPass = 0f
        
        // 低通滤波器 (~100Hz) - 提取低频
        val lowAlpha = 0.985f
        var lowPassValue = data[0]
        
        // 带通滤波器 (~2kHz) - 提取中高频
        val bandAlpha = 0.85f
        var prevBandInput = data[0]
        var prevBandPass = 0f
        var bandLowPass = 0f
        val bandLowAlpha = 0.95f
        
        for (i in data.indices) {
            val input = data[i]
            
            // 高通滤波（提取高频）
            val highPass = alpha * (prevHighPass + input - prevInput)
            prevInput = input
            prevHighPass = highPass
            
            // 低通滤波（提取低频）
            lowPassValue = lowAlpha * lowPassValue + (1f - lowAlpha) * input
            
            // 带通滤波（提取中高频）- 先高通再低通
            val tempBand = bandAlpha * (prevBandPass + input - prevBandInput)
            prevBandInput = input
            prevBandPass = tempBand
            bandLowPass = bandLowAlpha * bandLowPass + (1f - bandLowAlpha) * tempBand
            
            // 组合：原始信号 + 各频段增强
            var output = input * gain
            output += highPass * highFreqMix * gain           // 高频增强（清晰度）
            output += lowPassValue * lowFreqBoost * gain      // 低频增强（力量感）
            output += bandLowPass * midHighBoost * gain       // 中高频增强（临场感）
            
            data[i] = output
        }
    }
    
    /**
     * 设置声道距离感
     * @param channel 声道类型
     * @param proximity 距离感 (0.0=远, 1.0=近)
     */
    fun setProximity(channel: LcrChannelType, proximity: Float) {
        val p = proximity.coerceIn(0f, 1f)
        when (channel) {
            LcrChannelType.LEFT -> leftInfo.proximity = p
            LcrChannelType.CENTER -> centerInfo.proximity = p
            LcrChannelType.RIGHT -> rightInfo.proximity = p
        }
        Log.d(TAG, "设置距离感: $channel = ${String.format("%.0f", p * 100)}%")
    }
    
    /**
     * 获取声道距离感
     */
    fun getProximity(channel: LcrChannelType): Float {
        return when (channel) {
            LcrChannelType.LEFT -> leftInfo.proximity
            LcrChannelType.CENTER -> centerInfo.proximity
            LcrChannelType.RIGHT -> rightInfo.proximity
        }
    }
    
    /**
     * 限幅到 [-1, 1]
     */
    private fun clipArray(data: FloatArray) {
        for (i in data.indices) {
            data[i] = data[i].coerceIn(-1f, 1f)
        }
    }
    
    /**
     * 交织两个单声道数组为立体声
     */
    private fun interleave(left: FloatArray, right: FloatArray): FloatArray {
        val result = FloatArray(left.size * 2)
        for (i in left.indices) {
            result[i * 2] = left[i]
            result[i * 2 + 1] = right[i]
        }
        return result
    }
    
    /**
     * 关闭所有流
     */
    private fun closeStreams() {
        audioTrackStereo?.release()
        audioTrackStereo = null
        audioTrackCenter?.release()
        audioTrackCenter = null
    }
    
    /**
     * 更新状态流
     */
    private fun updateState() {
        val pos = positionLock.withLock { currentPosition }
        _playbackState.value = LcrPlaybackState(
            isPlaying = _isPlaying.get(),
            isPaused = _isPaused.get(),
            isSeeking = _isSeeking.get(),
            position = pos,
            positionSeconds = pos / SAMPLE_RATE.toFloat(),
            durationSeconds = getMinDuration(),
            playMode = playMode,
            isSwitched = isSwitched,
            memoryUsageMB = getTotalMemoryUsageMB(),
            playbackSpeed = playbackSpeed,
            isLooping = isLooping
        )
    }
}
