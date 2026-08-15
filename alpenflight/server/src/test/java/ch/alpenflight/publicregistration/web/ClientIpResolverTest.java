package ch.alpenflight.publicregistration.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    private static final String HEADER = "X-Forwarded-For";
    private static final String PUBLIC_CLIENT = "203.0.113.9";
    private static final String PROXY_ON_THE_COMPOSE_NETWORK = "172.18.0.5";

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    void a_direct_caller_cannot_forge_a_source_with_a_forwarded_header() {
        assertThat(resolve("198.51.100.7", "203.0.113.1")).isEqualTo("198.51.100.7");
    }

    @Test
    void a_direct_caller_gets_one_key_however_many_hops_it_invents() {
        String first = resolve("198.51.100.7", "203.0.113.1");
        String second = resolve("198.51.100.7", "203.0.113.2, 203.0.113.3");

        assertThat(second).isEqualTo(first);
    }

    @Test
    void behind_infrastructure_the_client_is_read_out_of_the_forwarded_chain() {
        assertThat(resolve(PROXY_ON_THE_COMPOSE_NETWORK, PUBLIC_CLIENT + ", 10.0.0.7"))
                .isEqualTo(PUBLIC_CLIENT);
    }

    @Test
    void a_prepended_hop_cannot_impersonate_another_client() {
        assertThat(resolve(PROXY_ON_THE_COMPOSE_NETWORK, "198.51.100.7, " + PUBLIC_CLIENT))
                .isEqualTo(PUBLIC_CLIENT);
    }

    @Test
    void behind_infrastructure_without_a_forwarded_header_the_peer_is_the_source() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(PROXY_ON_THE_COMPOSE_NETWORK);

        assertThat(resolver.resolve(request)).isEqualTo(PROXY_ON_THE_COMPOSE_NETWORK);
    }

    @Test
    void a_hop_that_is_not_an_ip_literal_is_skipped_rather_than_resolved() {
        assertThat(resolve(PROXY_ON_THE_COMPOSE_NETWORK, "evil.example.com"))
                .isEqualTo(PROXY_ON_THE_COMPOSE_NETWORK);
        assertThat(resolve(PROXY_ON_THE_COMPOSE_NETWORK, "999.999.999.999"))
                .isEqualTo(PROXY_ON_THE_COMPOSE_NETWORK);
    }

    @Test
    void a_hop_carrying_a_port_keys_on_the_address_alone() {
        assertThat(resolve(PROXY_ON_THE_COMPOSE_NETWORK, PUBLIC_CLIENT + ":41234"))
                .isEqualTo(PUBLIC_CLIENT);
    }

    @Test
    void repeated_forwarded_headers_are_one_chain() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(PROXY_ON_THE_COMPOSE_NETWORK);
        request.addHeader(HEADER, "198.51.100.7");
        request.addHeader(HEADER, PUBLIC_CLIENT + ", 10.0.0.7");

        assertThat(resolver.resolve(request)).isEqualTo(PUBLIC_CLIENT);
    }

    @Test
    void an_ipv6_client_keys_on_its_canonical_form() {
        assertThat(resolve("fd00::1", "2001:DB8::1")).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    private String resolve(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader(HEADER, forwardedFor);
        return resolver.resolve(request);
    }
}
