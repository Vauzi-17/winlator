package com.winlator.cmod.xenvironment.components;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.system.OsConstants;

import androidx.annotation.NonNull;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xenvironment.GlibcRootFs;

import java.io.File;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Network interfaces for the glibc runtime's Wine. Android denies apps the netlink sockets that
 * getifaddrs() needs, so brunodev85's nsiproxy reads the interfaces from {rootfs}/tmp/ifaddrs
 * instead (one "name,flags,family,scopeId,address,netmask" line each), written here like
 * brunodev85's NetworkInfoUpdateComponent does. Without it Wine reports no usable adapter and
 * games enumerating adapters (e.g. PES 2021 through iphlpapi) can crash.
 */
public class GlibcNetworkInfoComponent extends EnvironmentComponent {
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private static class IFAddress {
        String name = "eth0";
        int flags = 0;
        int family = OsConstants.AF_INET;
        int scopeId = 0;
        String address = "0";
        String netmask = "0";

        @NonNull
        @Override
        public String toString() {
            return name+","+flags+","+family+","+scopeId+","+address+","+netmask;
        }
    }

    @Override
    public void start() {
        Context context = environment.getContext();
        connectivityManager = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        update();

        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(@NonNull Network network, @NonNull LinkProperties linkProperties) {
                update();
            }

            @Override
            public void onLost(@NonNull Network network) {
                update();
            }
        };

        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        }
        catch (RuntimeException e) {
            networkCallback = null;
        }
    }

    @Override
    public void stop() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
            catch (RuntimeException e) {}
        }
        networkCallback = null;
    }

    private synchronized void update() {
        GlibcRootFs rootFs = GlibcRootFs.find(environment.getContext());
        LinkProperties linkProperties = getActiveLinkProperties();

        List<IFAddress> ifAddresses = getIFAddresses(linkProperties);
        StringBuilder content = new StringBuilder();
        for (IFAddress ifAddress : ifAddresses) {
            if (content.length() > 0) content.append("\n");
            content.append(ifAddress);
        }
        if (content.length() == 0) content.append(new IFAddress());

        File tmpDir = rootFs.getTmpDir();
        if (!tmpDir.isDirectory()) tmpDir.mkdirs();
        FileUtils.writeString(new File(tmpDir, "ifaddrs"), content.toString());

        String ipAddress = getIPv4Address(linkProperties);
        FileUtils.writeString(new File(rootFs.getRootDir(), "etc/hosts"), (ipAddress != null ? ipAddress : "127.0.0.1")+"\tlocalhost\n");
    }

    private LinkProperties getActiveLinkProperties() {
        if (connectivityManager == null) return null;
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            return activeNetwork != null ? connectivityManager.getLinkProperties(activeNetwork) : null;
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    private static List<IFAddress> getIFAddresses(LinkProperties linkProperties) {
        ArrayList<IFAddress> result = new ArrayList<>();
        if (linkProperties == null) return result;

        for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
            InetAddress address = linkAddress.getAddress();
            if (!(address instanceof Inet4Address || address instanceof Inet6Address)) continue;

            IFAddress ifAddress = new IFAddress();
            if (address instanceof Inet6Address) {
                ifAddress.family = OsConstants.AF_INET6;
                ifAddress.scopeId = ((Inet6Address)address).getScopeId();
            }

            ifAddress.address = address.getHostAddress();
            ifAddress.netmask = formatNetmask(linkAddress.getPrefixLength());
            ifAddress.flags = OsConstants.IFF_UP | OsConstants.IFF_RUNNING;
            result.add(ifAddress);
        }
        return result;
    }

    private static String getIPv4Address(LinkProperties linkProperties) {
        if (linkProperties == null) return null;
        for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
            InetAddress address = linkAddress.getAddress();
            if (address instanceof Inet4Address) return address.getHostAddress();
        }
        return null;
    }

    private static String formatNetmask(int prefixLength) {
        switch (prefixLength) {
            case 8: return "255.0.0.0";
            case 16: return "255.255.0.0";
            case 24: return "255.255.255.0";
            case 32: return "255.255.255.255";
            case 64: return "ffff:ffff:ffff:ffff::";
            default: return "";
        }
    }
}
