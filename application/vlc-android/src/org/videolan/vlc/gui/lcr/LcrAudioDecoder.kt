/**
 * LCR 三声道播放器 - 音频解码器
 * 使用 MediaCodec 解码音频文件为 PCM Float 数组
 * 对标 Python audio_loader.py
 */

package org.videolan.vlc.gui.lcr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "LCR-Decoder"
private const val TIMEOUT_US = 10000L

/**
 * 音频解码器
 * 对标 Python AudioLoader
 */
class LcrAudioDecoder(private val context: Context) {
    
    companion object {
        const val TARGET_SAMPLE_RATE = 44100
    }
    
    /**
     * 解码结果
     */
    data class DecodeResult(
        val pcmData: FloatArray,
        val duration: Float,
        val sampleRate: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DecodeResult
            if (!pcmData.contentEquals(other.pcmData)) return false
            if (duration != other.duration) return false
            if (sampleRate != other.sampleRate) return false
            return true
        }

        override fun hashCode(): Int {
            var result = pcmData.contentHashCode()
            result = 31 * result + duration.hashCode()
            result = 31 * result + sampleRate
            return result
        }
    }
    
    /**
     * 解码音频文件
     * 对标 Python AudioLoader.load_audio()
     * 
     * @param uri 音频文件URI
     * @param channelType 声道类型 (LEFT, RIGHT, CENTER)
     * @param inverted 是否反转（LEFT提取右声道，RIGHT提取左声道）
     * @return 解码结果
     */
    fun decode(uri: Uri, channelType: LcrChannelType, inverted: Boolean = false): DecodeResult {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "========== 开始解码 ==========")
        Log.i(TAG, "  URI: $uri")
        Log.i(TAG, "  声道类型: $channelType")
        Log.i(TAG, "  反转: $inverted")
        
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            Log.d(TAG, "数据源设置成功")
            
            // 找到音频轨道
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) {
                Log.e(TAG, "未找到音频轨道!")
                throw IllegalArgumentException("未找到音频轨道")
            }
            Log.d(TAG, "找到音频轨道: index=$audioTrackIndex")
            
            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("未知MIME类型")
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1_000_000f
            } else 0f
            
            Log.i(TAG, "音频格式信息:")
            Log.i(TAG, "  MIME: $mime")
            Log.i(TAG, "  采样率: $sampleRate Hz")
            Log.i(TAG, "  声道数: $channelCount")
            Log.i(TAG, "  时长: ${String.format("%.2f", duration)}s")
            
            // 解码
            Log.d(TAG, "开始 MediaCodec 解码...")
            val decodeStartTime = System.currentTimeMillis()
            val rawPcm = decodeToShortArray(extractor, format, mime)
            val decodeTime = System.currentTimeMillis() - decodeStartTime
            Log.i(TAG, "解码完成: ${rawPcm.size} samples, 耗时 ${decodeTime}ms")
            
            // 估算内存使用
            val rawMemoryMB = rawPcm.size * 2 / 1024f / 1024f
            Log.d(TAG, "原始PCM内存: ${String.format("%.2f", rawMemoryMB)} MB")
            
            // 提取指定声道并转换为Float
            Log.d(TAG, "提取声道: $channelType (inverted=$inverted)")
            val floatPcm = extractChannel(rawPcm, channelCount, channelType, inverted)
            Log.d(TAG, "声道提取完成: ${floatPcm.size} samples")
            
            // 重采样（如果需要）
            val resampledPcm = if (sampleRate != TARGET_SAMPLE_RATE) {
                Log.d(TAG, "重采样: $sampleRate -> $TARGET_SAMPLE_RATE Hz")
                resample(floatPcm, sampleRate, TARGET_SAMPLE_RATE)
            } else {
                floatPcm
            }
            
            val actualDuration = resampledPcm.size.toFloat() / TARGET_SAMPLE_RATE
            val finalMemoryMB = resampledPcm.size * 4 / 1024f / 1024f
            val totalTime = System.currentTimeMillis() - startTime
            
            Log.i(TAG, "========== 解码完成 ==========")
            Log.i(TAG, "  最终采样数: ${resampledPcm.size}")
            Log.i(TAG, "  实际时长: ${String.format("%.2f", actualDuration)}s")
            Log.i(TAG, "  内存占用: ${String.format("%.2f", finalMemoryMB)} MB")
            Log.i(TAG, "  总耗时: ${totalTime}ms")
            
            return DecodeResult(resampledPcm, actualDuration, TARGET_SAMPLE_RATE)
            
        } catch (e: Exception) {
            Log.e(TAG, "解码异常: ${e.message}", e)
            throw e
        } finally {
            extractor.release()
        }
    }
    
    /**
     * 找到音频轨道索引
     */
    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }
    
    /**
     * 解码为Short数组（原始PCM）
     */
    private fun decodeToShortArray(
        extractor: MediaExtractor,
        format: MediaFormat,
        mime: String
    ): ShortArray {
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        
        val bufferInfo = MediaCodec.BufferInfo()
        val outputData = mutableListOf<Short>()
        var inputDone = false
        var outputDone = false
        
        try {
            while (!outputDone) {
                // 输入
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }
                
                // 输出
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        
                        // 转换为Short
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        val shortBuffer = outputBuffer.asShortBuffer()
                        val samples = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(samples)
                        outputData.addAll(samples.toList())
                        
                        codec.releaseOutputBuffer(outputIndex, false)
                        
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "输出格式改变: ${codec.outputFormat}")
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        
        return outputData.toShortArray()
    }
    
    /**
     * 提取指定声道并转换为Float
     * 对标 Python audio_loader.py 的声道提取逻辑
     */
    private fun extractChannel(
        rawPcm: ShortArray,
        channelCount: Int,
        channelType: LcrChannelType,
        inverted: Boolean
    ): FloatArray {
        return if (channelCount == 1) {
            // 单声道：直接使用
            rawPcm.map { it.toFloat() / 32768f }.toFloatArray()
        } else if (channelCount >= 2) {
            // 立体声或多声道
            val frameCount = rawPcm.size / channelCount
            val result = FloatArray(frameCount)
            
            when (channelType) {
                LcrChannelType.LEFT -> {
                    // Left: 提取左声道（索引0），如果inverted则提取右声道（索引1）
                    val sourceChannel = if (inverted) 1 else 0
                    for (i in 0 until frameCount) {
                        result[i] = rawPcm[i * channelCount + sourceChannel].toFloat() / 32768f
                    }
                }
                LcrChannelType.RIGHT -> {
                    // Right: 提取右声道（索引1），如果inverted则提取左声道（索引0）
                    val sourceChannel = if (inverted) 0 else 1
                    for (i in 0 until frameCount) {
                        result[i] = rawPcm[i * channelCount + sourceChannel].toFloat() / 32768f
                    }
                }
                LcrChannelType.CENTER -> {
                    // Center: 混合为单声道（对标Python的set_channels(1)）
                    for (i in 0 until frameCount) {
                        var sum = 0f
                        for (ch in 0 until channelCount) {
                            sum += rawPcm[i * channelCount + ch].toFloat() / 32768f
                        }
                        result[i] = sum / channelCount
                    }
                }
            }
            result
        } else {
            floatArrayOf()
        }
    }
    
    /**
     * 简单的线性重采样
     */
    private fun resample(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
        if (inputRate == outputRate) return input
        
        val ratio = inputRate.toDouble() / outputRate
        val outputLength = (input.size / ratio).toInt()
        val output = FloatArray(outputLength)
        
        for (i in 0 until outputLength) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = (srcPos - srcIndex).toFloat()
            
            output[i] = if (srcIndex + 1 < input.size) {
                input[srcIndex] * (1 - frac) + input[srcIndex + 1] * frac
            } else {
                input[srcIndex]
            }
        }
        
        return output
    }
}

