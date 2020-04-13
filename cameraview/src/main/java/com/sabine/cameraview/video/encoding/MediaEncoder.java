package com.sabine.cameraview.video.encoding;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.sabine.cameraview.CameraLogger;
import com.sabine.cameraview.internal.utils.WorkerHandler;
import com.sabine.cameraview.utils.LogUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for single-track encoders, coordinated by a {@link MediaEncoderEngine}.
 * For the lifecycle of this class, read comments in the engine class.
 *
 * This class manages a background thread and streamlines events on this thread
 * which we call the {@link EncoderThread}:
 *
 * 1. When {@link #prepare(MediaEncoderEngine.Controller)} is called, we call
 *    {@link #onPrepare(MediaEncoderEngine.Controller)} on the encoder thread.
 *
 * 2. When {@link #start()} is called, we call {@link #onStart()} on the encoder thread.
 *
 * 3. When {@link #notify(String, Object)} is called, we call {@link #onEvent(String, Object)}
 *    on the encoder thread.
 *
 * 4. After starting, encoders are free to acquire an input buffer with
 *    {@link #tryAcquireInputBuffer(InputBuffer)} or {@link #acquireInputBuffer(InputBuffer)}.
 *
 * 5. After getting the input buffer, they are free to fill it with data.
 *
 * 6. After filling it with data, they are required to call {@link #encodeInputBuffer(InputBuffer)}
 *    for encoding to take place.
 *
 * 7. After this happens, or at regular intervals, or whenever they want, encoders can then
 *    call {@link #drainOutput(boolean)} with a false parameter to fetch the encoded data
 *    and pass it to the engine (so it can be written to the muxer).
 *
 * 8. When {@link #stop()} is called - either by the engine user, or as a consequence of having
 *    called {@link MediaEncoderEngine.Controller#requestStop(int)} - we call
 *    {@link #onStop()} on the encoder thread.
 *
 * 9. The {@link #onStop()} implementation should, as fast as possible, stop reading, signal the
 *    end of input stream (there are two ways to do so), and finally call
 *    {@link #drainOutput(boolean)} for the last time, with a true parameter.
 *
 * 10. Once everything is drained, we will call {@link #onStopped()}, on a unspecified thread.
 *     There, subclasses can perform extra cleanup of their own resources.
 *
 * For VIDEO encoders, things are much easier because we skip the whole input part.
 * See description in {@link VideoMediaEncoder}.
 *
 * MAX LENGTH CONSTRAINT
 *
 * For max length constraint, it will be checked automatically during {@link #drainOutput(boolean)}
 * In this second case, we can request a stop at reading time, so we avoid useless readings
 * in certain setups (where drain is called a lot after reading).
 *
 * TIMING
 *
 * Subclasses can use timestamps (in microseconds) in any reference system they prefer. For
 * instance, it might be the {@link System#nanoTime()} reference, or some reference provided
 * by SurfaceTextures.
 *
 * However, they are required to call {@link #notifyFirstFrameMillis(long)} and pass the
 * milliseconds of the first frame in the {@link System#currentTimeMillis()} reference, so
 * something that we can coordinate on.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public abstract class MediaEncoder {

    private final static String TAG = MediaEncoder.class.getSimpleName();
    private final static CameraLogger LOG = CameraLogger.create(TAG);

    // Did some test to see which value would maximize our performance in the current setup
    // (infinite audio pool). Measured the time it would take to write a 30 seconds video.
    // Based on this, we'll go with TIMEOUT=0 for now.
    // INPUT_TIMEOUT_US 10000: 46 seconds
    // INPUT_TIMEOUT_US 1000: 37 seconds
    // INPUT_TIMEOUT_US 100: 33 seconds
    // INPUT_TIMEOUT_US 0: 32 seconds
    private final static int INPUT_TIMEOUT_US = 0;

    // 0 also seems to be the best, although it does not change so much.
    // Can't go too high or this is a bottleneck for the audio encoder.
    private final static int OUTPUT_TIMEOUT_US = 0;

    private final static int STATE_NONE = 0;
    private final static int STATE_PREPARING = 1;
    private final static int STATE_PREPARED = 2;
    private final static int STATE_STARTING = 3;
    private final static int STATE_STARTED = 4;
    // max timestamp was reached. we will keep draining but have asked the engine to stop us.
    // this step can be skipped in case stop() is called from outside before a limit is reached.
    private final static int STATE_LIMIT_REACHED = 5;
    private final static int STATE_STOPPING = 6;
    private final static int STATE_STOPPED = 7;

    protected final static String NAME_VIDEO = "VideoEncoder";
    protected final static String NAME_AUDIO = "AudioEncoder";

    private int mState = STATE_NONE;
    private final String mName;

    @SuppressWarnings("WeakerAccess")
    protected MediaCodec mMediaCodec;

    @SuppressWarnings("WeakerAccess")
    protected WorkerHandler mWorker;

    protected AudioTimestamp mTimestamp;

    MediaFormat mediaFormat;

    private MediaEncoderEngine.Controller mController;
    private OutputBufferPool mOutputBufferPool;
    private MediaCodec.BufferInfo mBufferInfo;
    private MediaCodecBuffers mBuffers;
    private final Map<String, AtomicInteger> mPendingEvents = new HashMap<>();
    private boolean recordState;

    private long mStartTimeUs = 0; // In System.currentTimeMillis()

    private long mDebugSetStateTimestamp = Long.MIN_VALUE;

    AudioMediaEncoder mAudioMediaEncoder;

    /**
     * Needs a readable name for the thread and for logging.
     * @param name a name
     */
    @SuppressWarnings("WeakerAccess")
    protected MediaEncoder(@NonNull String name) {
        mName = name;
    }

    private void setState(int newState) {
        if (mDebugSetStateTimestamp == Long.MIN_VALUE) {
            mDebugSetStateTimestamp = System.currentTimeMillis();
        }
        long millis = System.currentTimeMillis() - mDebugSetStateTimestamp;
        mDebugSetStateTimestamp = System.currentTimeMillis();

        String newStateName = null;
        switch (newState) {
            case STATE_NONE: newStateName = "NONE"; break;
            case STATE_PREPARING: newStateName = "PREPARING"; break;
            case STATE_PREPARED: newStateName = "PREPARED"; break;
            case STATE_STARTING: newStateName = "STARTING"; break;
            case STATE_STARTED: newStateName = "STARTED"; break;
            case STATE_LIMIT_REACHED: newStateName = "LIMIT_REACHED"; break;
            case STATE_STOPPING: newStateName = "STOPPING"; break;
            case STATE_STOPPED: newStateName = "STOPPED"; break;
        }
        LOG.w(mName, "setState:", newStateName, "millisSinceLastState:", millis);
        mState = newState;
    }

    /**
     * This encoder was attached to the engine. Keep the controller
     * and run the internal thread.
     *
     * NOTE: it's important to call {@link WorkerHandler#post(Runnable)} instead of run()!
     * The internal actions can cause a stop, and due to how {@link WorkerHandler#run(Runnable)}
     * works, we might have {@link #onStop()} or {@link #onStopped()} to be executed before
     * the previous step has completed.
     */
    final void prepare(@NonNull final MediaEncoderEngine.Controller controller) {
        if (mState >= STATE_PREPARING) {
            LOG.e(mName, "Wrong state while preparing. Aborting.", mState);
            return;
        }
        mController = controller;
        mBufferInfo = new MediaCodec.BufferInfo();
        mWorker = WorkerHandler.get(mName);
        mWorker.getThread().setPriority(Thread.MAX_PRIORITY);
        LOG.i(mName, "Prepare was called. Posting.");
        mWorker.post(new Runnable() {
            @Override
            public void run() {
                LOG.i(mName, "Prepare was called. Executing.");
                setState(STATE_PREPARING);
                onPrepare(controller);
                setState(STATE_PREPARED);
            }
        });
        mStartTimeUs = 0;
    }

    /**
     * Start recording. This might be a lightweight operation
     * in case the encoder needs to wait for a certain event
     * like a "frame available".
     *
     * The {@link #STATE_STARTED} state will be set when draining for the
     * first time (not when onStart ends).
     *
     * NOTE: it's important to call {@link WorkerHandler#post(Runnable)} instead of run()!
     */
    final void start() {
        LOG.w(mName, "Start was called. Posting.");
        mWorker.post(new Runnable() {
            @Override
            public void run() {
                if (mState < STATE_PREPARED || mState >= STATE_STARTING) {
                    LOG.e(mName, "Wrong state while starting. Aborting.", mState);
                    return;
                }
                setState(STATE_STARTING);
                LOG.w(mName, "Start was called. Executing.");
                recordState = true;
                onStart();
            }
        });
    }

    /**
     * The caller notifying of a certain event occurring.
     * Should analyze the string and see if the event is important.
     *
     * NOTE: it's important to call {@link WorkerHandler#post(Runnable)} instead of run()!
     *
     * @param event what happened
     * @param data object
     */
    @SuppressWarnings("ConstantConditions")
    final void notify(final @NonNull String event, final @Nullable Object data) {
        if (!mPendingEvents.containsKey(event)) mPendingEvents.put(event,
                 new AtomicInteger(0));
        final AtomicInteger pendingEvents = mPendingEvents.get(event);
        pendingEvents.incrementAndGet();
        LOG.i(mName, "Notify was called. Posting. pendingEvents:", pendingEvents.intValue());
//        if (pendingEvents.intValue() > 2) LogUtil.w(mName, "pendingEvents.intValue() === " + pendingEvents.intValue());
        WorkerHandler.get(mName).post(new Runnable() {
            @Override
            public void run() {
                LOG.i(mName, "Notify was called. Executing. pendingEvents:",
                        pendingEvents.intValue());
                onEvent(event, data);
                pendingEvents.decrementAndGet();
            }
        });
    }

    /**
     * Stop recording. This involves signaling the end of stream and draining
     * all output left.
     *
     * The {@link #STATE_STOPPED} state will be set when draining for the
     * last time (not when onStart ends).
     *
     * NOTE: it's important to call {@link WorkerHandler#post(Runnable)} instead of run()!
     */
    final void stop() {
        if (mState >= STATE_STOPPING) {
            LogUtil.w(mName, "Wrong state while stopping. Aborting." + mState);
            return;
        }
        setState(STATE_STOPPING);
        LogUtil.e(mName, "Stop was called. Posting.");
        mWorker.post(new Runnable() {
            @Override
            public void run() {
                LogUtil.e(mName, "Stop was called. Executing.");
                onStop();
            }
        });
    }

    public void setBaseTimeUs(long baseTimeUs) {}

    /**
     * Called to prepare this encoder before starting.
     * Any initialization should be done here as it does not interfere with the original
     * thread (that, generally, is the rendering thread).
     *
     * At this point subclasses MUST create the {@link #mMediaCodec} object.
     *
     * @param controller the muxer controller
     */
    @EncoderThread
    protected abstract void onPrepare(@NonNull MediaEncoderEngine.Controller controller);

    /**
     * Start recording. This might be a lightweight operation
     * in case the encoder needs to wait for a certain event
     * like a "frame available".
     */
    @EncoderThread
    protected abstract void onStart();

    /**
     * The caller notifying of a certain event occurring.
     * Should analyze the string and see if the event is important.
     * @param event what happened
     * @param data object
     */
    @EncoderThread
    protected void onEvent(@NonNull String event, @Nullable Object data) {}

    /**
     * Stop recording. This involves signaling the end of stream and draining
     * all output left.
     */
    @EncoderThread
    protected abstract void onStop();

    /**
     * Called by {@link #drainOutput(boolean)} when we get an EOS signal (not necessarily in the
     * parameters, might also be through an input buffer flag).
     *
     * This is a good moment to release all resources, although the muxer might still
     * be alive (we wait for the other Encoder, see MediaEncoderEngine.Controller).
     */
    @CallSuper
    protected void onStopped() {
        LogUtil.e(mName, "is being released. Notifying controller and releasing codecs.");
        // TODO should we call notifyStopped after this method ends?
        mMediaCodec.stop();
        mMediaCodec.release();
        mMediaCodec = null;
        mOutputBufferPool.clear();
        mOutputBufferPool = null;
        mBuffers = null;
        setState(STATE_STOPPED);
        mWorker.destroy();
        mController.notifyStopped();
        recordState = false;
    }

    /**
     * Returns a new input buffer and index, waiting at most {@link #INPUT_TIMEOUT_US} if none
     * is available. Callers should check the boolean result - true if the buffer was filled.
     *
     * @param holder the input buffer holder
     * @return true if acquired
     */
    @SuppressWarnings("WeakerAccess")
    protected boolean tryAcquireInputBuffer(@NonNull InputBuffer holder) {
        if (mBuffers == null) {
            mBuffers = new MediaCodecBuffers(mMediaCodec);
        }
        int inputBufferIndex = mMediaCodec.dequeueInputBuffer(INPUT_TIMEOUT_US);
        if (inputBufferIndex < 0) {
            return false;
        } else {
            holder.index = inputBufferIndex;
            holder.data = mBuffers.getInputBuffer(inputBufferIndex);
            return true;
        }
    }

    /**
     * Returns a new input buffer and index, waiting indefinitely if none is available.
     * The buffer should be written into, then be passed to {@link #encodeInputBuffer(InputBuffer)}.
     *
     * @param holder the input buffer holder
     */
    @SuppressWarnings({"StatementWithEmptyBody", "WeakerAccess"})
    protected void acquireInputBuffer(@NonNull InputBuffer holder) {
        while (!tryAcquireInputBuffer(holder)) {}
    }

    /**
     * Encode data into the {@link #mMediaCodec}.
     *
     * @param buffer the input buffer
     */
    @SuppressWarnings("WeakerAccess")
    protected void encodeInputBuffer(InputBuffer buffer) {
//        Log.e(mName, "drainOutput - Buffer:" + buffer.index +
//                ", Bytes:" + buffer.length +
//                ", Presentation:" + buffer.timestamp +
//                ", isEndOfStream:" + buffer.isEndOfStream);
        if (buffer.isEndOfStream) { // send EOS
            mMediaCodec.queueInputBuffer(buffer.index, 0, 0,
                    buffer.timestamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        } else {
            mMediaCodec.queueInputBuffer(buffer.index, 0, buffer.length,
                    buffer.timestamp, 0);
        }
    }

    private boolean isFirstKeyFrame = true;

    /**
     * Extracts all pending data that was written and encoded into {@link #mMediaCodec},
     * and forwards it to the muxer.
     *
     * If drainAll is not set, this returns after TIMEOUT_USEC if there is no more data to drain.
     * If drainAll is set, we wait until we see EOS on the output.
     * Calling this with drainAll set should be done once, right before stopping the muxer.
     *
     * @param drainAll whether to drain all
     */
    @SuppressLint("LogNotTimber")
    @SuppressWarnings("WeakerAccess")
    protected final void drainOutput(boolean drainAll) {
        LOG.i(mName, "DRAINING - EOS:", drainAll);
        if (mMediaCodec == null) {
            LOG.e("drain() was called before prepare() or after releasing.");
            return;
        }
        if (mBuffers == null) {
            mBuffers = new MediaCodecBuffers(mMediaCodec);
        }
        while (true) {
            int encoderStatus = mMediaCodec.dequeueOutputBuffer(mBufferInfo, OUTPUT_TIMEOUT_US);
            LOG.v(mName, "DRAINING - Got status:", encoderStatus);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!drainAll) break; // out of while

            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                mBuffers.onOutputBuffersChanged();

            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                LogUtil.e(mName, "bbb INFO_OUTPUT_FORMAT_CHANGED + presentationTimeUs === " + mBufferInfo.presentationTimeUs);
                if (mController.isStarted()) {
                    throw new RuntimeException("MediaFormat changed twice.");
                }
                mediaFormat = mMediaCodec.getOutputFormat();
                mController.notifyStarted(mediaFormat/*, mLastTimeUs, mName.equals("VideoEncoder")*/);
                setState(STATE_STARTED);
                if (mOutputBufferPool == null) mOutputBufferPool = new OutputBufferPool();
            } else if (encoderStatus < 0) {
                LogUtil.e(mName, "Unexpected result from dequeueOutputBuffer: " + encoderStatus);
                // let's ignore it
            } else {
                ByteBuffer encodedData = mBuffers.getOutputBuffer(encoderStatus);

                // Codec config means that config data was pulled out and fed to the muxer
                // when we got the INFO_OUTPUT_FORMAT_CHANGED status. Ignore it.
                boolean isCodecConfig = (mBufferInfo.flags
                        & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;

                if (isCodecConfig) {
                    OutputBuffer buffer = mOutputBufferPool.get();
                    //noinspection ConstantConditions
                    buffer.info = mBufferInfo;
                    buffer.isVideo = mName.equals(NAME_VIDEO);
                    buffer.data = encodedData;
                    onWriteOutput(mOutputBufferPool, buffer, true);
                } else if (mController.isStarted() && mBufferInfo.size != 0) {

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                    // Write.
                    LOG.v(mName, "DRAINING - About to write(). Adjusted presentation:",
                            mBufferInfo.presentationTimeUs);
                    OutputBuffer buffer = mOutputBufferPool.get();
                    //noinspection ConstantConditions
                    buffer.info = mBufferInfo;
                    buffer.isVideo = mName.equals(NAME_VIDEO);
                    buffer.data = encodedData;
                    onWriteOutput(mOutputBufferPool, buffer, false);
                }
                mMediaCodec.releaseOutputBuffer(encoderStatus, false);

                // Check for the EOS flag so we can call onStopped.
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    LOG.e(mName, "drainOutput: DRAINING - Got EOS. Releasing the codec.");
                    onStopped();
                    break;
                }
            }
        }
    }

    @CallSuper
    protected void onWriteOutput(@NonNull OutputBufferPool pool, @NonNull OutputBuffer buffer, boolean isConfig) {
        mController.write(pool, buffer, isConfig);
    }

    protected abstract int getEncodedBitRate();

    @SuppressWarnings("WeakerAccess")
    protected boolean isRecording() {
        return recordState;
    }

    /**
     * Should be called by subclasses to pass the milliseconds of the first frame - as soon
     * as this information is available. The milliseconds should be in the
     * {@link System#currentTimeMillis()} reference system, so we can coordinate between different
     * encoders.
     *
     * @param firstFrameMillis the milliseconds of the first frame presentation
     */
    @SuppressWarnings("WeakerAccess")
    public final void notifyFirstFrameMillis(long firstFrameMillis) {
        mStartTimeUs = firstFrameMillis;
    }

    /**
     * Returns the number of events (see {@link #onEvent(String, Object)}) that were scheduled
     * but still not passed to that function. Could be used to drop some of them if this
     * number is too high.
     *
     * @param event the event type
     * @return the pending events number
     */
    @SuppressWarnings({"SameParameterValue", "ConstantConditions", "WeakerAccess"})
    protected final int getPendingEvents(@NonNull String event) {
        return mPendingEvents.get(event).intValue();
    }

    protected interface onPrepareListener {
        void onPrepareSuccess();
    }
}
