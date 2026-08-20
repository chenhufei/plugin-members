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
import run.halo.app.plugin.ApiVersion;
import run.halo.members.finders.MemberFinder;

/**
 * 成员公开查询 API 端点（无需登录）
 * 提供给前台主题使用的公开 API
 * 
 * 使用 /apis/api.plugin.halo.run/v1alpha1/plugins/PluginMembers/members 路径
 * 这个路径是 Halo 的公开 API 路径，不需要登录权限
 * 
 * @author Sky
 * @since 2.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ApiVersion("api.plugin.halo.run/v1alpha1")
public class MemberPublicEndpoint implements CustomEndpoint {

    private final MemberFinder memberFinder;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.plugin.halo.run/v1alpha1/MemberPublic";
        return route()
            .GET("/plugins/PluginMembers/members", this::listMembers,
                builder -> builder.operationId("ListPublicMembers")
                    .description("List all approved members (public API, no authentication required)")
                    .tag(tag))
            .GET("/plugins/PluginMembers/membergroups", this::listGroups,
                builder -> builder.operationId("ListPublicMemberGroups")
                    .description("List all member groups (public API, no authentication required)")
                    .tag(tag))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.plugin.halo.run/v1alpha1");
    }

    /**
     * 获取所有已审核通过的成员列表
     * 公开 API，不需要登录
     * 返回简单的成员列表（与旧 API 兼容）
     */
    private Mono<ServerResponse> listMembers(ServerRequest request) {
        final Integer page;
        final Integer size;
        try {
            page = queryInt(request, "page", 1, 1, 1_000_000);
            size = queryInt(request, "size", null, 1, 100);
        } catch (IllegalArgumentException e) {
            return ServerResponse.badRequest()
                .bodyValue(new ErrorResponse(e.getMessage()));
        }
        return memberFinder.listApprovedMemberList(page, size)
            .doOnSuccess(result -> log.debug("返回公开成员列表，共 {} 个成员", result.getTotal()))
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .doOnError(error -> log.error("获取成员列表失败", error))
            .onErrorResume(error ->
                ServerResponse.status(500)
                    .bodyValue(new ErrorResponse("获取成员列表失败，请稍后重试"))
            );
    }

    /**
     * 获取所有分组
     * 公开 API，不需要登录
     */
    private Mono<ServerResponse> listGroups(ServerRequest request) {
        return memberFinder.listAllGroups().collectList()
            .flatMap(groups -> {
                log.debug("返回分组列表，共 {} 个分组", groups.size());
                return ServerResponse.ok().bodyValue(groups);
            })
            .doOnError(error -> log.error("获取分组列表失败", error))
            .onErrorResume(error ->
                ServerResponse.status(500)
                    .bodyValue(new ErrorResponse("获取分组列表失败，请稍后重试"))
            );
    }

    private Integer queryInt(ServerRequest request, String name, Integer defaultValue,
        int minimum, int maximum) {
        return request.queryParam(name)
            .map(value -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(name + " 参数不能为空");
                }
                return parseQueryInt(value, name, minimum, maximum);
            })
            .orElse(defaultValue);
    }

    static int parseQueryInt(String value, String name, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(
                    name + " 参数必须在 " + minimum + " 到 " + maximum + " 之间");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 参数必须是数字");
        }
    }

    /**
     * 错误响应
     */
    public record ErrorResponse(String message) {}
}
