package com.winlator.cmod.xenvironment.components;

import android.content.Context;
import android.util.SparseArray;

import androidx.annotation.Keep;

import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.ConnectionHandler;
import com.winlator.cmod.xconnector.RequestHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XServer;

import java.io.IOException;

/**
 * Server side of brunodev85's Vortek Vulkan driver for glibc containers: the libvulkan_vortek ICD
 * in the rootfs serializes Vulkan calls over {rootfs}/tmp/.vortek/V0 and this component replays
 * them on Android's own Vulkan driver (libvortekrenderer). Swapchains render into a GPU buffer per
 * window, shown by the X server renderer as direct content like DRI3 pixmaps.
 */
public class VortekRendererComponent extends EnvironmentComponent implements ConnectionHandler, RequestHandler {
    public static final String SERVER_PATH = "/tmp/.vortek/V0";
    private static final byte REQUEST_CODE_CREATE_CONTEXT = 1;
    private static final byte REQUEST_CODE_SEND_EXTRA_DATA = 2;
    private static final int HAL_PIXEL_FORMAT_RGBA_8888 = 1;
    public static final short IMAGE_CACHE_SIZE = 256;
    public static final int VK_MAX_VERSION = vkMakeVersion(1, 3, 128);
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;
    private final Options options;
    private XConnectorEpoll connector;
    private final SparseArray<Drawable> windowBuffers = new SparseArray<>();
    private int nextDrawableId = -1;

    static {
        System.loadLibrary("vortekrenderer");
    }

    /** Read by the native side by field name. */
    public static class Options {
        public int vkMaxVersion = VK_MAX_VERSION;
        public short maxDeviceMemory = 0;
        public short imageCacheSize = IMAGE_CACHE_SIZE;
        public byte resourceMemoryType = 0;
        public String[] exposedDeviceExtensions = null;
        public String libvulkanPath = null;
    }

    public VortekRendererComponent(Context context, XServer xServer, UnixSocketConfig socketConfig, Options options) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
        this.options = options;

        setCacheDir(context.getCacheDir().getPath());
        initVulkanWrapper(context.getApplicationInfo().nativeLibraryDir, options.libvulkanPath);
    }

    public static int vkMakeVersion(int major, int minor, int patch) {
        return (major << 22) | (minor << 12) | patch;
    }

    @Override
    public void start() {
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, this, this);
        connector.setInitialInputBufferCapacity(8);
        connector.setInitialOutputBufferCapacity(0);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }

        synchronized (windowBuffers) {
            XServerView xServerView = xServer.getXServerView();
            for (int i = 0; i < windowBuffers.size(); i++) {
                Drawable drawable = windowBuffers.valueAt(i);
                if (xServerView != null) xServerView.nativeRemoveDirectContent(windowBuffers.keyAt(i), drawable.id);
                releaseWindowBuffer(drawable.backingAHB);
            }
            windowBuffers.clear();
        }
    }

    @Keep
    private int getWindowWidth(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        return window != null ? window.getWidth() : 0;
    }

    @Keep
    private int getWindowHeight(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        return window != null ? window.getHeight() : 0;
    }

    @Keep
    private long getWindowHardwareBuffer(int windowId, boolean useHALPixelFormatBGRA8888) {
        Window window = xServer.windowManager.getWindow(windowId);
        XServerView xServerView = xServer.getXServerView();
        if (window == null || xServerView == null) return 0;

        short width = window.getWidth();
        short height = window.getHeight();
        int format = useHALPixelFormatBGRA8888 ? Drawable.HAL_PIXEL_FORMAT_BGRA_8888 : HAL_PIXEL_FORMAT_RGBA_8888;

        synchronized (windowBuffers) {
            Drawable current = windowBuffers.get(windowId);
            if (current != null && current.width == width && current.height == height && current.format == format) {
                return current.backingAHB;
            }

            long hardwareBufferPtr = createWindowBuffer(width, height, format);
            if (hardwareBufferPtr == 0) return 0;

            Drawable drawable = new Drawable(nextDrawableId--, width, height, xServer.pixmapManager.visual, new GPUImage(hardwareBufferPtr, format));
            xServerView.nativeAddDirectContent(windowId, drawable);
            if (current != null) {
                xServerView.nativeRemoveDirectContent(windowId, current.id);
                releaseWindowBuffer(current.backingAHB);
            }
            windowBuffers.put(windowId, drawable);
            return hardwareBufferPtr;
        }
    }

    @Keep
    private void updateWindowContent(int windowId) {
        Window window = xServer.windowManager.getWindow(windowId);
        Drawable drawable;
        synchronized (windowBuffers) {
            drawable = windowBuffers.get(windowId);
        }
        if (window != null && drawable != null) xServer.windowManager.triggerOnUpdateWindowContentDirect(window, drawable);
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        if (client.getTag() != null) {
            long contextPtr = (long)client.getTag();
            client.setTag(null);
            destroyVkContext(contextPtr);
        }
    }

    @Override
    public void handleNewConnection(Client client) {}

    @Override
    public boolean handleRequest(Client client) throws IOException {
        XInputStream inputStream = client.getInputStream();
        if (inputStream.available() < 8) return false;
        int requestCode = inputStream.readInt();
        int requestLength = inputStream.readInt();

        if (requestCode == REQUEST_CODE_CREATE_CONTEXT) {
            long contextPtr = createVkContext(client.clientSocket.fd, options);
            if (contextPtr > 0) {
                client.setTag(contextPtr);
            }
            else connector.killConnection(client);
        }
        else if (requestCode > Short.MAX_VALUE && (requestCode >> 16) == REQUEST_CODE_SEND_EXTRA_DATA) {
            int requestId = requestCode & 0xffff;
            if (client.getTag() == null) throw new IOException("Vortek extra data before a context.");
            long contextPtr = (long)client.getTag();
            boolean success = handleExtraDataRequest(contextPtr, requestId, requestLength);
            if (!success) throw new IOException("Failed to handle extra data request.");
        }

        return true;
    }

    private native long createVkContext(int clientFd, Options options);

    private native void destroyVkContext(long contextPtr);

    private native void initVulkanWrapper(String nativeLibraryDir, String libvulkanPath);

    private native boolean handleExtraDataRequest(long contextPtr, int requestCode, int requestLength);

    private static native long createWindowBuffer(int width, int height, int format);

    private static native void releaseWindowBuffer(long hardwareBufferPtr);

    private static native void setCacheDir(String cacheDir);
}
