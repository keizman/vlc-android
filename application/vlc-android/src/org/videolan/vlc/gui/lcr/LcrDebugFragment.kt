/**
 * LCR 三声道调试 Fragment
 * 对标 Python main_window.py 的 LCRPlayerGUI
 */

package org.videolan.vlc.gui.lcr

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.videolan.vlc.R

private const val TAG = "LCR-Fragment"

class LcrDebugFragment : Fragment() {

    private lateinit var player: LcrDualStreamPlayer
    
    // 文件选择相关 - Left
    private lateinit var leftFileContainer: View
    private lateinit var leftFileName: TextView
    private lateinit var leftDuration: TextView
    private lateinit var leftPlaceholder: TextView
    private lateinit var leftSelectBtn: Button
    private lateinit var leftClearBtn: Button
    private lateinit var leftInvertBtn: Button
    
    // 文件选择相关 - Center
    private lateinit var centerFileContainer: View
    private lateinit var centerFileName: TextView
    private lateinit var centerDuration: TextView
    private lateinit var centerPlaceholder: TextView
    private lateinit var centerSelectBtn: Button
    private lateinit var centerClearBtn: Button
    
    // 文件选择相关 - Right
    private lateinit var rightFileContainer: View
    private lateinit var rightFileName: TextView
    private lateinit var rightDuration: TextView
    private lateinit var rightPlaceholder: TextView
    private lateinit var rightSelectBtn: Button
    private lateinit var rightClearBtn: Button
    private lateinit var rightInvertBtn: Button
    
    // 播放模式
    private lateinit var modeGroup: RadioGroup
    private lateinit var switchButton: Button
    private lateinit var switchStatus: TextView
    
    // 倍速控制
    private lateinit var speedText: TextView
    private lateinit var speedSeekBar: SeekBar
    private lateinit var speed1xBtn: Button
    private lateinit var speed125xBtn: Button
    private lateinit var speed15xBtn: Button
    private lateinit var speed175xBtn: Button
    private lateinit var speed2xBtn: Button
    private lateinit var loopCheckBox: CheckBox
    
    // 播放控制
    private lateinit var playButton: Button
    private lateinit var stopButton: Button
    
    // 音量控制
    private lateinit var volumeLeftSeekBar: SeekBar
    private lateinit var volumeCenterSeekBar: SeekBar
    private lateinit var volumeRightSeekBar: SeekBar
    private lateinit var volumeLeftText: TextView
    private lateinit var volumeCenterText: TextView
    private lateinit var volumeRightText: TextView
    
    // 距离感控制
    private lateinit var proximityLeftSeekBar: SeekBar
    private lateinit var proximityCenterSeekBar: SeekBar
    private lateinit var proximityRightSeekBar: SeekBar
    private lateinit var proximityLeftText: TextView
    private lateinit var proximityCenterText: TextView
    private lateinit var proximityRightText: TextView
    
    // 重置按钮
    private lateinit var volumeResetBtn: Button
    private lateinit var proximityResetBtn: Button
    
    // 进度
    private lateinit var progressBar: SeekBar
    private lateinit var timeText: TextView
    
    // 状态
    private lateinit var statusText: TextView
    
