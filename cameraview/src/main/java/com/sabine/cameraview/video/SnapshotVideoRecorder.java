package com.sabine.cameraview.video;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.sabine.cameraview.CameraLogger;
import com.sabine.cameraview.VideoResult;
import com.sabine.cameraview.controls.Audio;
import com.sabine.cameraview.engine.CameraEngine;
import com.sabine.cameraview.filter.Filter;
import com.sabine.cameraview.internal.DeviceEncoders;
import com.sabine.cameraview.overlay.Overlay;
import com.sabine.cameraview.overlay.OverlayDrawer;
import com.sabine.cameraview.preview.GlCameraPreview;
import com.sabine.cameraview.preview.RendererCameraPreview;
import com.sabine.cameraview.preview.RendererFrameCallback;
import com.sabine.cameraview.preview.RendererThread;
import com.sabine.cameraview.size.Size;
import com.sabine.cameraview.video.encoding.AudioConfig;
import com.sabine.cameraview.video.encoding.AudioMediaEncoder;
import com.sabine.cameraview.video.encoding.EncoderThread;
import com.sabine.cameraview.video.encoding.MediaEncoderEngine;
import com.sabine.cameraview.video.encoding.TextureConfig;
import com.sabine.cameraview.video.encoding.TextureMediaEncoder;

