package com.sabinetek.mp4v2utils;

public class Mp4v2Helper {
    static {
        System.loadLibrary("Mp4v2Helper");
    }

    /**
     * 初始化MP4文件
     * @param fullPath mp4文件全路径名
     * @param width 视频宽
     * @param height 视频高
     * @param fps 视频帧率
     * @param channel 声道
     * @param samplerate 音频采样率
     * @return 1 if success, 0 if fail
     * */
    public native int init(String fullPath, int width, int height, int fps, int channel, int samplerate);

    /**
     * 添加视频轨
     * @param data 视频帧数据
     * @param dataLen 帧数据data的长度
     * @return -1 if failed, dataLen pack in if success
     * */
    public native int addVideoTrack(byte[] data, int dataLen);

    /**
     * 添加音频轨
     * @param data 音频帧数据
     * @param dataLen 帧数据data的长度
     * @return -1 if failed, dataLen pack in if success
     * */
    public native int addAudioTrack(byte[] data, int dataLen);

    /**
     * 封装视频数据帧
     * @param data 视频帧数据
     * @param dataLen 帧数据data的长度
     * @param presentationTimeUs 视频帧编码时间戳
     * @return -1 if failed, dataLen pack in if success
     * */
    public native int writeVideo(byte[] data, int dataLen, long presentationTimeUs);

    /**
     * 封装音频数据帧
     * @param data 音频帧数据
     * @param dataLen 帧数据data的长度
     * @param presentationTimeUs 音频帧编码时间戳
     * @return -1 if failed, dataLen pack in if success
     * */
    public native int writeAudio(byte[] data, int dataLen, long presentationTimeUs);

    /**
     * 结束并关闭Mp4文件
     * */
    public native void close();
}
