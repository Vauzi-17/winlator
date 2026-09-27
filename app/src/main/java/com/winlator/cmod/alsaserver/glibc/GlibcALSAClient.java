package com.winlator.cmod.alsaserver.glibc;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.winlator.cmod.sysvshm.SysVSharedMemory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * ALSA client of the glibc runtime, speaking the aserver protocol of the ALSA plugin shipped in the
 * brunodev85/winlator rootfs (shared memory with a 4 byte pointer header, MIN_BUFFER_SIZE request).
 * The bionic ALSA plugin uses the older protocol handled by {@link com.winlator.cmod.alsaserver.ALSAClient}.
 */
public class GlibcALSAClient {
    public static final byte BUFFER_OFFSET = 4;
    private static final short DEFAULT_LATENCY_MILLIS = 16;
    public enum DataType {
        U8(1), S16LE(2), S16BE(2), FLOATLE(4), FLOATBE(4);
        public final byte byteCount;

        DataType(int byteCount) {
            this.byteCount = (byte)byteCount;
        }
    }
    private DataType dataType = DataType.U8;
    private AudioTrack audioTrack = null;
    private byte channels = 2;
    private int sampleRate = 0;
    private int position;
    private int bufferSize;
    private byte frameBytes;
    private short previousUnderrunCount = 0;
    private int bufferCapacity;
    private ByteBuffer sharedBuffer;
    private ByteBuffer auxBuffer;
    private static short framesPerBuffer = 256;

    public void release() {
        if (sharedBuffer != null) {
            SysVSharedMemory.unmapSHMSegment(sharedBuffer, sharedBuffer.capacity());
            sharedBuffer = null;
        }

        if (audioTrack != null) {
            audioTrack.pause();
            audioTrack.flush();
            audioTrack.release();
            audioTrack = null;
        }
    }

    private static int getPCMEncoding(DataType dataType) {
        switch (dataType) {
            case U8:
                return AudioFormat.ENCODING_PCM_8BIT;
            case S16LE:
            case S16BE:
                return AudioFormat.ENCODING_PCM_16BIT;
            case FLOATLE:
            case FLOATBE:
                return AudioFormat.ENCODING_PCM_FLOAT;
            default:
                return AudioFormat.ENCODING_DEFAULT;
        }
    }

    public void prepare() {
        position = 0;
        previousUnderrunCount = 0;
        frameBytes = (byte)(channels * dataType.byteCount);
        release();

        if (!isValidBufferSize()) return;

        AudioFormat format = new AudioFormat.Builder()
            .setEncoding(getPCMEncoding(dataType))
            .setSampleRate(sampleRate)
            .setChannelMask(channels <= 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO)
            .build();

        audioTrack = new AudioTrack.Builder()
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setAudioFormat(format)
            .setBufferSizeInBytes(getBufferSizeInBytes())
            .build();

        bufferCapacity = audioTrack.getBufferCapacityInFrames();
        audioTrack.play();
    }

    public void start() {
        if (audioTrack != null && audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) audioTrack.play();
    }

    public void stop() {
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.flush();
        }
    }

    public void pause() {
        if (audioTrack != null) audioTrack.pause();
    }

    public void drain() {
        if (audioTrack != null) audioTrack.flush();
    }

    public void writeDataToTrack(ByteBuffer data) {
        if (dataType == DataType.S16LE || dataType == DataType.FLOATLE) {
            data.order(ByteOrder.LITTLE_ENDIAN);
        }
        else if (dataType == DataType.S16BE || dataType == DataType.FLOATBE) {
            data.order(ByteOrder.BIG_ENDIAN);
        }

        if (audioTrack != null) {
            int bytesWritten;
            data.position(0);

            do {
                try {
                    bytesWritten = audioTrack.write(data, data.remaining(), AudioTrack.WRITE_BLOCKING);
                    if (bytesWritten < 0) break;
                    increaseBufferSizeIfUnderrunOccurs();
                }
                catch (Exception e) {
                    break;
                }
            }
            while (data.position() != data.limit());

            position += data.position();
            data.rewind();
        }
    }

    private void increaseBufferSizeIfUnderrunOccurs() {
        int underrunCount = audioTrack.getUnderrunCount();
        if (underrunCount > previousUnderrunCount && bufferSize < bufferCapacity) {
            previousUnderrunCount = (short)underrunCount;
            bufferSize += framesPerBuffer;
            audioTrack.setBufferSizeInFrames(bufferSize);
        }
    }

    public int pointer() {
        return audioTrack != null ? position / frameBytes : 0;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public void setChannels(int channels) {
        this.channels = (byte)channels;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public ByteBuffer getSharedBuffer() {
        return sharedBuffer;
    }

    public void setSharedBuffer(ByteBuffer sharedBuffer) {
        if (sharedBuffer != null) {
            auxBuffer = ByteBuffer.allocateDirect(getBufferSizeInBytes()).order(ByteOrder.LITTLE_ENDIAN);
            this.sharedBuffer = sharedBuffer.order(ByteOrder.LITTLE_ENDIAN);
        }
        else {
            auxBuffer = null;
            this.sharedBuffer = null;
        }
    }

    public ByteBuffer getAuxBuffer() {
        return auxBuffer;
    }

    public int getBufferSizeInBytes() {
        return bufferSize * frameBytes;
    }

    public static int minBufferSizeInBytes(int channels, DataType dataType, int sampleRate) {
        byte frameBytes = (byte)(channels * dataType.byteCount);
        int frames = Math.round((DEFAULT_LATENCY_MILLIS * sampleRate) / 1000.0f / framesPerBuffer) * framesPerBuffer;
        return Math.max(frames, framesPerBuffer) * frameBytes;
    }

    private boolean isValidBufferSize() {
        return frameBytes > 0 && (bufferSize % frameBytes) == 0 && bufferSize > 0;
    }

    public static void assignFramesPerBuffer(Context context) {
        try {
            AudioManager am = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            framesPerBuffer = Short.parseShort(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
            if (framesPerBuffer <= 0) framesPerBuffer = 256;
        }
        catch (Exception e) {
            framesPerBuffer = 256;
        }
    }
}
