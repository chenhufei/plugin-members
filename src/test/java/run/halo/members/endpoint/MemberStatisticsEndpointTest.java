package run.halo.members.endpoint;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import run.halo.members.service.MemberStatisticsService;

class MemberStatisticsEndpointTest {

    private static final String BASE = "/apis/api.member.plugin.halo.run/v1alpha1";
    private static final String TREND = BASE + "/members/-/statistics/trend";

    private final MemberStatisticsService statisticsService =
        mock(MemberStatisticsService.class);
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        MemberStatisticsEndpoint endpoint = new MemberStatisticsEndpoint(statisticsService);
        RouterFunction<ServerResponse> router = RouterFunctions.route()
            .path(BASE, () -> endpoint.endpoint())
            .build();
        client = WebTestClient.bindToRouterFunction(router).build();
        when(statisticsService.getApplicationTrend(anyInt()))
            .thenReturn(Mono.just(Map.of("2026-10-28", 1)));
    }

    @Test
    @DisplayName("趋势统计缺省查询 30 天")
    void usesDefaultDays() {
        client.get().uri(TREND).exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$['2026-10-28']").isEqualTo(1);
    }

    @Test
    @DisplayName("days 非数字返回 400 而不是 500")
    void nonNumericDaysIsBadRequest() {
        client.get().uri(TREND + "?days=abc").exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.type")
            .isEqualTo("https://halo.run/probs/invalid-member-statistics-query");
    }

    @Test
    @DisplayName("days 越界返回 400")
    void outOfRangeDaysIsBadRequest() {
        client.get().uri(TREND + "?days=999").exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.status").isEqualTo(400);
    }
}
