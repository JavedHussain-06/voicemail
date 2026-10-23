package com.personal.voicemail

import com.personal.voicemail.audio.GreetingGenerator
import com.personal.voicemail.service.TelecomCallManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests verifying audio container formatting, screening state machine,
 * and call management logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoicemailPipelineTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TelecomCallManager.reset()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testWavHeaderGeneration() {
        val out = ByteArrayOutputStream()
        val pcmAudioLength = 32000L // 1 second of 16kHz 16-bit mono PCM

        GreetingGenerator.writeWavHeader(
            out = out,
            totalAudioLen = pcmAudioLength,
            sampleRate = 16000,
            channels = 1,
            bitsPerSample = 16
        )

        val headerBytes = out.toByteArray()
        assertEquals("WAV header must be exactly 44 bytes", 44, headerBytes.size)

        // Verify RIFF and WAVE magic identifiers
        val riff = String(headerBytes, 0, 4)
        val wave = String(headerBytes, 8, 4)
        assertEquals("RIFF", riff)
        assertEquals("WAVE", wave)

        // Verify fmt chunk
        val fmt = String(headerBytes, 12, 4)
        assertEquals("fmt ", fmt)

        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

        // Audio format should be 1 (PCM)
        val audioFormat = buffer.getShort(20).toInt()
        assertEquals("Audio format must be PCM (1)", 1, audioFormat)

        // Channels should be 1 (mono)
        val channels = buffer.getShort(22).toInt()
        assertEquals("Channel count must be 1 (mono)", 1, channels)

        // Sample rate should be 16000
        val sampleRate = buffer.getInt(24)
        assertEquals("Sample rate must be 16000 Hz", 16000, sampleRate)

        // Byte rate should be sampleRate * channels * (bitsPerSample / 8) = 16000 * 1 * 2 = 32000
        val byteRate = buffer.getInt(28)
        assertEquals("Byte rate must be 32000 bytes/sec", 32000, byteRate)

        // Block align should be channels * (bitsPerSample / 8) = 2
        val blockAlign = buffer.getShort(32).toInt()
        assertEquals("Block align must be 2", 2, blockAlign)

        // Bits per sample
        val bitsPerSample = buffer.getShort(34).toInt()
        assertEquals("Bits per sample must be 16", 16, bitsPerSample)

        // data chunk
        val dataChunk = String(headerBytes, 36, 4)
        assertEquals("data", dataChunk)

        val dataSize = buffer.getInt(40)
        assertEquals("Data chunk size must match pcmAudioLength", 32000, dataSize)
    }

    @Test
    fun testTelecomCallManagerScreeningStateFlow() {
        assertEquals(TelecomCallManager.CallScreeningState.IDLE, TelecomCallManager.screeningState.value)

        // 1. Incoming Call Ringing
        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.INCOMING_RINGING)
        assertEquals(TelecomCallManager.CallScreeningState.INCOMING_RINGING, TelecomCallManager.screeningState.value)

        // 2. Answering for Voicemail -> Screening speaker must default to false (Silent screening)
        TelecomCallManager.setScreeningSpeakerStateOnly(true) // artificially enable first
        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.SCREENING_ANSWERING)
        assertFalse("Screening speaker must default to silent in room when screening begins", TelecomCallManager.isScreeningSpeakerOn.value)

        // 3. Greeting Caller
        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.SCREENING_GREETING)
        assertEquals(TelecomCallManager.CallScreeningState.SCREENING_GREETING, TelecomCallManager.screeningState.value)
        assertFalse(TelecomCallManager.isScreeningSpeakerOn.value)

        // 4. Live Voicemail Recording
        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.SCREENING_LIVE_VOICEMAIL)
        assertEquals(TelecomCallManager.CallScreeningState.SCREENING_LIVE_VOICEMAIL, TelecomCallManager.screeningState.value)

        // 5. User enables Listen Live
        TelecomCallManager.setScreeningSpeakerStateOnly(true)
        assertTrue(TelecomCallManager.isScreeningSpeakerOn.value)

        // 6. User picks up call -> ACTIVE_CALL
        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.ACTIVE_CALL)
        assertEquals(TelecomCallManager.CallScreeningState.ACTIVE_CALL, TelecomCallManager.screeningState.value)

        // 7. Disconnected
        TelecomCallManager.setScreeningState(TelecomCallManager.CallScreeningState.DISCONNECTED)
        assertEquals(TelecomCallManager.CallScreeningState.DISCONNECTED, TelecomCallManager.screeningState.value)

        // 8. Reset
        TelecomCallManager.reset()
        assertEquals(TelecomCallManager.CallScreeningState.IDLE, TelecomCallManager.screeningState.value)
        assertFalse(TelecomCallManager.isOnHold.value)
        assertFalse(TelecomCallManager.isMicrophoneMuted.value)
        assertFalse(TelecomCallManager.isSpeakerOn.value)
        assertFalse(TelecomCallManager.isScreeningSpeakerOn.value)
    }

    @Test
    fun testMultiCallStateTracking() {
        TelecomCallManager.reset()
        assertFalse(TelecomCallManager.hasMultipleCalls.value)
        assertFalse(TelecomCallManager.canConference.value)

        // No calls
        TelecomCallManager.updateCallsList(emptyList())
        assertFalse(TelecomCallManager.hasMultipleCalls.value)
        assertFalse(TelecomCallManager.canConference.value)
    }
}
