package com.winlator.cmod.alsaserver.glibc;

import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.ConnectionHandler;

public class GlibcALSAClientConnectionHandler implements ConnectionHandler {
    @Override
    public void handleNewConnection(Client client) {
        client.createIOStreams();
        client.setTag(new GlibcALSAClient());
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        if (client.getTag() != null) ((GlibcALSAClient)client.getTag()).release();
    }
}
