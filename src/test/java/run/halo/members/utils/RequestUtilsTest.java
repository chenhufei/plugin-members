package run.halo.members.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;

class RequestUtilsTest {

    @Test
    void ignoresForwardedHeadersFromPublicClients() {
        var request = MockServerRequest.builder()
            .remoteAddress(new InetSocketAddress("198.51.100.10", 8080))
            .header("X-Forwarded-For", "203.0.113.8")
            .build();

        assertThat(RequestUtils.getClientIP(request)).isEqualTo("198.51.100.10");
    }

    @Test
    void acceptsForwardedHeadersFromLocalProxy() {
        var request = MockServerRequest.builder()
            .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
            .header("X-Forwarded-For", "203.0.113.8")
            .build();

        assertThat(RequestUtils.getClientIP(request)).isEqualTo("203.0.113.8");
    }
}
