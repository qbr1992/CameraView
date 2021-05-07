package com.sabine.cameraview.video.encoding;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.sabine.cameraview.CameraLogger;
import com.sabine.cameraview.utils.LogUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Default implementation for audio encoding.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class AudioMediaEncoder extends MediaEncoder {

    private static final String TAG = AudioMediaEncoder.class.getSimpleName();
    private static final CameraLogger LOG = CameraLogger.create(TAG);

    private static final boolean PERFORMANCE_DEBUG = false;
    private static final boolean PERFORMANCE_FILL_GAPS = true;
    private static final int PERFORMANCE_MAX_GAPS = 8;

    private boolean mRequestStop = false;
    private AudioEncodingThread mEncoder;
    private ByteBufferPool mByteBufferPool;
    private AudioConfig mConfig;
    private InputBufferPool mInputBufferPool = new InputBufferPool();
    private final LinkedBlockingQueue<InputBuffer> mInputBufferQueue = new LinkedBlockingQueue<>();
    private ByteBuffer mCurrentBuffer;
    private long mTotalBytes;
    private long mLastTimeUs;
    private int mPcmLength;

    // Just to debug performance.
    private int mDebugSendCount = 0;
    private int mDebugExecuteCount = 0;
    private long mDebugSendAvgDelay = 0;
    private long mDebugExecuteAvgDelay = 0;
    private Map<Long, Long> mDebugSendStartMap = new HashMap<>();

    private int mFirstSample = 0;

    public AudioMediaEncoder(@NonNull AudioConfig config) {
        super(NAME_AUDIO);
        mConfig = config.copy();
        mTimestamp = new AudioTimestamp(mConfig.byteRate());
        // These two were in onPrepare() but it's better to do warm-up here
        // since thread and looper creation is expensive.
        mEncoder = new AudioEncodingThread();
        mAudioMediaEncoder = this;
    }

    @EncoderThread
    @Override
    protected void onPrepare(@NonNull MediaEncoderEngine.Controller controller) {
        final MediaFormat audioFormat = MediaFormat.createAudioFormat(
                mConfig.mimeType,
                mConfig.samplingFrequency,
                mConfig.channels);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, mConfig.audioFormatChannels());
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, mConfig.bitRate);
        try {
            if (mConfig.encoder != null) {
                mMediaCodec = MediaCodec.createByCodecName(mConfig.encoder);
            } else {
                mMediaCodec = MediaCodec.createEncoderByType(mConfig.mimeType);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        mMediaCodec.configure(audioFormat, null, null,
                MediaCodec.CONFIGURE_FLAG_ENCODE);
        mMediaCodec.start();
        mFirstSample = 0;
    }

    @EncoderThread
    @Override
    protected void onStart() {
        mRequestStop = false;
        mEncoder.start();
    }

    @EncoderThread
    @Override
    protected void onStop() {
        LogUtil.e(TAG, "stop");
        mRequestStop = true;
    }

    @Override
    protected void onStopped() {
        super.onStopped();
        mRequestStop = false;
        mTotalBytes = 0;
        mLastTimeUs = 0;
        mEncoder = null;
        if (mByteBufferPool != null) {
            mByteBufferPool.clear();
            mByteBufferPool = null;
        }
    }

    @Override
    protected int getEncodedBitRate() {
        return mConfig.bitRate;
    }

    /**
     * Sleeps for some frames duration, to skip them. This can be used to slow down
     * the recording operation to balance it with encoding.
     */
    private void skipFrames(int frames) {
        try {
            Thread.sleep(AudioTimestamp.bytesToMillis(
                    mConfig.frameSize() * frames,
                    mConfig.byteRate()));
        } catch (InterruptedException ignore) {}
    }

    public void putAudioPcm(byte[] pcm, int length, boolean isEndOfStream) {
        if (pcm == null || pcm.length == 0) return;
        if (mByteBufferPool == null) {
            mByteBufferPool = new ByteBufferPool(length, mConfig.bufferPoolMaxSize());
            mPcmLength = length;
        }
        mCurrentBuffer = mByteBufferPool.get();
        if (mCurrentBuffer != null) {
            mCurrentBuffer.clear();
            mCurrentBuffer.put(pcm, 0, length);

            mCurrentBuffer.flip();
            mCurrentBuffer.capacity();

            increaseTime(length);
            if (mLastTimeUs != 0) {
                mTotalBytes += length;
                enqueue(mCurrentBuffer, mLastTimeUs, length, isEndOfStream);
            }
        }
    }

    private void enqueue(@NonNull ByteBuffer byteBuffer,
                             long timestamp, int bufferLength, boolean isEndOfStream) {
        InputBuffer inputBuffer = mInputBufferPool.get();
        //noinspection ConstantConditions
        inputBuffer.source = byteBuffer;
        inputBuffer.timestamp = timestamp;
        inputBuffer.length = bufferLength;
        inputBuffer.isEndOfStream = isEndOfStream;
        mInputBufferQueue.add(inputBuffer);
    }

    private void increaseTime(int readBytes) {
        // Get the latest frame timestamp.
        mLastTimeUs = mTimestamp.increaseUs(readBytes);
    }

    public long getTimestamp() {
        return mTotalBytes / (mConfig.byteRate() / 1000);
    }

    public void setBaseTimeUs(long baseTimeUs) {
        if (mTimestamp != null) mTimestamp.setBaseTimeUs(baseTimeUs);
    }

    public long getBaseTimeUs() {
        if (mTimestamp != null) return  mTimestamp.getBaseTimeUs();
        return 0;
    }

    public AudioConfig getAudioConfig() {
        return mConfig;
    }

    /**
     * A thread encoding the microphone data using the media encoder APIs.
     * Communicates with using {@link #mInputBufferQueue}.
     *
     * We want to do this operation on a different thread than the recording one (to avoid
     * losing frames while we're working here), and different than the {@link MediaEncoder}
     * own thread (we want that to be reactive - stop() must become onStop() soon).
     */
    private class AudioEncodingThread extends Thread {
        private AudioEncodingThread() {
            // Not sure about this... This thread can do VERY time consuming operations,
            // and slowing down the preview/camera threads can break them e.g. hit internal
            // timeouts for camera requests to be consumed.
            // setPriority(Thread.MAX_PRIORITY);
        }

        @Override
        public void run() {
            encoding: while (isRecording()) {
                if (mInputBufferQueue.isEmpty()) {
                    skipFrames(3);
                } else {
                    LOG.v("encoding thread - performing", mInputBufferQueue.size(),
                            "pending operations.");
                    InputBuffer inputBuffer;
                    while ((inputBuffer = mInputBufferQueue.peek()) != null) {

                        // Performance logging
                        if (PERFORMANCE_DEBUG) {
                            long sendEnd = System.nanoTime() / 1000000;
                            Long sendStart = mDebugSendStartMap.remove(inputBuffer.timestamp);
                            //noinspection StatementWithEmptyBody
                            if (sendStart != null) {
                                mDebugSendAvgDelay = ((mDebugSendAvgDelay * mDebugSendCount)
                                        + (sendEnd - sendStart)) / (++mDebugSendCount);
                                LOG.v("send delay millis:", sendEnd - sendStart,
                                        "average:", mDebugSendAvgDelay);
                            } else {
                                // This input buffer was already processed
                                // (but tryAcquire failed for now).
                            }
                        }

                        // Actual work
//                        if (inputBuffer.isEndOfStream) {
//                            acquireInputBuffer(inputBuffer);
//                            encode(inputBuffer);
//                            break encoding;
//                        } else if (tryAcquireInputBuffer(inputBuffer)) {
//                            encode(inputBuffer);
//                        } else {
//                            skipFrames(3);
//                        }
                        if (inputBuffer.isEndOfStream) {
                            acquireInputBuffer(inputBuffer);
                            encode(inputBuffer);
                            break encoding;
                        } else {
                            if (mFirstSample == 0) {
                                mFirstSample = 1;

                                if (tryAcquireInputBuffer(inputBuffer)) {
                                    encode(inputBuffer);
                                } else {
                                    skipFrames(3);
                                }
                            } else {
                                if (mFirstSample == 1) {
                                    if (mController.getPresentationTimeUs() != 0) {
                                        mFirstSample = 2;

                                        int audioChannels = mConfig.channels;
                                        int audioSampleRate = mConfig.samplingFrequency;
                                        int durationUs = (int)(((inputBuffer.length / (audioChannels * 2.0)) / audioSampleRate) * 1000000);
                                        long addSize = (inputBuffer.timestamp - mController.getPresentationTimeUs() + durationUs / 2) / durationUs;
                                        while (addSize > 0) {
                                            InputBuffer addInputBuffer = mInputBufferPool.get();
                                            addInputBuffer.source = mByteBufferPool.get();
                                            if (inputBuffer.length > addInputBuffer.source.remaining()) {
                                                LogUtil.e("bbb", "source.capacity == " + addInputBuffer.source.capacity() + ", source.remaining == " + addInputBuffer.source.remaining() + ", inputBuffer.length === " + inputBuffer.length);
                                            } else {
                                                addInputBuffer.source.put(new byte[inputBuffer.length], 0, inputBuffer.length);
                                            }
                                            addInputBuffer.timestamp = inputBuffer.timestamp - durationUs * addSize;
                                            addInputBuffer.length = inputBuffer.length;
                                            addInputBuffer.isEndOfStream = inputBuffer.isEndOfStream;
                                            if (tryAcquireInputBuffer(addInputBuffer)) {
                                                encode(addInputBuffer);

                                                addSize--;
                                            } else
                                                skipFrames(3);
                                        }
                                        if (tryAcquireInputBuffer(inputBuffer)) {
                                            encode(inputBuffer);
                                        } else {
                                            skipFrames(3);
                                        }
                                    } else
                                        skipFrames(3);
                                } else {
                                    if (tryAcquireInputBuffer(inputBuffer)) {
                                        encode(inputBuffer);
                                    } else {
                                        skipFrames(3);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // We got an end of stream.
            mInputBufferPool.clear();
            if (PERFORMANCE_DEBUG) {
                // After latest changes, the count here is not so different between MONO and STEREO.
                // We get about 400 frames in both cases (430 for MONO, but doesn't seem like
                // a big issue).
                LOG.e("EXECUTE DELAY MILLIS:", mDebugExecuteAvgDelay,
                        "COUNT:", mDebugExecuteCount);
                LOG.e("SEND DELAY MILLIS:", mDebugSendAvgDelay,
                        "COUNT:", mDebugSendCount);
            }
        }

        private void encode(@NonNull InputBuffer buffer) {
            long executeStart = System.nanoTime() / 1000000;

            LOG.v("encoding thread - performing pending operation for timestamp:",
                    buffer.timestamp, "- encoding.");
            // NOTE: this copy is prob. the worst part here for performance
            buffer.data.put(buffer.source);
            mByteBufferPool.recycle(buffer.source);
            mInputBufferQueue.remove(buffer);
            encodeInputBuffer(buffer);
            boolean eos = buffer.isEndOfStream;
            mInputBufferPool.recycle(buffer);
            LOG.v("encoding thread - performing pending operation for timestamp:",
                    buffer.timestamp, "- draining.");
            // NOTE: can consider calling this drainOutput on yet another thread, which would let us
            // use an even smaller BUFFER_POOL_MAX_SIZE without losing audio frames. But this way
            // we can accumulate delay on this new thread without noticing (no pool getting empty).
            drainOutput(eos);

            if (PERFORMANCE_DEBUG) {
                long executeEnd = System.nanoTime() / 1000000;
                mDebugExecuteAvgDelay = ((mDebugExecuteAvgDelay * mDebugExecuteCount)
                        + (executeEnd - executeStart)) / (++mDebugExecuteCount);
                LOG.v("execute delay millis:", executeEnd - executeStart,
                        "average:", mDebugExecuteAvgDelay);
            }
        }
    }
}
