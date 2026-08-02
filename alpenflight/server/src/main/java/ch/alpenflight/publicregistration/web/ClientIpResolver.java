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

/**
 * The source address the anonymous abuse guard buckets on. Getting this wrong
 * breaks the guard in one of two opposite ways, so the trust rule is explicit
 * rather than inherited from a container default:
 *
 * <ul>
 *   <li>trust {@code getRemoteAddr()} unconditionally and, behind a reverse
 *       proxy, every visitor collapses onto the proxy's address — the first
 *       registrant would lock out every other one;</li>
 *   <li>trust {@code X-Forwarded-For} unconditionally and any caller spoofs a
 *       fresh source per request, which is the guard doing nothing.</li>
 * </ul>
 *
 * <p>So the header is believed only when the immediate peer is infrastructure —
 * loopback, link-local, RFC 1918 / site-local, or IPv6 unique-local. A caller
 * arriving over the public internet has a public peer address, its
 * {@code X-Forwarded-For} is ignored outright, and the peer wins. This is the
 * {@code RemoteIpValve} trust model, applied at the one seam that needs it
 * instead of by flipping {@code server.forward-headers-strategy} for the whole
 * application: that property is unset here, and turning it on would also let
 * forwarded scheme/host headers rewrite every request in the app.
 *
 * <p>Both deployment shapes work under this rule with no configuration. Today
 * nothing terminates HTTP in front of the server, so the peer IS the client.
 * Under the ADR 0010 day-1 target — single VPS, Docker Compose, Caddy/Traefik
 * terminating TLS — the proxy reaches the server over the private compose
 * network, so its hop is infrastructure and the real client is read out of the
 * chain.
 *
 * <p>Residual risk accepted: anything that can already reach the server from a
 * private address can spoof a source. On a single-VPS install that is our own
 * containers.
 */
@Component
class ClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    /**
     * Strict dotted-quad. Loose matching would hand non-literals to
     * {@link InetAddress#getByName}, which resolves them — a header value must
     * never trigger a DNS lookup.
     */
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

    /** Every forwarded hop, left to right — the rightmost is the nearest proxy's view. */
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

    /** fc00::/7 — {@code isSiteLocalAddress} only covers the deprecated fec0::/10. */
    private static boolean isUniqueLocalV6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
