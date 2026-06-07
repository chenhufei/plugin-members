package run.halo.members.endpoint;

import java.util.List;

import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.members.service.MemberBatchService;
import run.halo.members.service.MemberQueryService;
import run.halo.members.service.MemberQueryService.MemberQuery;

/**
 * 成员批量操作 API 端点
 * 提供批量审核、删除、导出等功能
 * 
 * @author Sky
 * @since 2.1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberBatchEndpoint implements CustomEndpoint {

    private final MemberBatchService batchService;
    private final MemberQueryService memberQueryService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.member.plugin.halo.run/v1alpha1/MemberBatch";
        return route()
            .GET("members", this::listMembers,
                builder -> builder.operationId("QueryMembers")
                    .description("分页查询成员")
                    .tag(tag))
            .POST("members/-/batch-approve", this::batchApprove,
                builder -> builder.operationId("BatchApproveMember")
                    .description("批量审核成员")
                    .tag(tag))
            .POST("members/-/batch-delete", this::batchDelete,
                builder -> builder.operationId("BatchDeleteMember")
                    .description("批量删除成员")
                    .tag(tag))
            .POST("members/-/batch-change-group", this::batchChangeGroup,
                builder -> builder.operationId("BatchChangeGroup")
                    .description("批量修改分组")
                    .tag(tag))
            .POST("members/-/batch-change-priority", this::batchChangePriority,
                builder -> builder.operationId("BatchChangePriority")
                    .description("批量修改优先级")
                    .tag(tag))
            .POST("members/-/export-csv", this::exportCSV,
                builder -> builder.operationId("ExportMembersCSV")
                    .description("导出成员数据（CSV）")
                    .tag(tag))
            .POST("members/-/export-json", this::exportJSON,
                builder -> builder.operationId("ExportMembersJSON")
                    .description("导出成员数据（JSON）")
                    .tag(tag))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.member.plugin.halo.run/v1alpha1");
    }

    /**
     * 查询成员列表
     */
    private Mono<ServerResponse> listMembers(ServerRequest request) {
        MemberQuery query = new MemberQuery(
            queryInt(request, "page", 1),
            queryInt(request, "size", 20),
            queryString(request, "keyword"),
            queryString(request, "status"),
            queryString(request, "groupName"),
            queryString(request, "sort")
        );

        return memberQueryService.listMembers(query)
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .doOnError(error -> log.error("查询成员列表失败", error));
    }

    /**
     * 批量审核成员
     */
    private Mono<ServerResponse> batchApprove(ServerRequest request) {
        return request.bodyToMono(BatchApproveRequest.class)
            .flatMap(req -> batchService.batchApprove(req.memberNames(), req.approved()))
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .doOnError(error -> log.error("批量审核失败", error));
    }

    /**
     * 批量删除成员
     */
    private Mono<ServerResponse> batchDelete(ServerRequest request) {
        return request.bodyToMono(BatchRequest.class)
            .flatMap(req -> batchService.batchDelete(req.memberNames()))
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .doOnError(error -> log.error("批量删除失败", error));
    }

    /**
     * 批量修改分组
     */
    private Mono<ServerResponse> batchChangeGroup(ServerRequest request) {
        return request.bodyToMono(BatchChangeGroupRequest.class)
            .flatMap(req -> batchService.batchChangeGroup(req.memberNames(), req.groupName()))
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .doOnError(error -> log.error("批量修改分组失败", error));
    }

    /**
     * 批量修改优先级
     */
    private Mono<ServerResponse> batchChangePriority(ServerRequest request) {
        return request.bodyToMono(BatchChangePriorityRequest.class)
            .flatMap(req -> batchService.batchChangePriority(req.memberNames(), req.priority()))
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .doOnError(error -> log.error("批量修改优先级失败", error));
    }

    /**
     * 导出CSV
     */
    private Mono<ServerResponse> exportCSV(ServerRequest request) {
        return request.bodyToMono(ExportRequest.class)
            .defaultIfEmpty(new ExportRequest(List.of()))
            .flatMap(req -> batchService.exportToCSV(req.memberNames()))
            .flatMap(csv -> ServerResponse.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=members.csv")
                .bodyValue(csv))
            .doOnError(error -> log.error("导出CSV失败", error));
    }

    /**
     * 导出JSON
     */
    private Mono<ServerResponse> exportJSON(ServerRequest request) {
        return request.bodyToMono(ExportRequest.class)
            .defaultIfEmpty(new ExportRequest(List.of()))
            .flatMap(req -> batchService.exportToJSON(req.memberNames()))
            .flatMap(json -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=members.json")
                .bodyValue(json))
            .doOnError(error -> log.error("导出JSON失败", error));
    }

    // 请求对象
    public record BatchRequest(List<String> memberNames) {}
    public record BatchApproveRequest(List<String> memberNames, boolean approved) {}
    public record BatchChangeGroupRequest(List<String> memberNames, String groupName) {}
    public record BatchChangePriorityRequest(List<String> memberNames, Integer priority) {}
    public record ExportRequest(List<String> memberNames) {}

    private int queryInt(ServerRequest request, String name, int defaultValue) {
        return request.queryParam(name)
            .map(value -> {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            })
            .orElse(defaultValue);
    }

    private String queryString(ServerRequest request, String name) {
        return request.queryParam(name).orElse(null);
    }
}
