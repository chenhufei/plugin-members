package run.halo.members.endpoint;

import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.plugin.ApiVersion;
import run.halo.members.utils.AvatarProxyUrl;

/**
 * 头像代理端点（匿名可读）。
 * <p>
 * 成员页「保存工作证」要把头像画进画布再导出 PNG，而 QQ 头像 CDN 不返回 CORS 头：
 * {@code <img>} 能正常显示，{@code fetch} 与画布绘制都会被浏览器拒绝，
 * 导出图上的头像位置就只剩一个占位字母。这个端点把头像以同源地址取回来，
 * 让前端拿到可以直接绘制、不会污染画布的图片字节。
 * <p>
 * 地址走 {@link AvatarProxyUrl} 白名单（仅 QQ 头像的固定主机与路径），
 * 不跟随跳转、限制体积与超时，避免匿名接口被当成 SSRF 跳板。
 */
@Slf4j
@Component
@ApiVersion("api.plugin.halo.run/v1alpha1")
public class AvatarProxyEndpoint implements CustomEndpoint {

    /** 单张头像的体积上限：QQ 头像 640px 版本通常 30–90KB。 */
    private static final int MAX_BYTES = 3 * 1024 * 1024;

    /** 上游连不上就尽快失败，别把「保存工作证」的按钮一直挂在生成中。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** 头像基本不变，缓存半小时；条数封顶避免被不同 QQ 号刷爆内存。 */
    private static final int MAX_CACHE_ENTRIES = 256;
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    private final Map<String, CachedAvatar> cache = new ConcurrentHashMap<>();

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.plugin.halo.run/v1alpha1/AvatarProxy";
        return route()
            .GET("/plugins/PluginMembers/avatar", this::avatar,
                builder -> builder.operationId("GetMemberAvatar")
                    .description("""
                        代理 QQ 头像，供前台画布导出工作证图片使用（匿名可读）。
                        只接受 QQ 头像 CDN 的固定地址。
                        """)
                    .tag(tag))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.plugin.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> avatar(ServerRequest request) {
        URI target = AvatarProxyUrl.resolve(request.queryParam("url").orElse("")).orElse(null);
        if (target == null) {
            return ServerResponse.badRequest()
                .bodyValue(new ErrorResponse("头像地址不在允许范围内"));
        }
        CachedAvatar cached = cache.get(target.toString());
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return respond(cached);
        }
        return fetch(target)
            .doOnNext(avatar -> remember(target.toString(), avatar))
            .flatMap(this::respond)
            .onErrorResume(error -> {
                log.warn("代理头像失败：{} - {}", target, error.getMessage());
                return ServerResponse.status(502)
                    .bodyValue(new ErrorResponse("头像获取失败，请稍后重试"));
            });
    }

    private void remember(String key, CachedAvatar avatar) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(key, avatar);
    }

    private Mono<ServerResponse> respond(CachedAvatar avatar) {
        return ServerResponse.ok()
            .contentType(MediaType.parseMediaType(avatar.contentType()))
            .header("Cache-Control", "public, max-age=86400")
            .body(BodyInserters.fromValue(avatar.body()));
    }

    private Mono<CachedAvatar> fetch(URI uri) {
        return Mono.fromCallable(() -> {
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "image/*")
                    .header("User-Agent", "Halo-Members-Avatar-Proxy")
                    .GET()
                    .build();
                HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    if (response.statusCode() != 200) {
                        throw new IOException("上游返回 " + response.statusCode());
                    }
                    String contentType = response.headers()
                        .firstValue("content-type")
                        .orElse("")
                        .toLowerCase(Locale.ROOT);
                    if (!contentType.startsWith("image/")) {
                        throw new IOException("上游返回的不是图片：" + contentType);
                    }
                    long declared = response.headers()
                        .firstValueAsLong("content-length")
                        .orElse(-1L);
                    if (declared > MAX_BYTES) {
                        throw new IOException("头像超出体积上限：" + declared + " 字节");
                    }
                    byte[] bytes = readLimited(body);
                    return new CachedAvatar(bytes, contentType.split(";")[0].trim(),
                        System.currentTimeMillis() + CACHE_TTL_MS);
                }
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    /** 边读边限长，避免上游不声明 Content-Length 时把内存读爆。 */
    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        byte[] chunk = new byte[16 * 1024];
        int read;
        int total = 0;
        while ((read = input.read(chunk)) != -1) {
            total += read;
            if (total > MAX_BYTES) {
                throw new IOException("头像超出体积上限");
            }
            buffer.write(chunk, 0, read);
        }
        if (total == 0) {
            throw new IOException("头像内容为空");
        }
        return buffer.toByteArray();
    }

    private record CachedAvatar(byte[] body, String contentType, long expiresAt) {
    }

    /** 与成员公开接口同一套错误体，方便前端统一处理。 */
    public record ErrorResponse(String message) {
    }
}
