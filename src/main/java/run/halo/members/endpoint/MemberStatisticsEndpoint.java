package run.halo.members.endpoint;

import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.members.service.MemberStatisticsService;

/**
 * 成员统计分析 API 端点
 * 提供各种统计数据和分析功能
 * 
 * @author Sky
 * @since 2.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberStatisticsEndpoint implements CustomEndpoint {

    private final MemberStatisticsService statisticsService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.member.plugin.halo.run/v1alpha1/MemberStatistics";
        return route()
            .GET("members/-/statistics/overall", this::getOverallStatistics,
                builder -> builder.operationId("GetOverallStatistics")
                    .description("获取总体统计信息")
                    .tag(tag))
            .GET("members/-/statistics/by-group", this::getGroupStatistics,
                builder -> builder.operationId("GetGroupStatistics")
                    .description("获取按分组统计")
                    .tag(tag))
            .GET("members/-/statistics/by-status", this::getStatusStatistics,
                builder -> builder.operationId("GetStatusStatistics")
                    .description("获取按状态统计")
                    .tag(tag))
            .GET("members/-/statistics/by-school", this::getSchoolStatistics,
                builder -> builder.operationId("GetSchoolStatistics")
                    .description("获取按学校统计（Top 10）")
                    .tag(tag))
            .GET("members/-/statistics/trend", this::getApplicationTrend,
                builder -> builder.operationId("GetApplicationTrend")
                    .description("获取申请趋势")
                    .tag(tag))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.member.plugin.halo.run/v1alpha1");
    }

    /**
     * 获取总体统计信息
     */
    private Mono<ServerResponse> getOverallStatistics(ServerRequest request) {
        log.debug("请求总体统计信息");
        return statisticsService.getOverallStatistics()
            .flatMap(stats -> ServerResponse.ok().bodyValue(stats))
            .doOnError(error -> log.error("获取总体统计失败", error));
    }

    /**
     * 获取按分组统计
     */
    private Mono<ServerResponse> getGroupStatistics(ServerRequest request) {
        log.debug("请求分组统计");
        return statisticsService.getGroupStatistics()
            .flatMap(stats -> ServerResponse.ok().bodyValue(stats))
            .doOnError(error -> log.error("获取分组统计失败", error));
    }

    /**
     * 获取按状态统计
     */
    private Mono<ServerResponse> getStatusStatistics(ServerRequest request) {
        log.debug("请求状态统计");
        return statisticsService.getStatusStatistics()
            .flatMap(stats -> ServerResponse.ok().bodyValue(stats))
            .doOnError(error -> log.error("获取状态统计失败", error));
    }

    /**
     * 获取按学校统计
     */
    private Mono<ServerResponse> getSchoolStatistics(ServerRequest request) {
        log.debug("请求学校统计");
        return statisticsService.getSchoolStatistics()
            .flatMap(stats -> ServerResponse.ok().bodyValue(stats))
            .doOnError(error -> log.error("获取学校统计失败", error));
    }

    /**
     * 获取申请趋势
     */
    private Mono<ServerResponse> getApplicationTrend(ServerRequest request) {
        int days = request.queryParam("days")
            .map(param -> {
                try {
                    int value = Integer.parseInt(param);
                    if (value < 1 || value > 365) {
                        throw new IllegalArgumentException("days 参数必须在 1-365 之间");
                    }
                    return value;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("days 参数格式不正确");
                }
            })
            .orElse(30);
        
        return statisticsService.getApplicationTrend(days)
            .flatMap(trend -> ServerResponse.ok().bodyValue(trend))
            .doOnError(error -> log.error("获取申请趋势失败", error));
    }
}
