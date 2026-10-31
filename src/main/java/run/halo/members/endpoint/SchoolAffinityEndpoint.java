package run.halo.members.endpoint;

import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.members.Member;
import run.halo.members.service.SchoolAffinityService;

/**
 * 成员星球视图的数据接口：分析成员所属学校之间的关联，供前端连线使用。
 *
 * <p>AI Foundation 未安装时返回空连线，前端静默降级为不画线。</p>
 *
 * @since 1.15.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchoolAffinityEndpoint implements CustomEndpoint {

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final int MAX_SCHOOLS = 40;

    private final ReactiveExtensionClient client;
    private final ObjectProvider<SchoolAffinityService> affinityService;

    private volatile CachedResult cache;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.plugin.halo.run/v1alpha1/MemberSchoolAffinity";
        return route()
            .GET("/plugins/PluginMembers/school-affinity", this::getAffinity,
                builder -> builder.operationId("GetSchoolAffinity")
                    .description("分析成员所属学校之间的关联，供成员星球视图连线使用")
                    .tag(tag))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.plugin.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> getAffinity(ServerRequest request) {
        var service = affinityService.getIfAvailable();
        if (service == null) {
            return json(emptyBody("ai-unavailable"));
        }
        var cached = cache;
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return json(cached.body());
        }
        return listSchools()
            .flatMap(schools -> {
                if (schools.size() < 2) {
                    return Mono.just(emptyBody("not-enough-schools"));
                }
                return service.analyze(schools)
                    .map(links -> {
                        var body = new LinkedHashMap<String, Object>();
                        body.put("links", links);
                        body.put("schools", schools);
                        cache = new CachedResult(body, Instant.now().plus(CACHE_TTL));
                        return body;
                    });
            })
            .flatMap(this::json);
    }

    private Mono<List<String>> listSchools() {
        return client.list(Member.class, member -> true, null)
            .map(member -> {
                var spec = member.getSpec();
                String school = spec == null ? null : spec.getSchool();
                return school == null ? "" : school.trim();
            })
            .filter(school -> !school.isEmpty())
            .distinct()
            .collectList()
            .map(list -> list.size() > MAX_SCHOOLS ? list.subList(0, MAX_SCHOOLS) : list);
    }

    private Map<String, Object> emptyBody(String reason) {
        var body = new LinkedHashMap<String, Object>();
        body.put("links", List.of());
        body.put("reason", reason);
        return body;
    }

    private Mono<ServerResponse> json(Map<String, Object> body) {
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body);
    }

    private record CachedResult(Map<String, Object> body, Instant expiresAt) {
    }
}
