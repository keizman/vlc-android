package org.videolan.vlc.gui.lcr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.min

private const val TAG = "LCR/StreamingSource"

/**
 * 流式音频源 - 边加载边播放
 * 
 * 设计：
 * - 快速启动：只需加载 QUICK_START_SEC 秒数据即可开始播放
 * - 后台预加载：播放的同时后台继续加载
 * - 滑动窗口：缓冲区随播放位置移动
 */
class StreamingAudioSource(
    private val context: Context,
    private val channelType: LcrChannelType,
    private val inverted: Boolean = false
) {
    companion object {
        // 快速启动：只需加载这么多秒就可以开始播放
        const val QUICK_START_SEC = 5f
        // 预加载阈值：剩余缓冲小于此值时触发预加载
        const val PRELOAD_THRESHOLD_SEC = 30f
        // 每次预加载的数据量
        const val PRELOAD_CHUNK_SEC = 60f
    }
    
    // 缓冲区
    private var buffer: FloatArray? = null
    private var bufferStartSample = 0L  // 缓冲区起始采样点
    private var bufferValidSamples = 0  // 缓冲区有效采样点数
    
    // 文件信息
    var totalDurationSec: Float = 0f
        private set
    var totalSamples: Long = 0L
        private set
    var sampleRate: Int = LcrMemoryConfig.SAMPLE_RATE
        private set
    var sourceChannelCount: Int = 2
        private set
    
    // URI
    private var uri: Uri? = null
    private var isFullyLoaded = false
    
    // 预加载线程
    private var preloadThread: Thread? = null
    private val stopPreload = AtomicBoolean(false)
    private val isPreloading = AtomicBoolean(false)
    private val bufferLock = ReentrantLock()
    
    // MIME 类型缓存
    private var cachedMime: String? = null
    
    /**
     * 初始化音频源
     */
    suspend fun initialize(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "[$channelType] 初始化: $uri")
        this@StreamingAudioSource.uri = uri
        
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex == -1) {
                Log.e(TAG, "[$channelType] 未找到音频轨道")
                return@withContext false
            }
            
            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            sourceChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            cachedMime = format.getString(MediaFormat.KEY_MIME)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            totalDurationSec = durationUs / 1_000_000f
            totalSamples = (totalDurationSec * sampleRate).toLong()
            
            Log.d(TAG, "[$channelType] 音频: ${sampleRate}Hz, ${sourceChannelCount}ch, ${String.format("%.1f", totalDurationSec)}s")
            
            // 警告：Center 使用立体声文件会导致混合问题
            if (channelType == LcrChannelType.CENTER && sourceChannelCount > 1) {
                Log.w(TAG, "⚠️ [$channelType] 警告: Center 文件是 ${sourceChannelCount} 声道!")
                Log.w(TAG, "⚠️ [$channelType] 立体声会被混合为单声道，可能导致与 LR 融合!")
                Log.w(TAG, "⚠️ [$channelType] 建议使用真正的单声道文件作为 Center")
            }
            
            // 判断是否可以全量加载
            isFullyLoaded = LcrMemoryConfig.canLoadFully(totalDurationSec)
            
            // 分配缓冲区
            val bufferSize = LcrMemoryConfig.getActualBufferSize(totalDurationSec)
            buffer = FloatArray(bufferSize)
            
            val memoryMB = bufferSize * 4f / 1024 / 1024
            Log.d(TAG, "[$channelType] 缓冲区: ${String.format("%.1f", memoryMB)}MB, 全量: $isFullyLoaded")
            
            extractor.release()
            
            // 加载初始数据
            loadInitialData()
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "[$channelType] 初始化失败: ${e.message}", e)
            extractor.release()
            false
        }
    }
    
    /**
     * 加载初始数据
     */
    private suspend fun loadInitialData() = withContext(Dispatchers.IO) {
        loadFromPositionInternal(0, fullLoad = isFullyLoaded)
    }
    
    /**
     * 快速 Seek - 只加载少量数据即可返回
     */
    suspend fun seekTo(positionSample: Long) {
        val posSec = positionSample.toFloat() / sampleRate
        
        if (isFullyLoaded) {
            Log.d(TAG, "[$channelType] Seek ${String.format("%.1f", posSec)}s - 全量模式")
            return
        }
        
        // 检查是否已在缓冲区
        bufferLock.withLock {
            val bufferEnd = bufferStartSample + bufferValidSamples
            if (positionSample >= bufferStartSample && positionSample < bufferEnd) {
                val bufStartSec = bufferStartSample.toFloat() / sampleRate
                val bufEndSec = bufferEnd.toFloat() / sampleRate
                Log.d(TAG, "[$channelType] Seek ${String.format("%.1f", posSec)}s - 已在缓冲区 [${String.format("%.1f", bufStartSec)}s-${String.format("%.1f", bufEndSec)}s]")
                return
            }
        }
        
        // 停止当前预加载
        stopPreload.set(true)
        preloadThread?.join(300)
        
        Log.i(TAG, "[$channelType] Seek ${String.format("%.1f", posSec)}s - 快速加载...")
        
        // 快速加载：只加载少量数据
        withContext(Dispatchers.IO) {
            loadFromPositionInternal(positionSample, fullLoad = false)
        }
        
        // 启动后台预加载
        startBackgroundPreload()
    }
    
    /**
     * 从指定位置加载数据
     * @param fullLoad true=尽可能多加载, false=只加载快速启动需要的量
     */
    private fun loadFromPositionInternal(startSample: Long, fullLoad: Boolean) {
        val targetUri = uri ?: return
        val targetBuffer = buffer ?: return
        
        val startTime = System.currentTimeMillis()
        val startSec = startSample.toFloat() / sampleRate
        
        // 计算目标加载量
        val quickStartSamples = (QUICK_START_SEC * sampleRate).toInt()
        val maxSamples = if (fullLoad) targetBuffer.size else minOf(quickStartSamples * 2, targetBuffer.size)
        
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, targetUri, null)
            val audioTrackIndex = findAudioTrack(extractor)
            extractor.selectTrack(audioTrackIndex)
            
            // Seek
            if (startSample > 0) {
                val seekTimeUs = (startSample.toFloat() / sampleRate * 1_000_000).toLong()
                extractor.seekTo(seekTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            
            var samplesDecoded = 0
            val bufferInfo = MediaCodec.BufferInfo()
            var isExtractorEOS = false
            var isDecoderEOS = false
            
            // 跳过 seek 之前的数据
            val actualStartSample = if (startSample > 0) {
                (extractor.sampleTime / 1_000_000f * sampleRate).toLong()
            } else 0L
            var skipSamples = (startSample - actualStartSample).coerceAtLeast(0).toInt()
            
            while (!isDecoderEOS && samplesDecoded < maxSamples) {
                if (!isExtractorEOS) {
                    val inputBufferId = codec.dequeueInputBuffer(5000)
                    if (inputBufferId >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferId)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isExtractorEOS = true
                        } else {
                            codec.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                
                val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 5000)
                if (outputBufferId >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferId)!!
                        val samples = extractSamplesFromBuffer(outputBuffer, bufferInfo.size)
                        
                        val effectiveSamples = if (skipSamples > 0) {
                            val skip = min(skipSamples, samples.size)
                            skipSamples -= skip
                            samples.copyOfRange(skip, samples.size)
                        } else {
                            samples
                        }
                        
                        if (effectiveSamples.isNotEmpty()) {
                            val remaining = maxSamples - samplesDecoded
                            val toCopy = min(effectiveSamples.size, remaining)
                            System.arraycopy(effectiveSamples, 0, targetBuffer, samplesDecoded, toCopy)
                            samplesDecoded += toCopy
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferId, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderEOS = true
                    }
                }
            }
            
            codec.stop()
            codec.release()
            extractor.release()
            
            bufferLock.withLock {
                bufferStartSample = startSample
                bufferValidSamples = samplesDecoded
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            val loadedSec = samplesDecoded.toFloat() / sampleRate
            val endSec = startSec + loadedSec
            Log.i(TAG, "[$channelType] 缓冲: [${String.format("%.1f", startSec)}s-${String.format("%.1f", endSec)}s] ${elapsed}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "[$channelType] 加载失败: ${e.message}", e)
            extractor.release()
        }
    }
    
    /**
     * 启动后台预加载
     */
    private fun startBackgroundPreload() {
        if (isFullyLoaded) return
        if (preloadThread?.isAlive == true) return
        
        // 检查是否真的需要预加载
        val needMore = bufferLock.withLock {
            val bufferEnd = bufferStartSample + bufferValidSamples
            val remainingSamples = buffer?.size?.minus(bufferValidSamples) ?: 0
            remainingSamples > 0 && bufferEnd < totalSamples
        }
        
        if (!needMore) {
            // 缓冲区已满，不需要预加载
            return
        }
        
        stopPreload.set(false)
        preloadThread = thread(name = "Preload-$channelType") {
            backgroundPreloadLoop()
        }
    }
    
    /**
     * 后台预加载循环
     */
    private fun backgroundPreloadLoop() {
        Log.d(TAG, "[$channelType] 预加载线程启动")
        
        while (!stopPreload.get()) {
            val needMore = bufferLock.withLock {
                val bufferEnd = bufferStartSample + bufferValidSamples
                val remainingSamples = buffer?.size?.minus(bufferValidSamples) ?: 0
                remainingSamples > 0 && bufferEnd < totalSamples
            }
            
            if (!needMore) {
                break
            }
            
            isPreloading.set(true)
            appendMoreData()
            isPreloading.set(false)
            
            // 短暂休息避免CPU占用过高
            Thread.sleep(100)
        }
        
        Log.d(TAG, "[$channelType] 预加载线程结束")
    }
    
    /**
     * 追加更多数据到缓冲区末尾
     */
    private fun appendMoreData() {
        val targetUri = uri ?: return
        val targetBuffer = buffer ?: return
        
        val appendStartSample: Long
        val appendOffset: Int
        val maxAppend: Int
        
        bufferLock.withLock {
            appendStartSample = bufferStartSample + bufferValidSamples
            appendOffset = bufferValidSamples
            maxAppend = targetBuffer.size - bufferValidSamples
        }
        
        if (maxAppend <= 0 || appendStartSample >= totalSamples) return
        
        val chunkSamples = min((PRELOAD_CHUNK_SEC * sampleRate).toInt(), maxAppend)
        
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, targetUri, null)
            val audioTrackIndex = findAudioTrack(extractor)
            extractor.selectTrack(audioTrackIndex)
            
            val seekTimeUs = (appendStartSample.toFloat() / sampleRate * 1_000_000).toLong()
            extractor.seekTo(seekTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            
            var samplesDecoded = 0
            val bufferInfo = MediaCodec.BufferInfo()
            var isExtractorEOS = false
            var isDecoderEOS = false
            
            val actualStartSample = (extractor.sampleTime / 1_000_000f * sampleRate).toLong()
            var skipSamples = (appendStartSample - actualStartSample).coerceAtLeast(0).toInt()
            
            while (!isDecoderEOS && samplesDecoded < chunkSamples && !stopPreload.get()) {
                if (!isExtractorEOS) {
                    val inputBufferId = codec.dequeueInputBuffer(5000)
                    if (inputBufferId >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferId)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isExtractorEOS = true
                        } else {
                            codec.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                
                val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 5000)
                if (outputBufferId >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferId)!!
                        val samples = extractSamplesFromBuffer(outputBuffer, bufferInfo.size)
                        
                        val effectiveSamples = if (skipSamples > 0) {
                            val skip = min(skipSamples, samples.size)
                            skipSamples -= skip
                            samples.copyOfRange(skip, samples.size)
                        } else {
                            samples
                        }
                        
                        if (effectiveSamples.isNotEmpty()) {
                            bufferLock.withLock {
                                val remaining = chunkSamples - samplesDecoded
                                val toCopy = min(effectiveSamples.size, remaining)
                                val writeOffset = appendOffset + samplesDecoded
                                if (writeOffset + toCopy <= targetBuffer.size) {
                                    System.arraycopy(effectiveSamples, 0, targetBuffer, writeOffset, toCopy)
                                    samplesDecoded += toCopy
                                    bufferValidSamples = appendOffset + samplesDecoded
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferId, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderEOS = true
                    }
                }
            }
            
            codec.stop()
            codec.release()
            extractor.release()
            
            if (samplesDecoded > 0) {
                val loadedSec = samplesDecoded.toFloat() / sampleRate
                Log.d(TAG, "[$channelType] 预加载追加: +${String.format("%.1f", loadedSec)}s")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "[$channelType] 预加载失败: ${e.message}", e)
            extractor.release()
        }
    }
    
    /**
     * 从解码缓冲区提取目标声道数据
     * MediaCodec 输出的是 16-bit PCM (Short)，需要转换为 Float
     */
    private fun extractSamplesFromBuffer(outputBuffer: ByteBuffer, size: Int): FloatArray {
        outputBuffer.position(0)
        outputBuffer.order(ByteOrder.nativeOrder())
        
        // MediaCodec 输出 16-bit PCM (Short, 2 bytes per sample)
        val shortBuffer = outputBuffer.asShortBuffer()
        val totalShorts = size / 2  // 2 bytes per short
        val tempShorts = ShortArray(totalShorts)
        shortBuffer.get(tempShorts)
        
        // 转换 Short (-32768 ~ 32767) 到 Float (-1.0 ~ 1.0)
        val tempFloats = FloatArray(totalShorts)
        for (i in tempShorts.indices) {
            tempFloats[i] = tempShorts[i] / 32768f
        }
        
        return when {
            sourceChannelCount == 1 -> tempFloats
            channelType == LcrChannelType.CENTER -> mixToMono(tempFloats, sourceChannelCount)
            channelType == LcrChannelType.LEFT -> {
                val targetCh = if (inverted) 1 else 0
                extractChannel(tempFloats, sourceChannelCount, targetCh)
            }
            channelType == LcrChannelType.RIGHT -> {
                val targetCh = if (inverted) 0 else 1
                extractChannel(tempFloats, sourceChannelCount, targetCh)
            }
            else -> tempFloats
        }
    }
    
    private fun extractChannel(data: FloatArray, channels: Int, targetCh: Int): FloatArray {
        if (channels == 1) return data
        val result = FloatArray(data.size / channels)
        for (i in result.indices) {
            result[i] = data[i * channels + targetCh.coerceIn(0, channels - 1)]
        }
        return result
    }
    
    /**
     * 混合为单声道 - 优化版本
     * 
     * 问题：简单平均 (L+R)/2 会导致：
     * 1. 相位抵消：L/R 反相成分相互抵消，丢失细节
     * 2. 音量衰减：-6dB 的损失
     * 
     * 优化策略：
     * - 使用 sqrt(2)/2 ≈ 0.707 作为混合系数（保持能量而非幅度）
     * - 不做硬除法，避免过度衰减
     * - 添加软限幅保护
     */
    private fun mixToMono(data: FloatArray, channels: Int): FloatArray {
        if (channels == 1) return data
        
        val result = FloatArray(data.size / channels)
        // 使用 0.707 (sqrt(2)/2) 作为混合系数，保持能量级别
        // 对于立体声：out = (L + R) * 0.707，而不是 (L + R) / 2
        val mixGain = 0.707f
        
        for (i in result.indices) {
            var sum = 0f
            for (ch in 0 until channels) {
                sum += data[i * channels + ch]
            }
            // 应用混合增益并软限幅
            result[i] = softClip(sum * mixGain)
        }
        return result
    }
    
    /**
     * 软限幅 - 比硬限幅更自然，保留瞬态细节
     * 使用 tanh 函数实现平滑压缩
     */
    private fun softClip(x: Float): Float {
        return when {
            x > 1.0f -> 1.0f - 0.1f / (x + 0.1f)  // 软饱和
            x < -1.0f -> -1.0f + 0.1f / (-x + 0.1f)
            else -> x
        }
    }
    
    /**
     * 获取数据块
     */
    fun getChunk(positionSample: Long, size: Int): FloatArray {
        val result = FloatArray(size)
        val targetBuffer = buffer ?: return result
        
        bufferLock.withLock {
            val bufferEnd = bufferStartSample + bufferValidSamples
            
            if (positionSample >= bufferStartSample && positionSample < bufferEnd) {
                val offsetInBuffer = (positionSample - bufferStartSample).toInt()
                val available = bufferValidSamples - offsetInBuffer
                val toCopy = min(size, available)
                
                if (toCopy > 0) {
                    System.arraycopy(targetBuffer, offsetInBuffer, result, 0, toCopy)
                }
                
                // 检查是否需要预加载
                val remainingSec = (bufferEnd - positionSample - size).toFloat() / sampleRate
                if (!isFullyLoaded && remainingSec < PRELOAD_THRESHOLD_SEC && !isPreloading.get()) {
                    startBackgroundPreload()
                }
            } else {
                val posSec = positionSample.toFloat() / sampleRate
                val bufStartSec = bufferStartSample.toFloat() / sampleRate
                val bufEndSec = bufferEnd.toFloat() / sampleRate
                Log.e(TAG, "[$channelType] 数据不在缓冲区! ${String.format("%.1f", posSec)}s 不在 [${String.format("%.1f", bufStartSec)}s-${String.format("%.1f", bufEndSec)}s]")
            }
        }
        
        return result
    }
    
    /**
     * 检查位置是否在缓冲区内
     */
    fun isPositionInBuffer(positionSample: Long): Boolean {
        if (isFullyLoaded) return true
        
        return bufferLock.withLock {
            val bufferEnd = bufferStartSample + bufferValidSamples
            positionSample >= bufferStartSample && positionSample < bufferEnd
        }
    }
    
    /**
     * 检查是否有足够数据可以开始播放
     */
    fun hasEnoughDataToPlay(positionSample: Long): Boolean {
        if (isFullyLoaded) return true
        
        return bufferLock.withLock {
            val bufferEnd = bufferStartSample + bufferValidSamples
            if (positionSample < bufferStartSample || positionSample >= bufferEnd) {
                false
            } else {
                val availableSec = (bufferEnd - positionSample).toFloat() / sampleRate
                availableSec >= QUICK_START_SEC
            }
        }
    }
    
    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }
    
    fun release() {
        Log.d(TAG, "[$channelType] 释放")
        stopPreload.set(true)
        preloadThread?.join(500)
        buffer = null
        bufferValidSamples = 0
    }
    
    fun getMemoryUsage(): Long = (buffer?.size ?: 0) * 4L
    
    fun getLoadedDurationSec(): Float = bufferValidSamples.toFloat() / sampleRate
}
