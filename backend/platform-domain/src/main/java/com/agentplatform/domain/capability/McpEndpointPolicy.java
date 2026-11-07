package com.agentplatform.domain.capability;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/** Syntax and literal-address admission only; the controlled egress must recheck resolved destinations on every connection. */
public final class McpEndpointPolicy {
    private McpEndpointPolicy() {}
    public static URI external(String input) {
        final URI uri;
        try { uri = URI.create(input); } catch (RuntimeException invalid) { throw invalid(); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque() || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getRawQuery() != null
                || uri.getPort() == 0 || uri.getPort() > 65535 || input.length() > 4096) throw invalid();
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        else if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.indexOf('%') >= 0 || host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")) throw invalid();
        if (host.indexOf(':') >= 0) {
            try { if (!publicAddress(InetAddress.getByName(host))) throw invalid(); }
            catch (java.net.UnknownHostException invalid) { throw invalid(); }
        } else if (host.matches("[0-9.]+")) {
            String[] parts = host.split("\\.", -1);
            if (parts.length != 4) throw invalid();
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.startsWith("0"))) throw invalid();
                if (Integer.parseInt(part) > 255) throw invalid();
            }
            try { if (!publicAddress(InetAddress.getByName(host))) throw invalid(); }
            catch (java.net.UnknownHostException invalid) { throw invalid(); }
        } else if (!host.contains(".")) throw invalid();
        return uri;
    }
    private static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = raw[0] & 255, b = raw[1] & 255, c = raw[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && (b == 168 || b == 0 && (c == 0 || c == 2) || b == 88 && c == 99))
                    || (a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
                    || (a == 203 && b == 0 && c == 113));
        }
        if (address instanceof Inet6Address) {
            int a = raw[0] & 255, b = raw[1] & 255, c = raw[2] & 255, d = raw[3] & 255;
            return (a & 0xe0) == 0x20 && !(a == 0x20 && b == 0x01 &&
                    (c == 0 && d == 0 || c == 0x0d && d == 0xb8 || c == 0 && (d & 0xf0) == 0x10));
        }
        return false;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("MCP_ENDPOINT_REJECTED"); }
}
