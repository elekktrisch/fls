package ch.alpenflight.publicregistration.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class ClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final Pattern IPV4_LITERAL = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

    String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        String peerAddress = peer == null ? "" : peer;
        InetAddress peerIp = parseLiteral(peerAddress);
        if (peerIp == null || !isInfrastructure(peerIp)) {
            return peerIp == null ? peerAddress : peerIp.getHostAddress();
        }
        List<String> hops = forwardedHops(request);
        for (int i = hops.size() - 1; i >= 0; i--) {
            InetAddress hop = parseLiteral(hops.get(i));
            if (hop != null && !isInfrastructure(hop)) {
                return hop.getHostAddress();
            }
        }
        return peerIp.getHostAddress();
    }

    private static List<String> forwardedHops(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(FORWARDED_FOR);
        if (values == null) {
            return List.of();
        }
        List<String> hops = new ArrayList<>();
        while (values.hasMoreElements()) {
            for (String part : values.nextElement().split(",", -1)) {
                String hop = stripPort(part.trim());
                if (!hop.isEmpty()) {
                    hops.add(hop);
                }
            }
        }
        return hops;
    }

    private static String stripPort(String hop) {
        if (hop.startsWith("[")) {
            int close = hop.indexOf(']');
            return close < 0 ? hop : hop.substring(1, close);
        }
        int colon = hop.indexOf(':');
        boolean ipv4WithPort = colon > 0 && hop.indexOf(':', colon + 1) < 0;
        return ipv4WithPort ? hop.substring(0, colon) : hop;
    }

    private static @Nullable InetAddress parseLiteral(String candidate) {
        boolean literal = IPV4_LITERAL.matcher(candidate).matches() || candidate.indexOf(':') >= 0;
        if (!literal) {
            return null;
        }
        try {
            return InetAddress.getByName(candidate);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean isInfrastructure(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || isUniqueLocalV6(address);
    }

    private static boolean isUniqueLocalV6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
