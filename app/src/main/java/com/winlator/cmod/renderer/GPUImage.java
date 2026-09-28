package com.winlator.cmod.renderer;

import androidx.annotation.Keep;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Window;
import java.nio.ByteBuffer;

public class GPUImage {
    public long hardwareBufferPtr;
    public int format;
    private ByteBuffer virtualData;
    private short stride;
    private static boolean supported = false;

    static {
        System.loadLibrary("winlator");
    }

    public GPUImage(short width, short height) {
        hardwareBufferPtr = createHardwareBuffer(width, height);
        if (hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            if (virtualData == null) {
                System.err.println("Error: Failed to lock hardware buffer");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        } else {
            System.err.println("Error: Failed to create hardware buffer");
        }
    }
    
    /** Wraps a hardware buffer owned by the caller. */
    public GPUImage(long hardwareBufferPtr, int format) {
        this.hardwareBufferPtr = hardwareBufferPtr;
        this.format = format;
    }

    public GPUImage(int socketFd) {
        hardwareBufferPtr = nativeHardwareBufferFromSocket(socketFd);
        if (hardwareBufferPtr == 0)
            System.err.println("Error: Failed to create hardware buffer");
    }

    public short getStride() {
        return stride;
    }

    @Keep
    private void setStride(short stride) {
        this.stride = stride;
    }

    public ByteBuffer getVirtualData() {
        return virtualData;
    }

    public static boolean isSupported() {
        return supported;
    }

    private native long nativeHardwareBufferFromSocket(int fd);
    
    private native long createHardwareBuffer(short width, short height);

    private native void destroyHardwareBuffer(long hardwareBufferPtr);

    private native ByteBuffer lockHardwareBuffer(long hardwareBufferPtr);
}
    
