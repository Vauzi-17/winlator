package com.winlator.cmod.alsaserver.glibc;

import com.winlator.cmod.sysvshm.SysVSharedMemory;
import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.RequestHandler;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;

import java.io.IOException;
import java.nio.ByteBuffer;

/** aserver protocol of the glibc ALSA plugin (brunodev85/winlator). */
public class GlibcALSARequestHandler implements RequestHandler {
    private static final byte CLOSE = 0;
    private static final byte START = 1;
    private static final byte STOP = 2;
    private static final byte PAUSE = 3;
    private static final byte PREPARE = 4;
    private static final byte WRITE = 5;
    private static final byte DRAIN = 6;
    private static final byte POINTER = 7;
    private static final byte MIN_BUFFER_SIZE = 8;
    private int maxSHMemoryId = 0;

    @Override
    public boolean handleRequest(Client client) throws IOException {
        GlibcALSAClient alsaClient = (GlibcALSAClient)client.getTag();
        XInputStream inputStream = client.getInputStream();
        XOutputStream outputStream = client.getOutputStream();

        if (inputStream.available() < 5) return false;
        byte requestCode = inputStream.readByte();
        int requestLength = inputStream.readInt();

        switch (requestCode) {
            case CLOSE:
                alsaClient.release();
                break;
            case START:
                alsaClient.start();
                break;
            case STOP:
                alsaClient.stop();
                break;
            case PAUSE:
                alsaClient.pause();
                break;
            case PREPARE:
                if (inputStream.available() < requestLength) return false;

                alsaClient.setChannels(inputStream.readByte());
                alsaClient.setDataType(GlibcALSAClient.DataType.values()[inputStream.readByte()]);
                alsaClient.setSampleRate(inputStream.readInt());
                alsaClient.setBufferSize(inputStream.readInt());
                alsaClient.prepare();

                createSharedMemory(alsaClient, outputStream);
                break;
            case WRITE:
                ByteBuffer sharedBuffer = alsaClient.getSharedBuffer();
                if (sharedBuffer != null) {
                    copySharedBuffer(alsaClient, requestLength, outputStream);
                    alsaClient.writeDataToTrack(alsaClient.getAuxBuffer());
                    sharedBuffer.putInt(0, alsaClient.pointer());
                }
                else {
                    if (inputStream.available() < requestLength) return false;
                    alsaClient.writeDataToTrack(inputStream.readByteBuffer(requestLength));
                }
                break;
            case DRAIN:
                alsaClient.drain();
                break;
            case POINTER:
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeInt(alsaClient.pointer());
                }
                break;
            case MIN_BUFFER_SIZE:
                if (inputStream.available() < 6) return false;
                byte channels = inputStream.readByte();
                GlibcALSAClient.DataType dataType = GlibcALSAClient.DataType.values()[inputStream.readByte()];
                int sampleRate = inputStream.readInt();

                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeInt(GlibcALSAClient.minBufferSizeInBytes(channels, dataType, sampleRate));
                }
                break;
        }
        return true;
    }

    private void copySharedBuffer(GlibcALSAClient alsaClient, int requestLength, XOutputStream outputStream) throws IOException {
        ByteBuffer sharedBuffer = alsaClient.getSharedBuffer();
        ByteBuffer auxBuffer = alsaClient.getAuxBuffer();

        auxBuffer.position(0).limit(requestLength);
        sharedBuffer.position(GlibcALSAClient.BUFFER_OFFSET).limit(GlibcALSAClient.BUFFER_OFFSET + requestLength);
        auxBuffer.put(sharedBuffer);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte)1);
        }
    }

    private void createSharedMemory(GlibcALSAClient alsaClient, XOutputStream outputStream) throws IOException {
        int shmSize = alsaClient.getBufferSizeInBytes() + GlibcALSAClient.BUFFER_OFFSET;
        int fd = SysVSharedMemory.createMemoryFd("alsa-shm" + (++maxSHMemoryId), shmSize);

        if (fd >= 0) {
            ByteBuffer buffer = SysVSharedMemory.mapSHMSegment(fd, shmSize, 0, false);
            if (buffer != null) alsaClient.setSharedBuffer(buffer);
        }

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte)0);
            outputStream.setAncillaryFd(fd);
        }
        finally {
            if (fd >= 0) XConnectorEpoll.closeFd(fd);
        }
    }
}
