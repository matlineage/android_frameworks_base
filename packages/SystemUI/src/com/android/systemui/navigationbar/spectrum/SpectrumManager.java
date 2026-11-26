/*
 * Copyright (C) 2025 LineageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.navigationbar.spectrum;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Manages loopback audio capture via {@link AudioManager#registerAudioPolicy(AudioPolicy)} with
 * an {@link AudioMix} using {@link AudioMix#ROUTE_FLAG_LOOP_BACK_RENDER}. This captures playback
 * output without stealing the output route — audio keeps playing to the speaker/headphones/
 * Bluetooth while a copy is fed to the {@link SpectrumView}. Tied to the navigation bar lifecycle.
 */
public class SpectrumManager {
    private static final String TAG = "SpectrumManager";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO; // output channel mask
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT;
    private static final int BUFFER_SIZE = 1024;

    private final Context mContext;
    private final Handler mHandler;
    private final AudioManager mAudioManager;

    private SpectrumView mSpectrumView;
    private AudioRecord mAudioRecord;
    private Thread mAudioThread;
    private boolean mIsRecording = false;
    private AudioPolicy mAudioPolicy;
    private AudioMix mAudioMix;
    private boolean mUseMicrophone = false;

    /** Create a new manager tied to a nav bar context. */
    public SpectrumManager(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mAudioManager = context.getSystemService(AudioManager.class);
    }

    /**
     * Attach a reference to the SpectrumView this manager will update.
     */
    public void setSpectrumView(SpectrumView view) {
        mSpectrumView = view;
    }

    public void setUseMicrophone(boolean useMicrophone) {
        if (mUseMicrophone == useMicrophone) {
            return;
        }
        mUseMicrophone = useMicrophone;
        if (mIsRecording) {
            stop();
            start();
        }
    }

    /**
     * Start capturing audio for the spectrum. By default captures media playback via an AudioPolicy
     * loopback mix; when microphone mode is enabled, captures microphone input instead.
     */
    public void start() {
        Log.d(TAG, "start(): mIsRecording=" + mIsRecording + " mSpectrumView=" + mSpectrumView
                + " mUseMicrophone=" + mUseMicrophone);
        if (mIsRecording) return;
        if (mSpectrumView == null) return;
        if (mAudioManager == null) {
            Log.e(TAG, "AudioManager is null");
            return;
        }

        if (mUseMicrophone) {
            startMicrophoneCapture();
        } else {
            startMediaCapture();
        }
    }

    private void startMediaCapture() {
        Log.d(TAG, "Starting spectrum capture via AudioPolicy loopback mix");

        AudioAttributes mediaAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build();
        AudioMixingRule rule = new AudioMixingRule.Builder()
                .addRule(mediaAttrs, AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE)
                .setTargetMixRole(AudioMixingRule.MIX_ROLE_PLAYERS)
                .build();

        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build();
        mAudioMix = new AudioMix.Builder(rule)
                .setFormat(format)
                .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK | AudioMix.ROUTE_FLAG_RENDER)
                .build();

        mAudioPolicy = new AudioPolicy.Builder(mContext)
                .setLooper(mHandler.getLooper())
                .addMix(mAudioMix)
                .build();

        int regResult = mAudioManager.registerAudioPolicy(mAudioPolicy);
        if (regResult != AudioManager.SUCCESS) {
            Log.e(TAG, "Failed to register audio policy, result=" + regResult);
            mAudioPolicy = null;
            mAudioMix = null;
            return;
        }

        mAudioRecord = mAudioPolicy.createAudioRecordSink(mAudioMix);
        if (mAudioRecord == null) {
            Log.e(TAG, "Failed to create AudioRecord sink for audio mix");
            unregisterAudioPolicy();
            return;
        }

        if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized, state=" + mAudioRecord.getState());
            releaseAudioRecord();
            unregisterAudioPolicy();
            return;
        }

        startCaptureThread();
    }

    private void startMicrophoneCapture() {
        Log.d(TAG, "Starting spectrum capture via microphone");

        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AUDIO_FORMAT);
        int bufferSize = Math.max(minBuffer, BUFFER_SIZE * 4);
        try {
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT, bufferSize);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create microphone AudioRecord: " + e.getMessage(), e);
            mAudioRecord = null;
            return;
        }

        if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Microphone AudioRecord not initialized, state="
                    + mAudioRecord.getState());
            releaseAudioRecord();
            return;
        }

        startCaptureThread();
    }

    private void startCaptureThread() {
        try {
            mAudioRecord.startRecording();
        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to start recording: " + e.getMessage(), e);
            releaseAudioRecord();
            unregisterAudioPolicy();
            return;
        }
        mIsRecording = true;

        mAudioThread = new Thread(new AudioCaptureRunnable(), "spectrum-audio");
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        mAudioThread.start();
    }

    private void releaseAudioRecord() {
        if (mAudioRecord != null) {
            try {
                mAudioRecord.release();
            } catch (IllegalStateException e) {
                // Ignore
            }
            mAudioRecord = null;
        }
    }

    private void unregisterAudioPolicy() {
        if (mAudioPolicy != null) {
            try {
                mAudioManager.unregisterAudioPolicy(mAudioPolicy);
            } catch (Exception e) {
                Log.w(TAG, "Could not unregister audio policy: " + e.getMessage());
            }
            mAudioPolicy = null;
        }
        mAudioMix = null;
    }

    /**
     * Stop recording loopback audio and unregister the audio policy.
     */
    public void stop() {
        if (!mIsRecording) return;

        Log.d(TAG, "Stopping spectrum capture");
        mIsRecording = false;

        if (mAudioRecord != null) {
            try {
                mAudioRecord.stop();
                mAudioRecord.release();
            } catch (IllegalStateException e) {
                // Already stopped or not initialized — ignore
            }
            mAudioRecord = null;
        }

        if (mAudioPolicy != null) {
            try {
                mAudioManager.unregisterAudioPolicy(mAudioPolicy);
            } catch (Exception e) {
                Log.w(TAG, "Could not unregister audio policy: " + e.getMessage());
            }
            mAudioPolicy = null;
        }
        mAudioMix = null;

        if (mAudioThread != null) {
            try {
                mAudioThread.join();
            } catch (InterruptedException e) {
                // Ignore
            }
            mAudioThread = null;
        }
    }

    private final class AudioCaptureRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "AudioCaptureRunnable: starting capture thread");
            if (mAudioRecord == null) return;

            final float[] buffer = new float[BUFFER_SIZE];

            while (mIsRecording && mAudioRecord != null) {
                final int n = mAudioRecord.read(buffer, 0, buffer.length,
                        AudioRecord.READ_BLOCKING);
                if (n <= 0) {
                    Log.w(TAG, "Audio record returned " + n);
                    try {
                        Thread.sleep(16); // ~60 Hz polling
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                // Deliver to main thread for spectrum update via UI post
                if (mSpectrumView != null) {
                    mSpectrumView.update(buffer, n);
                }
            }

            Log.d(TAG, "Audio capture thread exiting");
        }
    }
}
