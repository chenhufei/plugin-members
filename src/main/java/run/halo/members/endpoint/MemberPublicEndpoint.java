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
        Integer page = queryInt(request, "page");
        Integer size = queryInt(request, "size");
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

    private Integer queryInt(ServerRequest request, String name) {
        return request.queryParam(name)
            .map(value -> {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    return null;
                }
            })
            .orElse(null);
    }

    /**
     * 错误响应
     */
    public record ErrorResponse(String message) {}
}
