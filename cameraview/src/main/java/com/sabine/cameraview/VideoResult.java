package com.sabine.cameraview;

import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sabine.cameraview.controls.Audio;
import com.sabine.cameraview.controls.Facing;
import com.sabine.cameraview.controls.VideoCodec;
import com.sabine.cameraview.size.Size;

import java.io.File;
import java.io.FileDescriptor;

/**
 * Wraps the result of a video recording started by {@link CameraView#takeVideo(File)}.
 */
@SuppressWarnings("WeakerAccess")
public class VideoResult {

    /**
     * A result stub, for internal use only.
     */
    public static class Stub {

        Stub() {}

        public boolean isSnapshot;
        public Location location;
        public int rotation;
        public Size size;
        public File file;
        public FileDescriptor fileDescriptor;
        public Facing facing;
        public VideoCodec videoCodec;
        public Audio audio;
        public int endReason;
        public int videoBitRate;
        public int videoFrameRate;
        public int audioBitRate;
        public float scaleX;
        public float scaleY;

        @Override
        public String toString() {
            return "Stub{" +
                    "isSnapshot=" + isSnapshot +
                    ", location=" + location +
                    ", rotation=" + rotation +
                    ", size=" + size +
                    ", file=" + file +
                    ", fileDescriptor=" + fileDescriptor +
                    ", facing=" + facing +
                    ", videoCodec=" + videoCodec +
                    ", audio=" + audio +
                    ", endReason=" + endReason +
                    ", videoBitRate=" + videoBitRate +
                    ", videoFrameRate=" + videoFrameRate +
                    ", audioBitRate=" + audioBitRate +
                    ", scaleX=" + scaleX +
                    ", scaleY=" + scaleY +
                    '}';
        }
    }

    @SuppressWarnings({"WeakerAccess", "unused"})
    public static final int REASON_USER = 0;

    @SuppressWarnings("WeakerAccess")
    public static final int REASON_MAX_SIZE_REACHED = 1;

    @SuppressWarnings("WeakerAccess")
    public static final int REASON_MAX_DURATION_REACHED = 2;

    private final boolean isSnapshot;
    private final Location location;
    private final int rotation;
    private final Size size;
    private final File file;
    private final FileDescriptor fileDescriptor;
    private final Facing facing;
    private final VideoCodec videoCodec;
    private final Audio audio;
    private final int endReason;
    private final int videoBitRate;
    private final int videoFrameRate;
    private final int audioBitRate;

    VideoResult(@NonNull Stub builder) {
        isSnapshot = builder.isSnapshot;
        location = builder.location;
        rotation = builder.rotation;
        size = builder.size;
        file = builder.file;
        fileDescriptor = builder.fileDescriptor;
        facing = builder.facing;
        videoCodec = builder.videoCodec;
        audio = builder.audio;
        endReason = builder.endReason;
        videoBitRate = builder.videoBitRate;
        videoFrameRate = builder.videoFrameRate;
        audioBitRate = builder.audioBitRate;
    }

    /**
     * Returns whether this result comes from a snapshot.
     *
     * @return whether this is a snapshot
     */
    public boolean isSnapshot() {
        return isSnapshot;
    }

    /**
     * Returns geographic information for this video, if any.
     * If it was set, it is also present in the file metadata.
     *
     * @return a nullable Location
     */
    @Nullable
    public Location getLocation() {
        return location;
    }

    /**
     * Returns the clock-wise rotation that should be applied to the
     * video frames before displaying. If it is non-zero, it is also present
     * in the video metadata, so most reader will take care of it.
     *
     * @return the clock-wise rotation
     */
    public int getRotation() {
        return rotation;
    }

    /**
     * Returns the size of the frames after the rotation is applied.
     *
     * @return the Size of this video
     */
    @NonNull
    public Size getSize() {
        return size;
    }

    /**
     * Returns the file where the video was saved.
     *
     * @return the File of this video
     */
    @NonNull
    public File getFile() {
        if (file == null) {
            throw new RuntimeException("File is only available when takeVideo(File) is used.");
        }
        return file;
    }

    /**
     * Returns the file descriptor where the video was saved.
     *
     * @return the File Descriptor of this video
     */
    @NonNull
    public FileDescriptor getFileDescriptor() {
        if (fileDescriptor == null) {
            throw new RuntimeException("FileDescriptor is only available when takeVideo(FileDescriptor) is used.");
        }
        return fileDescriptor;
    }

    /**
     * Returns the facing value with which this video was recorded.
     *
     * @return the Facing of this video
     */
    @NonNull
    public Facing getFacing() {
        return facing;
    }

    /**
     * Returns the codec that was used to encode the video frames.
     *
     * @return the video codec
     */
    @NonNull
    public VideoCodec getVideoCodec() {
        return videoCodec;
    }

    /**
     * Returns the {@link Audio} setting for this video.
     *
     * @return the audio setting for this video
     */
    @NonNull
    public Audio getAudio() {
        return audio;
    }

    /**
     * Returns the reason why the recording was stopped.
     * @return one of {@link #REASON_USER}, {@link #REASON_MAX_DURATION_REACHED}
     *         or {@link #REASON_MAX_SIZE_REACHED}.
     */
    public int getTerminationReason() {
        return endReason;
    }

    /**
     * Returns the bit rate used for video encoding.
     *
     * @return the video bit rate
     */
    public int getVideoBitRate() {
        return videoBitRate;
    }

    /**
     * Returns the frame rate used for video encoding
     * in frames per second.
     *
     * @return the video frame rate
     */
    public int getVideoFrameRate() {
        return videoFrameRate;
    }

    /**
     * Returns the bit rate used for audio encoding.
     *
     * @return the audio bit rate
     */
    public int getAudioBitRate() {
        return audioBitRate;
    }

    @Override
    public String toString() {
        return "VideoResult{" +
                "isSnapshot=" + isSnapshot +
                ", location=" + location +
                ", rotation=" + rotation +
                ", size=" + size +
                ", file=" + file +
                ", fileDescriptor=" + fileDescriptor +
                ", facing=" + facing +
                ", videoCodec=" + videoCodec +
                ", audio=" + audio +
                ", endReason=" + endReason +
                ", videoBitRate=" + videoBitRate +
                ", videoFrameRate=" + videoFrameRate +
                ", audioBitRate=" + audioBitRate +
                '}';
    }
}