/**
 * A {@link VideoRecorder} that uses {@link android.media.MediaCodec} APIs.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class SnapshotVideoRecorder extends VideoRecorder implements RendererFrameCallback,
        MediaEncoderEngine.Listener {

    private static final String TAG = SnapshotVideoRecorder.class.getSimpleName();
    private static final CameraLogger LOG = CameraLogger.create(TAG);

    private static final int DEFAULT_VIDEO_FRAMERATE = 30;
    private static final int DEFAULT_AUDIO_BITRATE = 128000;

    // https://stackoverflow.com/a/5220554/4288782
    // Assuming low motion, we don't want to put this too high for default usage,
    // advanced users are still free to change this for each video.
    private static int estimateVideoBitRate(@NonNull Size size, int frameRate) {
        return (int) (0.07F * 1F * size.getWidth() * size.getHeight() * frameRate);
    }

    private static final int STATE_RECORDING = 0;
    private static final int STATE_NOT_RECORDING = 1;

    private MediaEncoderEngine mEncoderEngine;
    private AudioMediaEncoder audioMediaEncoder;
    private TextureMediaEncoder textureMediaEncoder;
    private final Object mEncoderEngineLock = new Object();
    private GlCameraPreview mPreview;

    private int mCurrentState = STATE_NOT_RECORDING;
    private int mDesiredState = STATE_NOT_RECORDING;
    private int mTextureId = 0;

    private Overlay mOverlay;
    private OverlayDrawer mOverlayDrawer;
    private boolean mHasOverlay;

    private Filter mCurrentFilter;

    public SnapshotVideoRecorder(@NonNull CameraEngine engine,
                                 @NonNull GlCameraPreview preview,
                                 @Nullable Overlay overlay) {
        super(engine);
        mPreview = preview;
        mOverlay = overlay;
        mHasOverlay = overlay != null && overlay.drawsOn(Overlay.Target.VIDEO_SNAPSHOT);
    }

    @Override
    public long getTimeStamp() {
        if (audioMediaEncoder != null) return audioMediaEncoder.getTimestamp();
        return 0;
    }

    @Override
    protected void onStart() {
        mDesiredState = STATE_RECORDING;
        mPreview.addRendererFrameCallback(this);
    }

    // Can be called different threads
    @Override
    protected void onStop(boolean isCameraShutdown) {
        if (isCameraShutdown) {
            // The renderer callback might never be called. From my tests, it's not,
            // so we can't wait for that callback to stop the encoder engine.
            LOG.i("Stopping the encoder engine from isCameraShutdown.");
            mDesiredState = STATE_NOT_RECORDING;
            mCurrentState = STATE_NOT_RECORDING;
            synchronized (mEncoderEngineLock) {
                if (mEncoderEngine != null) {
                    mEncoderEngine.stop();
                }
            }
        } else {
            mDesiredState = STATE_NOT_RECORDING;
        }
    }

    @RendererThread
    @Override
    public void onRendererTextureCreated(int textureId) {
        mTextureId = textureId;
        if (mHasOverlay) {
            mOverlayDrawer = new OverlayDrawer(mOverlay, mResult.size);
        }
    }

    @RendererThread
    @Override
    public void onRendererFilterChanged(@NonNull Filter filter, float filterLevel) {
        mCurrentFilter = filter.copy();
        mCurrentFilter.setSize(mResult.size.getWidth(), mResult.size.getHeight());
        synchronized (mEncoderEngineLock) {
            if (mEncoderEngine != null) {
                mEncoderEngine.notify(TextureMediaEncoder.FILTER_EVENT, mCurrentFilter);
                if (textureMediaEncoder != null) textureMediaEncoder.setFileterLevel(filterLevel);
            }
        }
    }

    @RendererThread
    @Override
    public void onRendererFrame(@NonNull SurfaceTexture surfaceTexture, int rotation,
                                float scaleX, float scaleY) {
        if (mCurrentState == STATE_NOT_RECORDING && mDesiredState == STATE_RECORDING) {
            LOG.i("Starting the encoder engine.");

            // Set default options
            if (mResult.videoFrameRate <= 0) mResult.videoFrameRate = DEFAULT_VIDEO_FRAMERATE;
            if (mResult.videoBitRate <= 0) mResult.videoBitRate
                    = estimateVideoBitRate(mResult.size, mResult.videoFrameRate);
            if (mResult.audioBitRate <= 0) mResult.audioBitRate = DEFAULT_AUDIO_BITRATE;

            // Define mime types
            String videoType = "";
            switch (mResult.videoCodec) {
                case H_263: videoType = "video/3gpp"; break; // MediaFormat.MIMETYPE_VIDEO_H263;
                case H_264:
                case DEVICE_DEFAULT:
                    videoType = "video/avc"; break; // MediaFormat.MIMETYPE_VIDEO_AVC:
            }
            String audioType = "audio/mp4a-latm";
            TextureConfig videoConfig = new TextureConfig();
            AudioConfig audioConfig = new AudioConfig();

            int audioChannels = 2;
            // Check the availability of values
            Size newVideoSize = null;
            int newVideoBitRate = 0;
            int newAudioBitRate = 0;
            int newVideoFrameRate = 0;
            int videoEncoderOffset = 0;
            int audioEncoderOffset = 0;
            boolean encodersFound = false;
            DeviceEncoders deviceEncoders = null;
            while (!encodersFound) {
                LOG.i("Checking DeviceEncoders...",
                        "videoOffset:", videoEncoderOffset,
                        "audioOffset:", audioEncoderOffset);
                try {
                    deviceEncoders = new DeviceEncoders(DeviceEncoders.MODE_RESPECT_ORDER,
                            videoType, audioType, videoEncoderOffset, audioEncoderOffset);
                } catch (RuntimeException e) {
                    LOG.w("Could not respect encoders parameters.",
                            "Going on again without checking encoders, possibly failing.");
                    newVideoSize = mResult.size;
                    newVideoBitRate = mResult.videoBitRate;
                    newVideoFrameRate = mResult.videoFrameRate;
                    newAudioBitRate = mResult.audioBitRate;
                    break;
                }
                deviceEncoders = new DeviceEncoders(DeviceEncoders.MODE_PREFER_HARDWARE,
                        videoType, audioType, videoEncoderOffset, audioEncoderOffset);
                try {
                    newVideoSize = deviceEncoders.getSupportedVideoSize(mResult.size);
                    newVideoBitRate = deviceEncoders.getSupportedVideoBitRate(mResult.videoBitRate);
                    newVideoFrameRate = deviceEncoders.getSupportedVideoFrameRate(newVideoSize,
                            mResult.videoFrameRate);
                    deviceEncoders.tryConfigureVideo(videoType, newVideoSize, newVideoFrameRate,
                            newVideoBitRate);
                    newAudioBitRate = deviceEncoders
                            .getSupportedAudioBitRate(mResult.audioBitRate);
                    deviceEncoders.tryConfigureAudio(audioType, newAudioBitRate,
                            audioConfig.samplingFrequency, audioChannels);
                    encodersFound = true;
                } catch (DeviceEncoders.VideoException videoException) {
                    LOG.i("Got VideoException:", videoException.getMessage());
                    videoEncoderOffset++;
                } catch (DeviceEncoders.AudioException audioException) {
                    LOG.i("Got AudioException:", audioException.getMessage());
                    audioEncoderOffset++;
                }
            }

            // Video
            videoConfig.width = mResult.size.getWidth();
            videoConfig.height = mResult.size.getHeight();
            videoConfig.bitRate = mResult.videoBitRate;
            videoConfig.frameRate = mResult.videoFrameRate;
            videoConfig.rotation = rotation + mResult.rotation;
            videoConfig.mimeType = videoType;
            videoConfig.encoder = deviceEncoders.getVideoEncoder();
            videoConfig.textureId = mTextureId;
            videoConfig.scaleX = mResult.scaleX;
            videoConfig.scaleY = mResult.scaleY;
            // Get egl context from the RendererThread, which is the one in which we have created
            // the textureId and the overlayTextureId, managed by the GlSurfaceView.
            // Next operations can then be performed on different threads using this handle.
            videoConfig.eglContext = EGL14.eglGetCurrentContext();
            if (mHasOverlay) {
                videoConfig.overlayTarget = Overlay.Target.VIDEO_SNAPSHOT;
                videoConfig.overlayDrawer = mOverlayDrawer;
                videoConfig.overlayRotation = mResult.rotation;
                // ^ no "rotation" here! Overlays are already in VIEW ref.
            }
            // Audio
            audioConfig.bitRate = mResult.audioBitRate;
            audioConfig.channels = audioChannels;
            audioConfig.encoder = deviceEncoders.getAudioEncoder();

            audioMediaEncoder = new AudioMediaEncoder(audioConfig);
            textureMediaEncoder = new TextureMediaEncoder(videoConfig, audioMediaEncoder);

            // Adjustment
//            mResult.rotation = 0; // We will rotate the result instead.
            mCurrentFilter.setSize(mResult.size.getWidth(), mResult.size.getWidth());

            // Engine
            synchronized (mEncoderEngineLock) {
                mEncoderEngine = new MediaEncoderEngine(mResult.file,
                        textureMediaEncoder,
                        audioMediaEncoder,
                        SnapshotVideoRecorder.this);
                mEncoderEngine.notify(TextureMediaEncoder.FILTER_EVENT, mCurrentFilter);
                if (textureMediaEncoder != null) textureMediaEncoder.setFileterLevel(mPreview.getFilterLevel());
                mEncoderEngine.start();
                dispatchVideoRecordingStart();
            }
            mCurrentState = STATE_RECORDING;
        } else {
            if (mCurrentState == STATE_RECORDING) {
                LOG.v("scheduling frame.");
                synchronized (mEncoderEngineLock) {
                    if (mEncoderEngine != null) { // Can be null on teardown.
                        LOG.v("dispatching frame.");
                        TextureMediaEncoder textureEncoder
                                = (TextureMediaEncoder) mEncoderEngine.getVideoEncoder();
                        TextureMediaEncoder.Frame frame = textureEncoder.acquireFrame();
                        frame.timestampNanos = surfaceTexture.getTimestamp();
                        // NOTE: this is an approximation but it seems to work:
                        frame.timestampMillis = System.currentTimeMillis();
                        frame.cropScaleX = scaleX;
                        frame.cropScaleY = scaleY;
                        surfaceTexture.getTransformMatrix(frame.transform);
                        mEncoderEngine.notify(TextureMediaEncoder.FRAME_EVENT, frame);
                    }
                }
            }

            if (mCurrentState == STATE_RECORDING && mDesiredState == STATE_NOT_RECORDING) {
                LOG.i("Stopping the encoder engine.");
                mCurrentState = STATE_NOT_RECORDING;
                synchronized (mEncoderEngineLock) {
                    if (mEncoderEngine != null) {
                        mEncoderEngine.stop();
                    }
                }
            }
        }

    }

    @Override
    public void onEncodingStart(int videoBitrate) {
        // This would be the most correct place to call dispatchVideoRecordingStart. However,
        // after this we'll post the call on the UI thread which can take some time. To compensate
        // this, we call dispatchVideoRecordingStart() a bit earlier in this class (onStart()).
        dispatchEncodeStart(videoBitrate);
    }

    @Override
    public void onEncodingStop() {

    }

    @EncoderThread
    @Override
    public void onEncodingEnd(int stopReason, @Nullable Exception e) {
        // If something failed, undo the result, since this is the mechanism
        // to notify Camera1Engine about this.
        if (e != null) {
            LOG.e("Error onEncodingEnd", e);
            mResult = null;
            mError = e;
        } else {
            if (stopReason == MediaEncoderEngine.END_BY_MAX_DURATION) {
                LOG.i("onEncodingEnd because of max duration.");
                mResult.endReason = VideoResult.REASON_MAX_DURATION_REACHED;
            } else if (stopReason == MediaEncoderEngine.END_BY_MAX_SIZE) {
                LOG.i("onEncodingEnd because of max size.");
                mResult.endReason = VideoResult.REASON_MAX_SIZE_REACHED;
            } else {
                LOG.i("onEncodingEnd because of user.");
            }
        }
        // Cleanup
        mCurrentState = STATE_NOT_RECORDING;
        mDesiredState = STATE_NOT_RECORDING;
        mPreview.removeRendererFrameCallback(SnapshotVideoRecorder.this);
        mPreview = null;
        if (mOverlayDrawer != null) {
            mOverlayDrawer.release();
            mOverlayDrawer = null;
        }
        synchronized (mEncoderEngineLock) {
            if (mEncoderEngine != null) mEncoderEngine = null;
        }
        dispatchVideoRecordingEnd();
        dispatchResult();
    }

    public void putAudioPcm(byte[] pcm, int length, boolean isEndOfStream) {
        if (audioMediaEncoder != null) audioMediaEncoder.putAudioPcm(pcm, length, isEndOfStream);
    }
}
