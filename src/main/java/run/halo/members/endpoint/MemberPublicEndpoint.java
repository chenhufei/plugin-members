package run.halo.members.endpoint;

import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.MetadataOperator;
import run.halo.app.plugin.ApiVersion;
import run.halo.members.Member;
import run.halo.members.finders.MemberFinder;
import run.halo.members.vo.MemberGroupVo;
import run.halo.members.vo.MemberVo;

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
            // 匿名接口必须给出保守的默认值：不传 size 时不能一路透传到「全量返回」
            size = queryInt(request, "size", 50, 1, 100);
        } catch (IllegalArgumentException e) {
            return ServerResponse.badRequest()
                .bodyValue(new ErrorResponse(e.getMessage()));
        }
        return memberFinder.listApprovedMemberList(page, size)
            .doOnSuccess(result -> log.debug("返回公开成员列表，共 {} 个成员", result.getTotal()))
            .map(MemberPublicEndpoint::toPublicView)
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
     *
     * <p>分组 VO 与成员列表复用同一套实例，历史上曾被 {@code listApprovedMembers}
     * 就地塞入成员列表，导致这个匿名接口把成员的 email / qq / status 一并吐出去。
     * 根因已在 finder 层修掉（改为返回副本），这里再做一层白名单兜底：
     * 即使上游又把成员塞进分组，也只允许公开展示字段出去。
     */
    private Mono<ServerResponse> listGroups(ServerRequest request) {
        return memberFinder.listAllGroups()
            .map(MemberPublicEndpoint::toPublicGroup)
            .collectList()
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

    /**
     * 匿名可读的成员视图：只保留公开展示字段，去掉邮箱、QQ 号、审核状态与内部标注。
     */
    static ListResult<MemberVo> toPublicView(ListResult<MemberVo> source) {
        List<MemberVo> items = source.getItems().stream()
            .map(MemberPublicEndpoint::toPublicView)
            .toList();
        return new ListResult<>(source.getPage(), source.getSize(), source.getTotal(), items);
    }

    static MemberVo toPublicView(MemberVo source) {
        var from = source.getSpec();
        Member.MemberSpec spec = new Member.MemberSpec();
        spec.setStatus(null);
        if (from != null) {
            spec.setDisplayName(from.getDisplayName());
            spec.setSchool(from.getSchool());
            spec.setAvatar(from.getAvatar());
            spec.setQqFriendLink(from.getQqFriendLink());
            spec.setGroupName(from.getGroupName());
        }
        Metadata metadata = new Metadata();
        MetadataOperator fromMetadata = source.getMetadata();
        if (fromMetadata != null) {
            metadata.setName(fromMetadata.getName());
            metadata.setCreationTimestamp(fromMetadata.getCreationTimestamp());
        }
        return MemberVo.builder().metadata(metadata).spec(spec).build();
    }

    /**
     * 公开分组视图：成员一律走 {@link #toPublicView(MemberVo)} 白名单，
     * 不让邮箱、QQ 号、审核状态通过分组接口漏给匿名访客。
     */
    static MemberGroupVo toPublicGroup(MemberGroupVo source) {
        List<MemberVo> members = source.getMembers() == null
            ? null
            : source.getMembers().stream().map(MemberPublicEndpoint::toPublicView).toList();
        return source.toBuilder().members(members).build();
    }
}
