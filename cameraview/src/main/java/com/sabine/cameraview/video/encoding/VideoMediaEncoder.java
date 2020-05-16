package com.sabine.cameraview.video.encoding;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.sabine.cameraview.CameraLogger;
import com.sabine.cameraview.utils.LogUtil;

import java.io.IOException;

/**
 * Base class for video encoding.
 *
 * This uses {@link MediaCodec#createInputSurface()} to create an input {@link Surface}
 * into which we can write and that MediaCodec itself can read.
 *
 * This makes everything easier with respect to the process explained in {@link MediaEncoder}
 * docs. We can skip the whole input part of acquiring an InputBuffer, filling it with data
 * and returning it to the encoder with {@link #encodeInputBuffer(InputBuffer)}.
 *
 * All of this is automatically done by MediaCodec as long as we keep writing data into the
 * given {@link Surface}. This class alone does not do this - subclasses are required to do so.
 *
 * @param <C> the config object.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
abstract class VideoMediaEncoder<C extends VideoConfig> extends MediaEncoder {

    private static final String TAG = VideoMediaEncoder.class.getSimpleName();
    private static final CameraLogger LOG = CameraLogger.create(TAG);

    @SuppressWarnings("WeakerAccess")
    protected C mConfig;

    protected int mVideoRealBitrate = 0;

    @SuppressWarnings("WeakerAccess")
    protected Surface mSurface;

    @SuppressWarnings("WeakerAccess")
    protected int mFrameNumber = -1;

    private boolean mSyncFrameFound = false;

    VideoMediaEncoder(@NonNull C config) {
        super(NAME_VIDEO);
        mConfig = config;
        mVideoRealBitrate = mConfig.bitRate;
    }

    @EncoderThread
    @Override
    protected void onPrepare(@NonNull MediaEncoderEngine.Controller controller) {

    }

    protected void onPrepare(onPrepareListener onPrepareListener) {
        MediaFormat format = MediaFormat.createVideoFormat(mConfig.mimeType, mConfig.width,
                mConfig.height);

        // Failing to specify some of these can cause the MediaCodec configure() call to throw an
        // unhelpful exception. About COLOR_FormatSurface, see
        // https://stackoverflow.com/q/28027858/4288782
        // This just means it is an opaque, implementation-specific format that the device
        // GPU prefers. So as long as we use the GPU to draw, the format will match what
        // the encoder expects.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mVideoRealBitrate);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, mConfig.frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // seconds between key frames!
        format.setInteger(MediaFormat.KEY_ROTATION, mConfig.rotation);
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
            /**
             * 可选配置，设置码率模式
             * BITRATE_MODE_VBR：恒定质量
             * BITRATE_MODE_VBR：可变码率
             * BITRATE_MODE_CBR：恒定码率
             */
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            /**
             * 可选配置，设置H264 Profile
             * 需要做兼容性检查
             */
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            /**
             * 可选配置，设置H264 Level
             * 需要做兼容性检查
             */
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31);
        }

        LogUtil.e(TAG, "onPrepare: " + format);
        try {
            if (mConfig.encoder != null) {
                mMediaCodec = MediaCodec.createByCodecName(mConfig.encoder);
            } else {
                mMediaCodec = MediaCodec.createEncoderByType(mConfig.mimeType);
            }
            mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mSurface = mMediaCodec.createInputSurface();
            mMediaCodec.start();
            if (onPrepareListener != null) onPrepareListener.onPrepareSuccess();
        } catch (Exception e) {
            LogUtil.e(TAG, "onPrepare: " + e.toString());
            if (e instanceof IllegalArgumentException) { // 这个错误是视频码率参数不支持导致的，需要降码率
                mMediaCodec.release();
                mMediaCodec = null;
                mVideoRealBitrate -= 10 * 1000 * 1000;
                if (mVideoRealBitrate > 0) onPrepare(onPrepareListener);
            }
        }
    }

    @EncoderThread
    @Override
    protected void onStart() {
        // Nothing to do here. Waiting for the first frame.
        mFrameNumber = 0;
    }

    @EncoderThread
    @Override
    protected void onStop() {
        LogUtil.i(TAG, "onStop  setting mFrameNumber to -1 and signaling the end of input stream.");
        mFrameNumber = -1;
        // Signals the end of input stream. This is a Video only API, as in the normal case,
        // we use input buffers to signal the end. In the video case, we don't have input buffers
        // because we use an input surface instead.
        mMediaCodec.signalEndOfInputStream();
        drainOutput(true);
    }

    /**
     * The first frame that we write MUST have the BUFFER_FLAG_SYNC_FRAME flag set.
     * It sometimes doesn't because we might drop some frames in {@link #drainOutput(boolean)},
     * basically if, at the time, the muxer was not started yet, due to Audio setup being slow.
     *
     * We can't add the BUFFER_FLAG_SYNC_FRAME flag to the first frame just because we'd like to.
     * But we can drop frames until we get a sync one.
     *
     * @param pool the buffer pool
     * @param buffer the buffer
     */
    @Override
    protected void onWriteOutput(@NonNull OutputBufferPool pool, @NonNull OutputBuffer buffer, boolean isConfig) {
        if (isConfig) super.onWriteOutput(pool, buffer, isConfig);
        else {
            if (!mSyncFrameFound) {
                LogUtil.w("onWriteOutput:", "bbb sync frame not found yet. Checking. presentationTimeUs === " + buffer.info.presentationTimeUs);
                int flag = MediaCodec.BUFFER_FLAG_SYNC_FRAME;
                boolean hasFlag = (buffer.info.flags & flag) == flag;
                if (hasFlag) {
                    LogUtil.w("onWriteOutput:", "bbb SYNC FRAME FOUND!");
                    mSyncFrameFound = true;
                    super.onWriteOutput(pool, buffer, isConfig);
                } else {
                    LogUtil.w("onWriteOutput:", "bbb DROPPING FRAME and requesting a sync frame soon. flags === " + buffer.info.flags);
                    Bundle params = new Bundle();
                    params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    mMediaCodec.setParameters(params);
                    pool.recycle(buffer);
                }
            } else {
                super.onWriteOutput(pool, buffer, isConfig);
            }
        }
    }

    @Override
    protected int getEncodedBitRate() {
        Log.e(TAG, "getEncodedBitRate: mVideoRealBitrate = " + mVideoRealBitrate);
        return mVideoRealBitrate;
    }

    public VideoConfig getVideoConfig() {
        return mConfig;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    protected boolean shouldRenderFrame(long timestampUs) {
        if (timestampUs == 0) return false; // grafika said so
        if (mFrameNumber < 0) return false; // We were asked to stop.
        if (!isRecording()) return false; // We were not asked yet, but we'll be soon.
        mFrameNumber++;
        return true;
    }
}
