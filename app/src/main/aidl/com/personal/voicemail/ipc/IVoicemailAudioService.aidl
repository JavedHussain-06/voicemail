package com.personal.voicemail.ipc;

import android.content.res.AssetFileDescriptor;
import com.personal.voicemail.ipc.IAudioChunkCallback;

/**
 * Privileged Audio Routing and Telecom Uplink/Downlink Streaming Service
 * executed under UID 2000 (com.android.shell) via Shizuku.
 */
interface IVoicemailAudioService {
    /**
     * Commences a voicemail screening session:
     * 1. Mutes STREAM_VOICE_CALL to guarantee zero earpiece bleed into the room.
     * 2. Configures Qualcomm DSP incall_music_enabled=true.
     * 3. Injects greetingWavFd into AudioDeviceInfo.TYPE_TELEPHONY uplink.
     * 4. Once greeting completes, begins capturing AudioSource.VOICE_DOWNLINK
     *    and streaming 16kHz PCM chunks back to callback.
     */
    void startVoicemailSession(in AssetFileDescriptor greetingWavFd, IAudioChunkCallback callback);

    /**
     * Enables or disables live room listening of the caller's voice by toggling
     * the mute status of STREAM_VOICE_CALL and directing audio output.
     */
    void setScreeningSpeakerEnabled(boolean enabled);

    /**
     * Stops caller audio capture, unbinds hardware audio streams, un-mutes
     * STREAM_VOICE_CALL, and resets Qualcomm DSP routing flags.
     */
    void stopVoicemailSession();

    /**
     * Destroys the remote Shizuku user service and terminates the daemon process.
     */
    void destroy();
}
