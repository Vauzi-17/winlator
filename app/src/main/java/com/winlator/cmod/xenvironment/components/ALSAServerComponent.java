package com.winlator.cmod.xenvironment.components;

import com.winlator.cmod.alsaserver.ALSAClientConnectionHandler;
import com.winlator.cmod.alsaserver.ALSARequestHandler;
import com.winlator.cmod.alsaserver.glibc.GlibcALSAClient;
import com.winlator.cmod.alsaserver.glibc.GlibcALSAClientConnectionHandler;
import com.winlator.cmod.alsaserver.glibc.GlibcALSARequestHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

public class ALSAServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final UnixSocketConfig socketConfig;
    private final boolean glibcProtocol;

    public ALSAServerComponent(UnixSocketConfig socketConfig) {
        this(socketConfig, false);
    }

    /** @param glibcProtocol use the aserver protocol of the glibc runtime's ALSA plugin */
    public ALSAServerComponent(UnixSocketConfig socketConfig, boolean glibcProtocol) {
        this.socketConfig = socketConfig;
        this.glibcProtocol = glibcProtocol;
    }

    @Override
    public void start() {
        if (connector != null) return;
        if (glibcProtocol) {
            GlibcALSAClient.assignFramesPerBuffer(environment.getContext());
            connector = new XConnectorEpoll(socketConfig, new GlibcALSAClientConnectionHandler(), new GlibcALSARequestHandler());
        }
        else connector = new XConnectorEpoll(socketConfig, new ALSAClientConnectionHandler(), new ALSARequestHandler());
        connector.setMultithreadedClients(true);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }
    }
}
