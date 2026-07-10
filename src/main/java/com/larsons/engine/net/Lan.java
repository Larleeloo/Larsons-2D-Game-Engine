package com.larsons.engine.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * Local-network address discovery, for telling a host what their friends on
 * the same network should type to join ({@code localhost} only works on the
 * host's own machine — other machines need the host's LAN IP).
 */
public final class Lan {

    private Lan() {}

    /**
     * This machine's site-local IPv4 address (e.g. {@code 192.168.1.23}), or
     * {@code null} when none is up — the address other machines on the same
     * network can reach this one at.
     */
    public static String siteLocalAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                try {
                    if (!iface.isUp() || iface.isLoopback()) continue;
                } catch (SocketException e) {
                    continue;
                }
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
            // no network info available; caller shows a generic hint
        }
        return null;
    }
}
