package com.personal.voicemail.recognition

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.personal.voicemail.audio.GreetingGenerator
import com.personal.voicemail.data.VoicemailEntity
import com.personal.voicemail.data.VoicemailRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Real-time Offline Speech-to-Text Transcriber and WAV Audio Recorder.
 *
 * Consumes 16kHz PCM chunks emitted by the privileged VoicemailUserService through a Kotlin
 * Channel on a single-threaded dispatcher (Dispatchers.Default.limitedParallelism(1)).
 *
 * 1. Writes raw 16-bit little-endian PCM bytes to disk in real time.
 * 2. Normalizes samples to [-1.0f, 1.0f] and passes them to Sherpa-ONNX OnlineRecognizer.
 * 3. Decodes speech frames and detects sentence endpoints.
 * 4. Emits real-time accumulated transcripts to Compose UI via StateFlow.
 * 5. Upon session termination, prepends a 44-byte RIFF/WAVE header and stores the record in Room.
 */
class LiveVoicemailTranscriber(
    private val context: Context,
    private val repository: VoicemailRepository = VoicemailRepository.getInstance(context)
) {

    companion object {
        private const val TAG = "LiveTranscriber"
        private const val SAMPLE_RATE_HZ = 16000
        private const val MODEL_DIR = "model"
        private const val RECORDINGS_DIR = "recordings"

        @Volatile
        private var INSTANCE: LiveVoicemailTranscriber? = null

        fun getInstance(context: Context): LiveVoicemailTranscriber {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LiveVoicemailTranscriber(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val transcriberScope = CoroutineScope(Dispatchers.Default)
    private val processingDispatcher = Dispatchers.Default.limitedParallelism(1)

    private var recognizer: OnlineRecognizer? = null
    private var isModelInitialized = false

    // Session State
    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _isScreeningActive = MutableStateFlow(false)
    val isScreeningActive: StateFlow<Boolean> = _isScreeningActive.asStateFlow()

    private val _audioRmsLevel = MutableStateFlow(0f)
    val audioRmsLevel: StateFlow<Float> = _audioRmsLevel.asStateFlow()

    private var activeStream: OnlineStream? = null
    private var pcmChannel: Channel<ShortArray>? = null
    private var consumerJob: Job? = null

    private var tempPcmFile: File? = null
    private var tempPcmOutputStream: FileOutputStream? = null

    private var sessionStartTimeMs: Long = 0
    private var currentCallerName: String? = null
    private var currentPhoneNumber: String = ""
    private var currentSimDisplayName: String = "SIM 1"
    private var currentSimSlotIndex: Int = 0

    private val accumulatedSentences = StringBuilder()
    private val isStopping = AtomicBoolean(false)

    init {
        initializeRecognizer()
    }

    fun isReady(): Boolean = isModelInitialized

    /**
     * Initializes the Sherpa-ONNX offline streaming transducer engine.
     * Copies model files from assets to internal storage and passes absolute paths
     * with assetManager = null to prevent AAssetManager native crashes on Android 14/15.
     */
    private fun initializeRecognizer() {
        transcriberScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting Sherpa-ONNX Zipformer model extraction and initialization...")
                val targetDir = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
                val requiredFiles = listOf(
                    "encoder.int8.onnx",
                    "decoder.onnx",
                    "joiner.onnx",
                    "tokens.txt"
                )
                val modelFiles = mutableMapOf<String, File>()

                for (name in requiredFiles) {
                    val dest = File(targetDir, name)
                    // If file doesn't exist or is empty, copy from assets
                    if (!dest.exists() || dest.length() <= 0L) {
                        try {
                            Log.d(TAG, "Extracting asset $MODEL_DIR/$name -> ${dest.absolutePath}")
                            context.assets.open("$MODEL_DIR/$name").use { input ->
                                FileOutputStream(dest).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            Log.d(TAG, "Extracted $name (${dest.length()} bytes)")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed extracting $name from assets: ${e.message}")
                        }
                    } else {
                        Log.d(TAG, "Model file $name already exists (${dest.length()} bytes)")
                    }
                    if (dest.exists() && dest.length() > 0L) {
                        modelFiles[name] = dest
                    }
                }

                val encoderFile = modelFiles["encoder.int8.onnx"]
                val decoderFile = modelFiles["decoder.onnx"]
                val joinerFile = modelFiles["joiner.onnx"]
                val tokensFile = modelFiles["tokens.txt"]

                if (encoderFile != null && decoderFile != null && joinerFile != null && tokensFile != null) {
                    Log.d(TAG, "Configuring Zipformer transducer...")

                    val transducerConfig = OnlineTransducerModelConfig(
                        encoder = encoderFile.absolutePath,
                        decoder = decoderFile.absolutePath,
                        joiner = joinerFile.absolutePath
                    )

                    val modelConfig = OnlineModelConfig(
                        transducer = transducerConfig,
                        tokens = tokensFile.absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                        debug = false
                    )

                    val recognizerConfig = OnlineRecognizerConfig(
                        modelConfig = modelConfig,
                        decodingMethod = "greedy_search",
                        enableEndpoint = true
                    )

                    Log.d(TAG, "Instantiating OnlineRecognizer native instance...")
                    recognizer = OnlineRecognizer(
                        assetManager = null,
                        config = recognizerConfig
                    )
                    isModelInitialized = true
                    Log.i(TAG, "Sherpa-ONNX OnlineRecognizer initialized successfully from filesDir!")
                } else {
                    val missing = requiredFiles.filterNot { modelFiles.containsKey(it) }
                    Log.e(TAG, "Sherpa-ONNX model files incomplete in $MODEL_DIR. Missing: $missing")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Exception initializing Sherpa-ONNX OnlineRecognizer: ${t.message}", t)
                recognizer = null
                isModelInitialized = false
            }
        }
    }

    /**
     * Starts a live transcription and recording session for an incoming call.
     */
    @Synchronized
    fun startSession(
        callerName: String?,
        phoneNumber: String,
        simDisplayName: String,
        simSlotIndex: Int
    ) {
        currentCallerName = callerName
        currentPhoneNumber = phoneNumber
        currentSimDisplayName = simDisplayName
        currentSimSlotIndex = simSlotIndex
        sessionStartTimeMs = System.currentTimeMillis()

        accumulatedSentences.clear()
        _liveTranscript.value = ""
        _audioRmsLevel.value = 0f
        _isScreeningActive.value = true
        isStopping.set(false)

        val recordingsDir = File(context.filesDir, RECORDINGS_DIR).apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        val baseFileName = "voicemail_${cleanNumber}_$timeStamp"

        tempPcmFile = File(recordingsDir, "$baseFileName.pcm")
        tempPcmOutputStream = FileOutputStream(tempPcmFile!!)

        // Allocate unbounded channel for high-throughput 16kHz PCM short arrays
        val channel = Channel<ShortArray>(Channel.UNLIMITED)
        pcmChannel = channel

        // Create Sherpa-ONNX streaming instance
        activeStream = recognizer?.createStream()

        // Launch consumer coroutine on single-threaded dispatcher
        consumerJob = transcriberScope.launch(processingDispatcher) {
            consumeAudioChunks(channel)
        }

        Log.i(TAG, "Live Voicemail transcription session commenced for $phoneNumber")
    }

    /**
     * Consumer loop: receives 16kHz PCM chunks, writes raw bytes, feeds Sherpa-ONNX stream.
     */
    private suspend fun consumeAudioChunks(channel: Channel<ShortArray>) {
        val stream = activeStream
        val rec = recognizer
        val byteBuffer = ByteBuffer.allocate(1600 * 2).order(ByteOrder.LITTLE_ENDIAN)

        for (chunk in channel) {
            // 1. Write raw PCM to temporary disk file
            byteBuffer.clear()
            for (sample in chunk) {
                byteBuffer.putShort(sample)
            }
            try {
                tempPcmOutputStream?.write(byteBuffer.array(), 0, byteBuffer.position())
            } catch (e: Exception) {
                Log.e(TAG, "Error writing raw PCM chunk to disk", e)
            }

            // 2. Calculate RMS Audio Level for real-time visual waveform animation
            val rms = calculateRms(chunk)
            _audioRmsLevel.value = rms

            // 3. Normalize samples to FloatArray [-1.0f, 1.0f]
            val floatSamples = FloatArray(chunk.size)
            for (i in chunk.indices) {
                floatSamples[i] = chunk[i] / 32768.0f
            }

            // 4. Feed Sherpa-ONNX OnlineStream
            if (rec != null && stream != null) {
                stream.acceptWaveform(floatSamples, SAMPLE_RATE_HZ)

                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }

                val currentResult = rec.getResult(stream)
                val partialText = currentResult.text.trim()

                if (rec.isEndpoint(stream)) {
                    if (partialText.isNotEmpty()) {
                        if (accumulatedSentences.isNotEmpty()) {
                            accumulatedSentences.append(" ")
                        }
                        accumulatedSentences.append(partialText)
                    }
                    rec.reset(stream)
                    _liveTranscript.value = accumulatedSentences.toString()
                } else {
                    val fullDisplay = if (accumulatedSentences.isEmpty()) {
                        partialText
                    } else if (partialText.isEmpty()) {
                        accumulatedSentences.toString()
                    } else {
                        "$accumulatedSentences $partialText"
                    }
                    _liveTranscript.value = fullDisplay
                }
            } else {
                // Fallback simulation when model weights are not loaded: speech activity indicators
                if (rms > 0.15f) {
                    val current = _liveTranscript.value
                    if (!current.contains("…")) {
                        _liveTranscript.value = if (current.isEmpty()) "Speaking…" else "$current …"
                    }
                }
            }
        }

        Log.i(TAG, "Consumer coroutine completed chunk processing loop.")
    }

    /**
     * Submits a chunk of 16kHz linear PCM samples received via AIDL from VoicemailUserService.
     */
    fun submitPcmChunk(pcm16kHzSamples: ShortArray) {
        if (!isStopping.get()) {
            pcmChannel?.trySend(pcm16kHzSamples)
        }
    }

    /**
     * Finalizes the voicemail session:
     * - Shuts down audio channel and awaits consumer coroutine completion
     * - Wraps PCM file with 44-byte RIFF/WAVE header
     * - Stores VoicemailEntity in Room database (if saveToRepository == true)
     */
    suspend fun stopSession(isPickedUp: Boolean, saveToRepository: Boolean = true): VoicemailEntity? = withContext(Dispatchers.IO) {
        if (!_isScreeningActive.value) {
            return@withContext null
        }
        isStopping.set(true)
        _isScreeningActive.value = false

        // Close channel and wait for background consumer to finish writing
        pcmChannel?.close()
        consumerJob?.join()
        pcmChannel = null
        consumerJob = null

        // Release Sherpa stream
        try {
            activeStream?.let { s ->
                recognizer?.let { r ->
                    // Flush any remaining samples
                    while (r.isReady(s)) {
                        r.decode(s)
                    }
                    val finalResult = r.getResult(s)
                    if (finalResult.text.isNotBlank()) {
                        if (accumulatedSentences.isNotEmpty()) {
                            accumulatedSentences.append(" ")
                        }
                        accumulatedSentences.append(finalResult.text.trim())
                    }
                }
                s.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing Sherpa-ONNX stream", t)
        } finally {
            activeStream = null
        }

        // Close PCM output stream
        try {
            tempPcmOutputStream?.flush()
            tempPcmOutputStream?.close()
        } catch (ignored: Throwable) {
        } finally {
            tempPcmOutputStream = null
        }

        val pcm = tempPcmFile
        tempPcmFile = null

        if (!saveToRepository) {
            pcm?.delete()
            Log.i(TAG, "stopSession executed with saveToRepository=false (Self-Test mode). Temp files purged.")
            return@withContext null
        }

        // Convert raw .pcm to standard playable .wav
        val recordingsDir = File(context.filesDir, RECORDINGS_DIR).apply { mkdirs() }
        val cleanNumber = currentPhoneNumber.replace(Regex("[^0-9+]"), "").ifEmpty { "unknown" }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(sessionStartTimeMs))
        val finalWavFile = File(recordingsDir, "voicemail_${cleanNumber}_$timeStamp.wav")

        val pcmLength = pcm?.length() ?: 0L
        if (pcm != null && pcm.exists() && pcmLength > 0L) {
            try {
                FileOutputStream(finalWavFile).use { fos ->
                    GreetingGenerator.writeWavHeader(
                        out = fos,
                        totalAudioLen = pcmLength,
                        sampleRate = SAMPLE_RATE_HZ,
                        channels = 1,
                        bitsPerSample = 16
                    )
                    FileInputStream(pcm).use { fis ->
                        fis.copyTo(fos)
                    }
                    fos.flush()
                }
                pcm.delete()
                Log.i(TAG, "Produced final WAV: ${finalWavFile.absolutePath} (${finalWavFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed creating WAV container from raw PCM file", e)
            }
        } else {
            // Guarantee a valid 1-second WAV file exists even if call disconnected immediately
            try {
                val silenceBytes = ByteArray(SAMPLE_RATE_HZ * 2)
                FileOutputStream(finalWavFile).use { fos ->
                    GreetingGenerator.writeWavHeader(
                        out = fos,
                        totalAudioLen = silenceBytes.size.toLong(),
                        sampleRate = SAMPLE_RATE_HZ,
                        channels = 1,
                        bitsPerSample = 16
                    )
                    fos.write(silenceBytes)
                    fos.flush()
                }
                pcm?.delete()
                Log.i(TAG, "Produced baseline silence WAV: ${finalWavFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed creating fallback WAV container", e)
            }
        }

        val durationSec = kotlin.math.max(1, (pcmLength / (SAMPLE_RATE_HZ * 2L)).toInt())
        val finalTranscript = accumulatedSentences.toString().trim()
            .ifEmpty { if (isPickedUp) "" else "(Voice message recorded — tap Play to listen)" }

        val entity = VoicemailEntity(
            callerName = currentCallerName,
            phoneNumber = currentPhoneNumber,
            simSlotIndex = currentSimSlotIndex,
            simDisplayName = currentSimDisplayName,
            timestamp = sessionStartTimeMs,
            durationSeconds = durationSec,
            transcript = finalTranscript,
            audioFilePath = finalWavFile.absolutePath,
            isRead = false,
            isPickedUp = isPickedUp
        )

        // Persist to database & update StateFlow
        val id = repository.addVoicemail(entity)
        val persisted = entity.copy(id = id)

        _liveTranscript.value = finalTranscript
        _audioRmsLevel.value = 0f

        Log.i(TAG, "Voicemail recording saved successfully (ID: $id, Duration: ${durationSec}s, Caller: $currentPhoneNumber)")
        persisted
    }

    private fun calculateRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (sample in samples) {
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
        }
        val meanSquare = sumSquares / samples.size
        return sqrt(meanSquare).toFloat().coerceIn(0f, 1f)
    }
}
