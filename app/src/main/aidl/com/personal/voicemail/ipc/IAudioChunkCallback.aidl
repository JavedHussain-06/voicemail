package com.personal.voicemail.ipc;

/**
 * Asynchronous callback invoked by the privileged Shizuku VoicemailUserService
 * back to the main application process.
 */
interface IAudioChunkCallback {
    /**
     * Emits a sequential buffer of 16,000 Hz, 16-bit Mono linear PCM byte data
     * (little-endian 16-bit samples) captured directly from the telephony downlink stream.
     */
    oneway void onPcmChunkReceived(in byte[] pcm16kHzBytes);

    /**
     * Signals that the greeting WAV playback injected into the telephony uplink
     * has completed, and caller speech capture has commenced.
     */
    oneway void onGreetingFinished();

    /**
     * Reports an audio pipeline or hardware routing failure.
     */
    oneway void onError(String message);
}