    // 当前选择的声道（用于文件选择回调）
    private var pendingChannelType: LcrChannelType? = null
    
    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "文件选择结果: resultCode=${result.resultCode}, data=${result.data?.data}")
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                pendingChannelType?.let { channel ->
                    Log.i(TAG, "选择文件成功: channel=$channel, uri=$uri")
                    loadAudioFile(uri, channel)
                }
            }
        } else {
            Log.w(TAG, "文件选择取消或失败")
        }
        pendingChannelType = null
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.i(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_lcr_debug, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.i(TAG, "onViewCreated")
        
        // 初始化播放器
        player = LcrDualStreamPlayer(requireContext())
        player.onPlaybackEnd = { 
            Log.i(TAG, "播放结束回调")
            activity?.runOnUiThread { 
                updatePlayButton(false)
                setStatus("播放结束", StatusColor.ORANGE)
            }
        }
        
        // 绑定视图
        bindViews(view)
        
        // 设置监听器
        setupListeners()
        
        // 观察播放状态
        observePlaybackState()
        
        Log.i(TAG, "初始化完成")
    }
    
    override fun onDestroyView() {
        Log.i(TAG, "onDestroyView")
        player.close()
        super.onDestroyView()
    }
    
    private fun bindViews(view: View) {
        Log.d(TAG, "绑定视图")
        
        // Left
        leftFileContainer = view.findViewById(R.id.lcr_left_file_container)
        leftFileName = view.findViewById(R.id.lcr_left_file_name)
        leftDuration = view.findViewById(R.id.lcr_left_duration)
        leftPlaceholder = view.findViewById(R.id.lcr_left_placeholder)
        leftSelectBtn = view.findViewById(R.id.lcr_left_select)
        leftClearBtn = view.findViewById(R.id.lcr_left_clear)
        leftInvertBtn = view.findViewById(R.id.lcr_left_invert)
        
        // Center
        centerFileContainer = view.findViewById(R.id.lcr_center_file_container)
        centerFileName = view.findViewById(R.id.lcr_center_file_name)
        centerDuration = view.findViewById(R.id.lcr_center_duration)
        centerPlaceholder = view.findViewById(R.id.lcr_center_placeholder)
        centerSelectBtn = view.findViewById(R.id.lcr_center_select)
        centerClearBtn = view.findViewById(R.id.lcr_center_clear)
        
        // Right
        rightFileContainer = view.findViewById(R.id.lcr_right_file_container)
        rightFileName = view.findViewById(R.id.lcr_right_file_name)
        rightDuration = view.findViewById(R.id.lcr_right_duration)
        rightPlaceholder = view.findViewById(R.id.lcr_right_placeholder)
        rightSelectBtn = view.findViewById(R.id.lcr_right_select)
        rightClearBtn = view.findViewById(R.id.lcr_right_clear)
        rightInvertBtn = view.findViewById(R.id.lcr_right_invert)
        
        // 播放模式
        modeGroup = view.findViewById(R.id.lcr_mode_group)
        switchButton = view.findViewById(R.id.lcr_switch_button)
        switchStatus = view.findViewById(R.id.lcr_switch_status)
        
        // 倍速控制
        speedText = view.findViewById(R.id.lcr_speed_text)
        speedSeekBar = view.findViewById(R.id.lcr_speed_seekbar)
        speed1xBtn = view.findViewById(R.id.lcr_speed_1x)
        speed125xBtn = view.findViewById(R.id.lcr_speed_1_25x)
        speed15xBtn = view.findViewById(R.id.lcr_speed_1_5x)
        speed175xBtn = view.findViewById(R.id.lcr_speed_1_75x)
        speed2xBtn = view.findViewById(R.id.lcr_speed_2x)
        loopCheckBox = view.findViewById(R.id.lcr_loop_checkbox)
        
        // 播放控制
        playButton = view.findViewById(R.id.lcr_play_button)
        stopButton = view.findViewById(R.id.lcr_stop_button)
        
        // 音量控制
        volumeLeftSeekBar = view.findViewById(R.id.lcr_volume_left)
        volumeCenterSeekBar = view.findViewById(R.id.lcr_volume_center)
        volumeRightSeekBar = view.findViewById(R.id.lcr_volume_right)
        volumeLeftText = view.findViewById(R.id.lcr_volume_left_text)
        volumeCenterText = view.findViewById(R.id.lcr_volume_center_text)
        volumeRightText = view.findViewById(R.id.lcr_volume_right_text)
        
        // 距离感控制
        proximityLeftSeekBar = view.findViewById(R.id.lcr_proximity_left)
        proximityCenterSeekBar = view.findViewById(R.id.lcr_proximity_center)
        proximityRightSeekBar = view.findViewById(R.id.lcr_proximity_right)
        proximityLeftText = view.findViewById(R.id.lcr_proximity_left_text)
        proximityCenterText = view.findViewById(R.id.lcr_proximity_center_text)
        proximityRightText = view.findViewById(R.id.lcr_proximity_right_text)
        
        // 重置按钮
        volumeResetBtn = view.findViewById(R.id.lcr_volume_reset)
        proximityResetBtn = view.findViewById(R.id.lcr_proximity_reset)
        
        // 进度
        progressBar = view.findViewById(R.id.lcr_progress_bar)
        timeText = view.findViewById(R.id.lcr_time_text)
        
        // 状态
        statusText = view.findViewById(R.id.lcr_status_text)
    }
    
    private fun setupListeners() {
        Log.d(TAG, "设置监听器")
        
        // 文件选择按钮
        leftSelectBtn.setOnClickListener { 
            Log.d(TAG, "点击 Left 选择按钮")
            selectFile(LcrChannelType.LEFT) 
        }
        centerSelectBtn.setOnClickListener { 
            Log.d(TAG, "点击 Center 选择按钮")
            selectFile(LcrChannelType.CENTER) 
        }
        rightSelectBtn.setOnClickListener { 
            Log.d(TAG, "点击 Right 选择按钮")
            selectFile(LcrChannelType.RIGHT) 
        }
        
        // 清除按钮
        leftClearBtn.setOnClickListener { 
            Log.d(TAG, "点击 Left 清除按钮")
            clearChannel(LcrChannelType.LEFT) 
        }
        centerClearBtn.setOnClickListener { 
            Log.d(TAG, "点击 Center 清除按钮")
            clearChannel(LcrChannelType.CENTER) 
        }
        rightClearBtn.setOnClickListener { 
            Log.d(TAG, "点击 Right 清除按钮")
            clearChannel(LcrChannelType.RIGHT) 
        }
        
        // 反转按钮
        leftInvertBtn.setOnClickListener { 
            Log.d(TAG, "点击 Left Invert 按钮")
            toggleInvert(LcrChannelType.LEFT) 
        }
        rightInvertBtn.setOnClickListener { 
            Log.d(TAG, "点击 Right Invert 按钮")
            toggleInvert(LcrChannelType.RIGHT) 
        }
        
        // 播放模式
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.lcr_mode_separate -> LcrPlayMode.SEPARATE
                R.id.lcr_mode_left -> LcrPlayMode.LEFT_ONLY
                R.id.lcr_mode_right -> LcrPlayMode.RIGHT_ONLY
                R.id.lcr_mode_center -> LcrPlayMode.CENTER_ONLY
                else -> LcrPlayMode.SEPARATE
            }
            Log.i(TAG, "切换播放模式: $mode")
            player.setPlayMode(mode)
            setStatus("播放模式: ${mode.displayName}", StatusColor.BLUE)
        }
        
        // Switch 按钮
        switchButton.setOnClickListener {
            val isSwitched = player.toggleSwitch()
            Log.i(TAG, "切换 Switch: $isSwitched")
            if (isSwitched) {
                switchStatus.text = "(已交换)"
                switchStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                setStatus("Switch: 左右声道输出已交换", StatusColor.BLUE)
            } else {
                switchStatus.text = "(正常)"
                switchStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
                setStatus("Switch: 左右声道输出正常", StatusColor.BLUE)
            }
        }
        
        // 播放/暂停按钮
        playButton.setOnClickListener {
            Log.d(TAG, "点击播放按钮")
            if (!hasAnyAudio()) {
                Log.w(TAG, "没有可播放的音频")
                setStatus("请至少选择一个音频文件", StatusColor.RED)
                return@setOnClickListener
            }
            
            val state = player.playbackState.value
            Log.d(TAG, "当前状态: isPlaying=${state.isPlaying}, isPaused=${state.isPaused}")
            
            if (!state.isPlaying) {
                Log.i(TAG, "开始播放")
                player.play()
                updatePlayButton(true)
                setStatus("播放中 - 模式: ${player.playMode.displayName}", StatusColor.GREEN)
            } else if (state.isPaused) {
                Log.i(TAG, "恢复播放")
                player.pause()
                updatePlayButton(true)
                setStatus("播放中 - 模式: ${player.playMode.displayName}", StatusColor.GREEN)
            } else {
                Log.i(TAG, "暂停播放")
                player.pause()
                updatePlayButton(false, paused = true)
                setStatus("已暂停", StatusColor.ORANGE)
            }
        }
        
        // 停止按钮
        stopButton.setOnClickListener {
            Log.i(TAG, "点击停止按钮")
            player.stop()
            updatePlayButton(false)
            progressBar.progress = 0
            timeText.text = "00:00 / 00:00"
            setStatus("已停止", StatusColor.RED)
        }
        
        // 音量控制
        setupVolumeSeekBar(volumeLeftSeekBar, volumeLeftText, LcrChannelType.LEFT)
        setupVolumeSeekBar(volumeCenterSeekBar, volumeCenterText, LcrChannelType.CENTER)
        setupVolumeSeekBar(volumeRightSeekBar, volumeRightText, LcrChannelType.RIGHT)
        
        // 距离感控制
        setupProximitySeekBar(proximityLeftSeekBar, proximityLeftText, LcrChannelType.LEFT)
        setupProximitySeekBar(proximityCenterSeekBar, proximityCenterText, LcrChannelType.CENTER)
        setupProximitySeekBar(proximityRightSeekBar, proximityRightText, LcrChannelType.RIGHT)
        
        // 重置按钮
        volumeResetBtn.setOnClickListener {
            Log.i(TAG, "重置音量")
            resetVolume()
            setStatus("音量已重置为 100%", StatusColor.BLUE)
        }
        
        proximityResetBtn.setOnClickListener {
            Log.i(TAG, "重置距离感")
            resetProximity()
            setStatus("距离感已重置为 50%", StatusColor.BLUE)
        }
        
        // 倍速按钮
        speed1xBtn.setOnClickListener { setSpeed(1.0f) }
        speed125xBtn.setOnClickListener { setSpeed(1.25f) }
        speed15xBtn.setOnClickListener { setSpeed(1.5f) }
        speed175xBtn.setOnClickListener { setSpeed(1.75f) }
        speed2xBtn.setOnClickListener { setSpeed(2.0f) }
        
        // 倍速滑动条 (0-150 对应 0.5-2.0)
        speedSeekBar.max = 150
        speedSeekBar.progress = 50  // 默认 1.0x
        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val speed = 0.5f + progress / 100f  // 0->0.5, 150->2.0
                    setSpeed(speed)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 循环播放
        loopCheckBox.setOnCheckedChangeListener { _, isChecked ->
            Log.i(TAG, "切换循环播放: $isChecked")
            player.setLooping(isChecked)
            setStatus(if (isChecked) "循环播放: 开" else "循环播放: 关", StatusColor.BLUE)
        }
        
        // 进度条
        progressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player.getMinDuration()
                    val position = progress / 1000f * duration
                    Log.d(TAG, "拖动进度条: progress=$progress, position=${position}s")
                    player.seek(position)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun setupVolumeSeekBar(seekBar: SeekBar, textView: TextView, channel: LcrChannelType) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    Log.d(TAG, "调整音量: $channel = $progress%")
                    player.setVolume(channel, progress / 100f)
                    textView.text = "$progress%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun setupProximitySeekBar(seekBar: SeekBar, textView: TextView, channel: LcrChannelType) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    Log.d(TAG, "调整距离感: $channel = $progress%")
                    player.setProximity(channel, progress / 100f)
                    textView.text = "$progress%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun resetVolume() {
        // 重置为 100%
        volumeLeftSeekBar.progress = 100
        volumeCenterSeekBar.progress = 100
        volumeRightSeekBar.progress = 100
        volumeLeftText.text = "100%"
        volumeCenterText.text = "100%"
        volumeRightText.text = "100%"
        player.setVolume(LcrChannelType.LEFT, 1.0f)
        player.setVolume(LcrChannelType.CENTER, 1.0f)
        player.setVolume(LcrChannelType.RIGHT, 1.0f)
    }
    
    private fun resetProximity() {
        // 重置为 50%（中性）
        proximityLeftSeekBar.progress = 50
        proximityCenterSeekBar.progress = 50
        proximityRightSeekBar.progress = 50
        proximityLeftText.text = "50%"
        proximityCenterText.text = "50%"
        proximityRightText.text = "50%"
        player.setProximity(LcrChannelType.LEFT, 0.5f)
        player.setProximity(LcrChannelType.CENTER, 0.5f)
        player.setProximity(LcrChannelType.RIGHT, 0.5f)
    }
    
    private fun setSpeed(speed: Float) {
        val clampedSpeed = speed.coerceIn(0.5f, 2.0f)
        Log.i(TAG, "设置播放速度: ${String.format("%.2f", clampedSpeed)}x")
        player.setSpeed(clampedSpeed)
        speedText.text = "${String.format("%.2f", clampedSpeed)}x"
        // 更新滑动条位置 (0.5-2.0 对应 0-150)
        val progress = ((clampedSpeed - 0.5f) * 100).toInt().coerceIn(0, 150)
        speedSeekBar.progress = progress
        setStatus("播放速度: ${String.format("%.2f", clampedSpeed)}x", StatusColor.BLUE)
    }
    
    private fun observePlaybackState() {
        lifecycleScope.launch {
            player.playbackState.collectLatest { state ->
                // 更新进度条（Seeking 时禁用）
                progressBar.isEnabled = !state.isSeeking
                
                if (state.durationSeconds > 0 && !state.isSeeking) {
                    val progress = (state.positionSeconds / state.durationSeconds * 1000).toInt()
                    progressBar.progress = progress
                }
                
                // 更新时间显示
                val memInfo = String.format("%.1f", state.memoryUsageMB)
                val timeStr = formatTime(state.positionSeconds) + " / " + formatTime(state.durationSeconds)
                
                if (state.isSeeking) {
                    timeText.text = "$timeStr | 加载中..."
                    setStatus("正在加载缓冲区...", StatusColor.ORANGE)
                } else {
                    timeText.text = "$timeStr | ${memInfo}MB"
                }
            }
        }
    }
    
    private fun selectFile(channel: LcrChannelType) {
        Log.i(TAG, "打开文件选择器: $channel")
        pendingChannelType = channel
        
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        filePickerLauncher.launch(intent)
    }
    
    private fun loadAudioFile(uri: Uri, channel: LcrChannelType) {
        val fileName = getFileName(uri)
        Log.i(TAG, "开始加载音频: channel=$channel, fileName=$fileName, uri=$uri")
        
        lifecycleScope.launch {
            try {
                setStatus("正在加载: $fileName...", StatusColor.BLUE)
                
                // 获取持久化权限
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    Log.d(TAG, "获取持久化权限成功")
                } catch (e: Exception) {
                    Log.w(TAG, "获取持久化权限失败: ${e.message}")
                }
                
                val startTime = System.currentTimeMillis()
                
                // L/R 联动：选择 L 时自动加载 R，反之亦然
                when (channel) {
                    LcrChannelType.LEFT -> {
                        player.loadAudio(uri, LcrChannelType.LEFT, fileName)
                        // 如果 R 为空，自动加载同一文件到 R
                        if (!player.rightInfo.hasData()) {
                            Log.i(TAG, "L/R 联动: 自动加载 R")
                            player.loadAudio(uri, LcrChannelType.RIGHT, fileName)
                        }
                    }
                    LcrChannelType.RIGHT -> {
                        player.loadAudio(uri, LcrChannelType.RIGHT, fileName)
                        // 如果 L 为空，自动加载同一文件到 L
                        if (!player.leftInfo.hasData()) {
                            Log.i(TAG, "L/R 联动: 自动加载 L")
                            player.loadAudio(uri, LcrChannelType.LEFT, fileName)
                        }
                    }
                    LcrChannelType.CENTER -> {
                        player.loadAudio(uri, LcrChannelType.CENTER, fileName)
                    }
                }
                
                val loadTime = System.currentTimeMillis() - startTime
                Log.i(TAG, "音频加载成功: channel=$channel, 耗时=${loadTime}ms")
                
                // 更新 Left UI
                if (player.leftInfo.hasData()) {
                    leftFileContainer.visibility = View.VISIBLE
                    leftPlaceholder.visibility = View.GONE
                    leftFileName.text = player.leftInfo.fileName ?: ""
                    leftDuration.text = formatTime(player.leftInfo.duration)
                    leftClearBtn.isEnabled = true
                    leftInvertBtn.isEnabled = true
                }
                
                // 更新 Center UI
                if (player.centerInfo.hasData()) {
                    centerFileContainer.visibility = View.VISIBLE
                    centerPlaceholder.visibility = View.GONE
                    centerFileName.text = player.centerInfo.fileName ?: ""
                    centerDuration.text = formatTime(player.centerInfo.duration)
                    centerClearBtn.isEnabled = true
                }
                
                // 更新 Right UI
                if (player.rightInfo.hasData()) {
                    rightFileContainer.visibility = View.VISIBLE
                    rightPlaceholder.visibility = View.GONE
                    rightFileName.text = player.rightInfo.fileName ?: ""
                    rightDuration.text = formatTime(player.rightInfo.duration)
                    rightClearBtn.isEnabled = true
                    rightInvertBtn.isEnabled = true
                }
                
                val memMB = String.format("%.1f", player.getTotalMemoryUsageMB())
                val loadedChannels = mutableListOf<String>()
                if (player.leftInfo.hasData()) loadedChannels.add("L")
                if (player.rightInfo.hasData()) loadedChannels.add("R")
                if (player.centerInfo.hasData()) loadedChannels.add("C")
                setStatus("✓ ${loadedChannels.joinToString("+")} 已加载 (${memMB}MB)", StatusColor.GREEN)
                
            } catch (e: Exception) {
                Log.e(TAG, "音频加载失败: channel=$channel", e)
                setStatus("加载失败: ${e.message}", StatusColor.RED)
            }
        }
    }
    
    private fun clearChannel(channel: LcrChannelType) {
        Log.i(TAG, "清除声道: $channel")
        player.clearAudio(channel)
        
        when (channel) {
            LcrChannelType.LEFT -> {
                leftFileContainer.visibility = View.GONE
                leftPlaceholder.visibility = View.VISIBLE
                leftFileName.text = ""
                leftDuration.text = ""
                leftClearBtn.isEnabled = false
                leftInvertBtn.isEnabled = false
                updateInvertButton(leftInvertBtn, false)
            }
            LcrChannelType.CENTER -> {
                centerFileContainer.visibility = View.GONE
                centerPlaceholder.visibility = View.VISIBLE
                centerFileName.text = ""
                centerDuration.text = ""
                centerClearBtn.isEnabled = false
            }
            LcrChannelType.RIGHT -> {
                rightFileContainer.visibility = View.GONE
                rightPlaceholder.visibility = View.VISIBLE
                rightFileName.text = ""
                rightDuration.text = ""
                rightClearBtn.isEnabled = false
                rightInvertBtn.isEnabled = false
                updateInvertButton(rightInvertBtn, false)
            }
        }
        
        setStatus("${channel.name} 已清除", StatusColor.ORANGE)
    }
    
    private fun toggleInvert(channel: LcrChannelType) {
        Log.i(TAG, "切换 Invert: $channel")
        
        lifecycleScope.launch {
            try {
                val inverted = player.toggleInvert(channel)
                Log.i(TAG, "Invert 结果: $channel = $inverted")
                
                val btn = if (channel == LcrChannelType.LEFT) leftInvertBtn else rightInvertBtn
                updateInvertButton(btn, inverted)
                
                val statusMsg = if (inverted) {
                    "${channel.name} 已反转: 提取${if (channel == LcrChannelType.LEFT) "Right" else "Left"}声道"
                } else {
                    "${channel.name} 正常: 提取${channel.name}声道"
                }
                setStatus(statusMsg, StatusColor.BLUE)
                
            } catch (e: Exception) {
                Log.e(TAG, "Invert 失败: $channel", e)
                setStatus("反转失败: ${e.message}", StatusColor.RED)
            }
        }
    }
    
    private fun updateInvertButton(btn: Button, inverted: Boolean) {
        if (inverted) {
            btn.text = "Inverted"
            btn.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
        } else {
            btn.text = "Invert"
            btn.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
        }
    }
    
    private fun updatePlayButton(playing: Boolean, paused: Boolean = false) {
        playButton.text = when {
            !playing && !paused -> "播放"
            playing && !paused -> "暂停"
            paused -> "继续"
            else -> "播放"
        }
    }
    
    private fun hasAnyAudio(): Boolean {
        val has = player.leftInfo.hasData() || 
                  player.centerInfo.hasData() || 
                  player.rightInfo.hasData()
        Log.d(TAG, "hasAnyAudio: $has (L=${player.leftInfo.hasData()}, C=${player.centerInfo.hasData()}, R=${player.rightInfo.hasData()})")
        return has
    }
    
    private fun getFileName(uri: Uri): String {
        var name = "unknown"
        try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = cursor.getString(index)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取文件名失败", e)
        }
        return name
    }
    
    private fun formatTime(seconds: Float): String {
        val totalSeconds = seconds.toInt()
        val mins = totalSeconds / 60
        val secs = totalSeconds % 60
        return String.format("%02d:%02d", mins, secs)
    }
    
    private enum class StatusColor {
        GREEN, BLUE, ORANGE, RED
    }
    
    private fun setStatus(message: String, color: StatusColor) {
        Log.d(TAG, "状态: $message")
        statusText.text = message
        val colorRes = when (color) {
            StatusColor.GREEN -> android.R.color.holo_green_dark
            StatusColor.BLUE -> android.R.color.holo_blue_dark
            StatusColor.ORANGE -> android.R.color.holo_orange_dark
            StatusColor.RED -> android.R.color.holo_red_dark
        }
        statusText.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
    }
}
