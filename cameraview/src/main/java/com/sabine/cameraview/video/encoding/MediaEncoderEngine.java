package com.sabine.cameraview.video.encoding;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.sabine.cameraview.CameraLogger;
import com.sabine.cameraview.internal.WorkerHandler;
import com.sabine.cameraview.utils.LogUtil;
import com.sabinetek.mp4v2utils.Mp4v2Helper;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The entry point for encoding video files.
 *
 * The external API is simple but the internal mechanism is not easy. Basically the engine
 * controls a {@link MediaEncoder} instance for each track (e.g. one for video, one for audio).
 *
 * 1. We prepare the MediaEncoders: {@link MediaEncoder#prepare(Controller)}
 *    MediaEncoders can be prepared synchronously or not.
 *
 * 2. Someone calls {@link #start()} from any thread.
 *    As a consequence, we start the MediaEncoders: {@link MediaEncoder#start()}.
 *
 * 3. MediaEncoders do not start synchronously. Instead, they call
 *    {@link Controller#notifyStarted(MediaFormat)} when they have a legit format,
 *    and we keep track of who has started.
 *
 * 4. When all MediaEncoders have started, we actually start the muxer.
 *
 * 5. Someone calls {@link #stop()} from any thread.
 *    As a consequence, we stop the MediaEncoders: {@link MediaEncoder#stop()}.
 *
 * 6. MediaEncoders do not stop synchronously. Instead, they will stop reading but
 *    keep draining the codec until there's no data left. At that point, they can
 *    call {@link Controller#notifyStopped()}.
 *
 * 7. When all MediaEncoders have been released, we actually stop the muxer and notify.
 *
 * There is another possibility where MediaEncoders themselves want to stop, for example
 * because they reach some limit or constraint (e.g. max duration). For this, they should
 * call {@link Controller#requestStop(int)}. Once all MediaEncoders have stopped, we will
 * actually call {@link #stop()} on ourselves.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class MediaEncoderEngine {

    /**
     * Receives the stop event callback to know when the video
     * was written (or what went wrong).
     */
    public interface Listener {

        /**
         * Called when encoding started.
         */
        @EncoderThread
        void onEncodingStart(int videoBitrate);

        /**
         * Called when encoding stopped. At this point the muxer or the encoders might still be
         * processing data, but we have stopped receiving input (recording video and audio frames).
         * Actually, we will stop very soon.
         *
         * The {@link #onEncodingEnd(int, Exception)} callback will soon be called
         * with the results.
         */
        @EncoderThread
        void onEncodingStop();

        /**
         * Called when encoding ended for some reason.
         * If there's an exception, it failed.
         * @param reason the reason
         * @param e the error, if present
         */
        @EncoderThread
        void onEncodingEnd(int reason, @Nullable Exception e);
    }

    public class WriteOutputEntry {
        public byte[] mOutputBuffer;
        public boolean mIsConfig;
        public boolean mIsVideo;
        public long mPresentationTimeUs;

        public WriteOutputEntry() {
        }

        public WriteOutputEntry(byte[] outputBuffer, boolean isConfig, boolean isVideo, long presentationTimeUs) {
            mOutputBuffer = outputBuffer;
            mIsConfig = isConfig;
            mIsVideo = isVideo;
            mPresentationTimeUs = presentationTimeUs;
        }
    }

    private final static String TAG = MediaEncoderEngine.class.getSimpleName();
    private final static CameraLogger LOG = CameraLogger.create(TAG);
    private static final boolean DEBUG_PERFORMANCE = false;

    @SuppressWarnings("WeakerAccess")
    public final static int END_BY_USER = 0;
    public final static int END_BY_MAX_DURATION = 1;
    public final static int END_BY_MAX_SIZE = 2;

    private final List<MediaEncoder> mEncoders = new ArrayList<>();
    private int mStartedEncodersCount = 0;
    private int mStoppedEncodersCount = 0;
    private short mMediaMuxerStartStatus; // -1:no start; 0:starting; 1:started;
    private long mPresentationTimeUs = 0;

    @SuppressWarnings("FieldCanBeLocal")
    private final Controller mController = new Controller();
    private final WorkerHandler mControllerThread = WorkerHandler.get("EncoderEngine");
    private final Object mControllerLock = new Object();
    private final Object mOutputQueueLock = new Object();
    private Listener mListener;
    private int mEndReason = END_BY_USER;

    private Mp4v2Helper mMp4v2Helper;

    private final Map<String, AtomicInteger> mPendingEvents = new HashMap<>();

    private Thread mWriteMp4Thread;
    private List<WriteOutputEntry> mOutputEntrys;
    private boolean mWriteEnd;

    /**
     * Creates a new engine for the given file, with the given encoders and max limits,
     * and listener to receive events.
     *
     * @param file output file
     * @param videoEncoder video encoder to use
     * @param audioEncoder audio encoder to use
     * @param listener a listener
     */
    public MediaEncoderEngine(@NonNull File file,
                              @NonNull VideoMediaEncoder videoEncoder,
                              @Nullable AudioMediaEncoder audioEncoder,
                              @Nullable Listener listener) {
        mMp4v2Helper = new Mp4v2Helper();
        mListener = listener;
        mEncoders.add(videoEncoder);
        mEncoders.add(audioEncoder);

        AudioConfig audioConfig = audioEncoder.getAudioConfig();
        VideoConfig videoConfig = videoEncoder.getVideoConfig();

        if (mMp4v2Helper != null) {
            int result = mMp4v2Helper.init(file.getAbsolutePath(), videoConfig.width, videoConfig.height, videoConfig.frameRate, audioConfig.channels, audioConfig.samplingFrequency);
            if (result > 0) {
                LogUtil.e(TAG, "init mp4v2 success");
            } else {
                LogUtil.e(TAG, "init mp4v2 failed");
            }
        }
        mPendingEvents.put(TAG, new AtomicInteger(0));
        mOutputEntrys = new ArrayList<>();
        mWriteEnd = false;

        // Trying to convert the size constraints to duration constraints,
        // because they are super easy to check.
        // This is really naive & probably not accurate, but...
        int bitRate = 0;
        for (MediaEncoder encoder : mEncoders) {
            bitRate += encoder.getEncodedBitRate();
        }
        int byteRate = bitRate / 8;
        for (MediaEncoder encoder : mEncoders) {
            encoder.prepare(mController);
        }
        Date nowtime = new Date();
        LogUtil.e(TAG, new SimpleDateFormat("yyyy-MM-dd-hh-mm-ss").format(nowtime) + " version : 2.2.11");
    }

    /**
     * Asks encoders to start (each one on its own track).
     */
    public final void start() {
        mPresentationTimeUs = 0;
        LOG.i("Passing event to encoders:", "START");
        for (MediaEncoder encoder : mEncoders) {
            encoder.start();
        }
        mWriteMp4Thread = new Thread() {
            @Override
            public void run() {
                while (!mWriteEnd) {
//                    synchronized (mControllerLock) {
//                    Log.e("aaa", "run: mOutputEntrys.size === " + mOutputEntrys.size());
                        if (mOutputEntrys.size() == 0) {
                            try {
                                synchronized (mOutputQueueLock) {
                                    mOutputQueueLock.wait();
                                }
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        } else {
                            if (mMp4v2Helper != null) {
                                int result = 0;
                                WriteOutputEntry writeOutputEntry = mOutputEntrys.get(0);
                                if (writeOutputEntry == null) {
                                    LogUtil.e(TAG, "run: writeOutputEntry == " + writeOutputEntry);
                                    for (int i = 0; i < mOutputEntrys.size(); i++) {
                                        LogUtil.e("aaa", "run: i === " + i + ", mOutputEntrys.get(i) === " + mOutputEntrys.get(i) + ", writeOutputEntry === " + writeOutputEntry);
                                    }
                                }
//                                if (writeOutputEntry != null) {
                                    if (writeOutputEntry.mIsConfig) {
                                        if (writeOutputEntry.mIsVideo) {
                                            if (mMp4v2Helper != null) result = mMp4v2Helper.addVideoTrack(writeOutputEntry.mOutputBuffer, writeOutputEntry.mOutputBuffer.length);
                                        } else {
                                            if (mMp4v2Helper != null) result = mMp4v2Helper.addAudioTrack(writeOutputEntry.mOutputBuffer, writeOutputEntry.mOutputBuffer.length);
                                        }
                                    } else {
                                        if (mPresentationTimeUs == 0 && writeOutputEntry.mIsVideo) {
                                            mPresentationTimeUs = writeOutputEntry.mPresentationTimeUs;
                                        }
                                        if (mPresentationTimeUs != 0) {
                                            if (writeOutputEntry.mIsVideo && mMp4v2Helper != null && writeOutputEntry.mPresentationTimeUs >= mPresentationTimeUs) {
                                                result = mMp4v2Helper.writeVideo(writeOutputEntry.mOutputBuffer, writeOutputEntry.mOutputBuffer.length, writeOutputEntry.mPresentationTimeUs);
                                            } else if (mMp4v2Helper != null && writeOutputEntry.mPresentationTimeUs >= mPresentationTimeUs) {
                                                result = mMp4v2Helper.writeAudio(writeOutputEntry.mOutputBuffer, writeOutputEntry.mOutputBuffer.length, writeOutputEntry.mPresentationTimeUs);
                                            }
                                        }
                                    }
                                synchronized (mOutputQueueLock) {
                                    mOutputEntrys.remove(0);
                                }
//                                } else {
//                                    LogUtil.e(TAG, "writeOutputEntry == null");
//                                }
                                if (result < 0) LogUtil.e(TAG, "write: encodeArray.length == " + writeOutputEntry.mOutputBuffer.length + ", result = " + result);

                            } else
                                break;
                        }
//                    }
                }
                if (mMp4v2Helper != null) {
                    mMp4v2Helper.close();
                    mMp4v2Helper = null;
                }
            }
        };
        mWriteMp4Thread.start();
    }

    /**
     * Notifies encoders of some event with the given payload.
     * Can be used for example to notify the video encoder of new frame available.
     * @param event an event string
     * @param data an event payload
     */
    @SuppressWarnings("SameParameterValue")
    public final void notify(final String event, final Object data) {
        LOG.v("Passing event to encoders:", event);
        for (MediaEncoder encoder : mEncoders) {
            encoder.notify(event, data);
        }
    }

    /**
     * Asks encoders to stop. This is not sync, of course we will ask for encoders
     * to call {@link Controller#notifyStopped()} before actually stop the muxer.
     * When all encoders request a release, {@link #end()} is called to do cleanup
     * and notify the listener.
     */
    public final void stop() {
        LogUtil.i("Passing event to encoders:", "STOP");
        for (MediaEncoder encoder : mEncoders) {
            encoder.stop();
        }
        if (mListener != null) {
            mListener.onEncodingStop();
        }
        mPresentationTimeUs = 0;
    }

    /**
     * Called after all encoders have requested a release using
     * {@link Controller#notifyStopped()}. At this point we will do cleanup and notify
     * the listener.
     */
    private void end() {
        synchronized (mOutputQueueLock) {
            mWriteEnd = true;
            mOutputQueueLock.notify();
        }
        synchronized (mControllerLock) {
            LOG.e("end:", "Releasing muxer after all encoders have been released.");
            Exception error = null;
//            mMp4v2Helper.close();
//            mMp4v2Helper = null;

//            mWriteEnd = true;
//            mOutputQueueLock.notify();

            LogUtil.w("end:", "\n\n\n ");
            mEndReason = END_BY_USER;
            mControllerThread.destroy();
            if (mListener != null) {
                mListener.onEncodingEnd(mEndReason, error);
                mListener = null;
            }
            LOG.e("end:", "Completed.");
        }
    }

    /**
     * Returns the current video encoder.
     * @return the current video encoder
     */
    @NonNull
    public VideoMediaEncoder getVideoEncoder() {
        return (VideoMediaEncoder) mEncoders.get(0);
    }

    /**
     * Returns the current audio encoder.
     * @return the current audio encoder
     */
    @SuppressWarnings("unused")
    @Nullable
    public AudioMediaEncoder getAudioEncoder() {
        return (AudioMediaEncoder) mEncoders.get(1);
    }

    /**
     * A handle for {@link MediaEncoder}s to pass information to this engine.
     * All methods here can be called for multiple threads.
     */
    @SuppressWarnings("WeakerAccess")
    public class Controller {

        private long mPresentationTimeUs = 0;

        /**
         * Request that the muxer should start. This is not guaranteed to be executed:
         * we wait for all encoders to call this method, and only then, start the muxer.
         * @param format the media format
         * @return the encoder track index
         */
        public void notifyStarted(@NonNull MediaFormat format) {
            synchronized (mControllerLock) {
//                LogUtil.e("notifyStarted:", "mp4v2 " + " format" +
//                        format.getString(MediaFormat.KEY_MIME));
                if (++mStartedEncodersCount == mEncoders.size()) {
//                    LogUtil.e("notifyStarted:", "mMediaMuxer All encoders have started." +
//                            "Starting muxer and dispatching onEncodingStart().");
                    // Go out of this thread since it might be very important for the
                    // encoders and we don't want to perform expensive operations here.
                    mControllerThread.run(new Runnable() {
                        @Override
                        public void run() {
                            mMediaMuxerStartStatus = 1;
                            if (mListener != null && getVideoEncoder().getEncodedBitRate() != getVideoEncoder().getVideoConfig().bitRate) {
                                mListener.onEncodingStart(getVideoEncoder().getEncodedBitRate());
                            }
                        }
                    });
                }
            }
        }

        /**
         * Whether the muxer is started. MediaEncoders are required to avoid
         * calling {@link #write(OutputBufferPool, OutputBuffer, boolean)} until this method returns true.
         *
         * @return true if muxer was started
         */
        public boolean isStarted() {
//            synchronized (mControllerLock) {
                return mMediaMuxerStartStatus == 1;
//            }
        }

        /**
         * Writes the given data to the muxer. Should be called after {@link #isStarted()}
         * returns true. Note: this seems to be thread safe, no lock.
         *
         * TODO: Skip first frames from encoder A when encoder B reported a firstTimeMillis
         * time that is significantly later. This can happen even if we wait for both to start,
         * because {@link MediaEncoder#notifyFirstFrameMillis(long)} can be called while the
         * muxer is still closed.
         *
         * The firstFrameMillis still has a value in computing the absolute times, but it is meant
         * to be the time of the first frame read, not necessarily a frame that will be written.
         *
         * This controller should coordinate between firstFrameMillis and skip frames that have
         * large differences.
         *
         * @param pool pool
         * @param buffer buffer
         */
        public void write(@NonNull OutputBufferPool pool, @NonNull OutputBuffer buffer, boolean isConfig) {
//            synchronized (mControllerLock) {
            int nBufferSize = buffer.data.remaining();
            if (nBufferSize > 0) {
//                byte[] encodeArray = new byte[buffer.data.remaining()];
//                buffer.data.get(encodeArray);

                WriteOutputEntry writeOutputEntry = new WriteOutputEntry();
                writeOutputEntry.mOutputBuffer = new byte[nBufferSize];
                writeOutputEntry.mIsConfig = isConfig;
                writeOutputEntry.mIsVideo = buffer.isVideo;
                writeOutputEntry.mPresentationTimeUs = buffer.info.presentationTimeUs;
                if (mPresentationTimeUs == 0 && buffer.isVideo)
                    mPresentationTimeUs = buffer.info.presentationTimeUs;
                buffer.data.get(writeOutputEntry.mOutputBuffer);
                synchronized (mOutputQueueLock) {
//                    mOutputEntrys.add(new WriteOutputEntry(encodeArray, isConfig, buffer.isVideo, buffer.info.presentationTimeUs));
                    mOutputEntrys.add(writeOutputEntry);
                    mOutputQueueLock.notify();
                }
            }
                pool.recycle(buffer);

//                int result = 0;
//                //TODO:修改libMp4V2Helper.so库接口，直接传递ByteBuffer类型参数，不必在调用接口前转成byte[]类型
////                byte[] encodeArray = new byte[buffer.data.remaining()];
////                buffer.data.get(encodeArray);
//
//                if (isConfig) {
//                    if (buffer.isVideo) {
//                        if (mMp4v2Helper != null) result = mMp4v2Helper.native_addVideoTrack(buffer.data, buffer.data.remaining()/*encodeArray, encodeArray.length*/);
//                    } else {
//                        if (mMp4v2Helper != null) result = mMp4v2Helper.native_addAudioTrack(buffer.data, buffer.data.remaining()/*encodeArray, encodeArray.length*/);
//                    }
//                } else {
//                    if (mPresentationTimeUs == 0 && buffer.isVideo) {
//                        mPresentationTimeUs = buffer.info.presentationTimeUs;
//                    }
//                    if (mPresentationTimeUs != 0) {
//                        if (buffer.isVideo && mMp4v2Helper != null && buffer.info.presentationTimeUs >= mPresentationTimeUs) {
//                            result = mMp4v2Helper.native_writeVideo(buffer.data, buffer.data.remaining()/*encodeArray, encodeArray.length*/, buffer.info.presentationTimeUs);
//
//                            long time1 = SystemClock.elapsedRealtime();
//                            byte[] encodeArray = new byte[buffer.data.remaining()];
//                            buffer.data.get(encodeArray);
//                            long time2 = SystemClock.elapsedRealtime();
//
//                            mTotalNumber++;
//                            mTotalTime += time2-time1;
//                        } else if (mMp4v2Helper != null && buffer.info.presentationTimeUs >= mPresentationTimeUs) {
//                            result = mMp4v2Helper.native_writeAudio(buffer.data, buffer.data.remaining()/*encodeArray, encodeArray.length*/, buffer.info.presentationTimeUs);
//                        }
//                    }
//                }
//
//                if (result < 0) LogUtil.e(TAG, "write: encodeArray.length == " + buffer.data.remaining() + ", result = " + result);
//                pool.recycle(buffer);
//            }
        }

        /**
         * Requests that the engine stops. This is not executed until all encoders call
         * this method, so it is a kind of soft request, just like
         * {@link #notifyStarted(MediaFormat)}. To be used when maxLength / maxSize constraints
         * are reached, for example.
         * When this succeeds, {@link MediaEncoder#stop()} is called.
         *
         * @param track track
         */
        public void requestStop(int track) {
            synchronized (mControllerLock) {
                LOG.w("requestStop:", "Called for track", track);
                if (--mStartedEncodersCount == 0) {
                    LOG.w("requestStop:", "All encoders have requested a stop.",
                            "Stopping them.");
                    // Go out of this thread since it might be very important for the
                    // encoders and we don't want to perform expensive operations here.
                    mControllerThread.run(new Runnable() {
                        @Override
                        public void run() {
                            stop();
                        }
                    });
                }
            }
        }

        /**
         * Notifies that the encoder was stopped. After this is called by all encoders,
         * we will actually stop the muxer.
         *
         */
        public void notifyStopped() {
            synchronized (mControllerLock) {
                LogUtil.w(TAG, "notifyStopped: Called for track : ");
                if (++mStoppedEncodersCount == mEncoders.size()) {
                    LogUtil.w(TAG, "requestStop: All encoders have been stopped." +
                            "Stopping the muxer.");
                    // Go out of this thread since it might be very important for the
                    // encoders and we don't want to perform expensive operations here.
                    mControllerThread.run(new Runnable() {
                        @Override
                        public void run() {
                            end();
                        }
                    });
                }
            }
        }

        public long getPresentationTimeUs() {
            return mPresentationTimeUs;
        }
    }
}
