package run.halo.members.endpoint;

import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import run.halo.members.service.SchoolAffinityService.AffinityOptions;
import run.halo.members.service.SettingConfigMember;

/**
 * 成员星球视图的数据接口：分析成员所属学校之间的关联，供前端连线与依据面板使用。
 *
 * <p>响应结构：</p>
 * <ul>
 *   <li>{@code enabled}：后台是否开启该能力（关闭时不产生任何大模型调用）</li>
 *   <li>{@code reason}：没有连线时的原因（disabled-by-admin / ai-unavailable /
 *       not-enough-schools）</li>
 *   <li>{@code links}：关联边，含 a、b、strength、category、reason</li>
 *   <li>{@code meta}：分析维度、学校数、连线上限等，便于前端如实展示「分析口径」</li>
 * </ul>
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
    private final SettingConfigMember settingConfigMember;

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
        return settingConfigMember.getBasicConfig()
            .flatMap(config -> {
                if (!config.isAffinityEnabled()) {
                    return Mono.just(body(false, "disabled-by-admin", List.of(), null));
                }
                var service = affinityService.getIfAvailable();
                if (service == null) {
                    return Mono.just(body(false, "ai-unavailable", List.of(), null));
                }
                var aspects = config.effectiveAffinityAspects();
                var maxLinks = config.effectiveAffinityMaxLinks();
                var signature = String.join(",", aspects) + "|" + maxLinks;

                var cached = cache;
                if (cached != null && signature.equals(cached.signature())
                    && Instant.now().isBefore(cached.expiresAt())) {
                    return Mono.just(cached.body());
                }

                var options = new AffinityOptions(Set.copyOf(aspects), maxLinks);
                return listSchools()
                    .flatMap(schools -> {
                        var meta = meta(schools, aspects, maxLinks);
                        if (schools.size() < 2) {
                            return Mono.just(
                                body(true, "not-enough-schools", List.of(), meta));
                        }
                        return service.analyze(schools, options)
                            .map(links -> body(true, null, links, meta));
                    })
                    .map(result -> {
                        cache = new CachedResult(result, signature,
                            Instant.now().plus(CACHE_TTL));
                        return result;
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

    private Map<String, Object> body(boolean enabled, String reason, List<?> links,
                                     Map<String, Object> meta) {
        var body = new LinkedHashMap<String, Object>();
        body.put("enabled", enabled);
        body.put("generatedAt", Instant.now().toString());
        if (reason != null) {
            body.put("reason", reason);
        }
        body.put("links", links == null ? List.of() : links);
        if (meta != null) {
            body.put("meta", meta);
        }
        return body;
    }

    private Map<String, Object> meta(List<String> schools, List<String> aspects, int maxLinks) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("schoolCount", schools.size());
        meta.put("aspects", aspects);
        meta.put("maxLinks", maxLinks);
        return meta;
    }

    private Mono<ServerResponse> json(Map<String, Object> body) {
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body);
    }

    private record CachedResult(Map<String, Object> body, String signature,
                                Instant expiresAt) {
    }
}
