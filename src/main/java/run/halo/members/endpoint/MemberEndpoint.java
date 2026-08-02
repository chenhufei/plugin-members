package run.halo.members.endpoint;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.members.exception.RateLimitExceededException;
import run.halo.members.finders.MemberFinder;
import run.halo.members.security.RateLimitService;
import run.halo.members.security.SecurityService;
import run.halo.members.service.MemberService;
import run.halo.members.service.SettingConfigMember;
import run.halo.members.utils.RequestUtils;
import run.halo.members.validation.SafeEmail;
import run.halo.members.validation.ValidSchool;

/**
 * 成员管理匿名 API 端点（无需登录）
 * v2.0.0 - 集成安全检查和频率限制
 * 
 * @author Sky
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberEndpoint implements CustomEndpoint {

    private static final HttpClient QQ_HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static final ObjectMapper QQ_OBJECT_MAPPER = new ObjectMapper();

    private final MemberFinder memberFinder;
    private final MemberService memberService;
    private final SettingConfigMember settingConfigMember;
    private final RateLimitService rateLimitService;
    private final SecurityService securityService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.member.plugin.halo.run/v1alpha1/Member";
        return route()
            .GET("membergroups", this::listGroups,
                builder -> builder.operationId("ListMemberGroups")
                    .description("List all member groups")
                    .tag(tag))
            .GET("qq-info", this::fetchQqInfo,
                builder -> builder.operationId("FetchQqInfo")
                    .description("服务端代理获取 QQ 昵称信息，规避前端跨域与网络限制")
                    .tag(tag))
            .POST("membersubmits/-/submit", this::submitMember,
                builder -> builder.operationId("SubmitMember")
                    .description("Submit member application")
                    .tag(tag))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.member.plugin.halo.run/v1alpha1");
    }

    /**
     * 获取所有分组（过滤掉禁止申请的分组）
     * 注意：此接口为公开读取接口，不需要安全检查
     */
    private Mono<ServerResponse> listGroups(ServerRequest request) {
        return settingConfigMember.getBasicConfig()
            .flatMap(config -> {
                String[] forbiddenGroups = config.getForbidSelectedGroupName();
                List<String> forbiddenList = forbiddenGroups != null ? 
                    Arrays.asList(forbiddenGroups) : List.of();
                
                return memberFinder.listAllGroups()
                    .filter(group -> !forbiddenList.contains(group.getMetadata().getName()))
                    .collectList()
                    .flatMap(groups -> ServerResponse.ok().bodyValue(groups));
            })
            .doOnError(error -> log.error("获取分组列表失败", error));
    }

    /**
     * 服务端代理获取 QQ 昵称信息。
     * 前端浏览器直接请求第三方接口会被 CORS 拦截并可能返回 403，
     * 改由服务端请求 uapis.cn 接口后返回归一化结果，规避跨域与网络限制。
     */
    private Mono<ServerResponse> fetchQqInfo(ServerRequest request) {
        String qq = request.queryParam("qq").orElse("").trim();
        if (!qq.matches("^\\d{5,12}$")) {
            return ServerResponse.badRequest()
                .bodyValue(new ErrorResponse("QQ号格式不正确"));
        }
        final String finalQq = qq;
        return settingConfigMember.getBasicConfig()
            .flatMap(config -> Mono.fromCallable(() -> requestQqInfo(finalQq, config.getUapisToken()))
                .subscribeOn(Schedulers.boundedElastic()))
            .flatMap(info -> ServerResponse.ok().bodyValue(info))
            .onErrorResume(error -> {
                log.warn("获取 QQ 信息失败, qq={}: {}", finalQq, error.getMessage());
                return ServerResponse.ok()
                    .bodyValue(new QqInfoResponse(finalQq, "", tencentAvatar(finalQq),
                        finalQq + "@qq.com", ""));
            });
    }

    /**
     * 多源兜底获取 QQ 信息：
     * <ul>
     *   <li>头像：直接由腾讯官方地址构造，无需任何第三方接口，永远可用。</li>
     *   <li>昵称：优先 uapis.cn（返回干净 UTF-8），失败或为空时退回腾讯官方接口
     *       （GBK 编码的 JSONP），保证正常账号昵称一定能取到。</li>
     * </ul>
     */
    private QqInfoResponse requestQqInfo(String qq, String uapisToken) {
        String nickname = "";
        String region = "";
        try {
            JsonNode uapisData = uapisQqInfo(qq, uapisToken);
            if (uapisData != null) {
                if (uapisData.has("data") && uapisData.get("data").isObject()) {
                    nickname = firstText(uapisData.get("data"), "nickname", "nick", "name", "userName");
                    region = firstText(uapisData.get("data"), "location", "province", "city");
                }
                if (nickname.isEmpty()) {
                    nickname = firstText(uapisData, "nickname", "nick", "name", "userName");
                }
                if (region.isEmpty()) {
                    region = firstText(uapisData, "location", "province", "city");
                }
            }
        } catch (Exception e) {
            log.debug("uapis.cn 获取 QQ 信息失败, qq={}: {}", qq, e.getMessage());
        }

        if (nickname.isEmpty() || isGarbled(nickname)) {
            String tencentNickname = nicknameFromTencent(qq);
            if (!tencentNickname.isEmpty() && !isGarbled(tencentNickname)) {
                nickname = tencentNickname;
            }
        }
        return new QqInfoResponse(qq, nickname, tencentAvatar(qq), qq + "@qq.com", region);
    }

    private boolean isGarbled(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // 简单判断是否包含明显的乱码字符（如常见的 GBK 误解析生僻字）
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\ufffd' || c == '锟' || c == '斤' || c == '拷' || c == '烫' || c == '屯' || c == '閿' || c == '熸' || c == '垝' || c == '姹' || c == '夐' || c == '敓' || c == '缁' || c == '撳' || c == '伐' || c == '枻' || c == '鎷' || c == '峰' || c == '') {
                return true;
            }
        }
        return false;
    }

    /**
     * 腾讯官方头像地址，无需接口调用即可直接展示
     */
    private static String tencentAvatar(String qq) {
        return "https://q.qlogo.cn/g?b=qq&nk=" + qq + "&s=640";
    }

    /**
     * 通过 uapis.cn 获取 QQ 信息节点（干净 UTF-8）；任何异常都返回 null，交由上层继续兜底
     */
    private JsonNode uapisQqInfo(String qq, String token) throws Exception {
        String url = "https://uapis.cn/api/v1/social/qq/userinfo?qq="
            + URLEncoder.encode(qq, StandardCharsets.UTF_8);
        if (token != null && !token.isBlank()) {
            url += "&token=" + URLEncoder.encode(token.trim(), StandardCharsets.UTF_8);
        }
        URI uri = URI.create(url);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "*/*");

        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token.trim());
        }

        HttpRequest httpRequest = builder
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build();

        HttpResponse<byte[]> response = QQ_HTTP_CLIENT.send(httpRequest,
            HttpResponse.BodyHandlers.ofByteArray());
        byte[] bytes = response.body();
        if (bytes == null) {
            return null;
        }
        String body = new String(bytes, StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return null;
        }
        return QQ_OBJECT_MAPPER.readTree(body);
    }

    /**
     * 通过腾讯官方 cgi_get_portrait.fcg 获取昵称。
     * 返回形如 {@code portraitCallBack({"QQ":[avatar,...,"NICK",0]})} 的 GBK 编码 JSONP，
     * 昵称位于数组下标 6。
     */
    private String nicknameFromTencent(String qq) {
        try {
            URI uri = URI.create(
                "https://users.qzone.qq.com/fcg-bin/cgi_get_portrait.fcg?uins="
                    + URLEncoder.encode(qq, StandardCharsets.UTF_8));
            byte[] bytes = httpGetBytes(uri);
            if (bytes == null || bytes.length == 0) {
                return "";
            }
            // 腾讯接口固定返回 GBK
            String body = new String(bytes, Charset.forName("GBK"));
            int start = body.indexOf('(');
            int end = body.lastIndexOf(')');
            if (start < 0 || end <= start) {
                return "";
            }
            JsonNode root = QQ_OBJECT_MAPPER.readTree(body.substring(start + 1, end));
            JsonNode arr = root.get(qq);
            if (arr != null && arr.isArray() && arr.size() > 6 && arr.get(6).isTextual()) {
                return arr.get(6).asText().trim();
            }
            return "";
        } catch (Exception e) {
            log.debug("腾讯接口获取 QQ 昵称失败, qq={}: {}", qq, e.getMessage());
            return "";
        }
    }

    private static byte[] httpGetBytes(URI uri) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "*/*")
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build();
        HttpResponse<byte[]> response = QQ_HTTP_CLIENT.send(httpRequest,
            HttpResponse.BodyHandlers.ofByteArray());
        return response.body();
    }

    private static String httpGetString(URI uri, Charset charset) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "*/*")
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build();
        HttpResponse<byte[]> response = QQ_HTTP_CLIENT.send(httpRequest,
            HttpResponse.BodyHandlers.ofByteArray());
        byte[] bytes = response.body();
        if (bytes == null) {
            return "";
        }
        return new String(bytes, charset);
    }

    /**
     * 按优先级从 JSON 节点中取第一个非空的文本字段值
     */
    private static String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual()) {
                String text = value.asText().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    /**
     * 提交成员申请
     */
    private Mono<ServerResponse> submitMember(ServerRequest request) {
        return settingConfigMember.getBasicConfig()
            .flatMap(config -> performSecurityCheck(request, config)
                .then(performRateLimitCheck(request, config))
                .then(request.bodyToMono(MemberSubmitRequest.class)
                    .switchIfEmpty(Mono.error(new IllegalArgumentException("提交内容不能为空"))))
            )
            .flatMap(memberService::submitMember)
            .flatMap(member -> {
                log.info("成员申请提交成功: {}", member.getMetadata().getName());
                return ServerResponse.ok().bodyValue(member);
            })
            .doOnError(error -> log.error("成员申请提交失败", error));
    }
    
    /**
     * 执行安全检查
     */
    private Mono<Void> performSecurityCheck(ServerRequest request,
        SettingConfigMember.BasicConfig config) {
        return Mono.fromRunnable(() -> {
            String clientIP = RequestUtils.getClientIP(request);
            String userAgent = RequestUtils.getUserAgent(request);

            if (!securityService.isRequestAllowed(clientIP, userAgent, config)) {
                log.warn("安全检查失败 - IP: {}, User-Agent: {}", clientIP, userAgent);
                throw new SecurityException("请求被安全策略拒绝");
            }
        });
    }
    
    /**
     * 执行频率限制检查
     */
    private Mono<Void> performRateLimitCheck(ServerRequest request,
        SettingConfigMember.BasicConfig config) {
        return Mono.fromRunnable(() -> {
            if (!config.isEnableRateLimit()) {
                return;
            }

            String clientIP = RequestUtils.getClientIP(request);
            int maxRequests = config.normalizedMaxRequestsPerMinute();
            
            if (!rateLimitService.isRequestAllowed(clientIP, maxRequests, Duration.ofMinutes(1))) {
                int remaining = rateLimitService.getRemainingRequests(clientIP, maxRequests);
                log.warn("频率限制触发 - IP: {}, 剩余请求数: {}", clientIP, remaining);
                throw new RateLimitExceededException("请求过于频繁，请稍后再试");
            }
        });
    }
    
    /**
     * 成员提交请求 - 增强验证
     */
    public record MemberSubmitRequest(
        @NotBlank(message = "账号名称不能为空")
        @Size(min = 2, max = 50, message = "账号名称长度必须在2-50字符之间")
        @Pattern(regexp = "^[\\u4e00-\\u9fa5a-zA-Z0-9\\s\\-_]+$", 
                 message = "账号名称包含非法字符")
        String displayName,
        
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @SafeEmail(allowTemporary = false)
        String email,
        
        @NotBlank(message = "学校不能为空")
        @ValidSchool
        String school,
        
        @NotBlank(message = "QQ号不能为空")
        @Pattern(regexp = "^\\d{5,12}$", message = "QQ号格式不正确")
        String qq,
        
        String qqFriendLink,
        
        String groupName
    ) {}

    /**
     * 错误响应
     */
    public record ErrorResponse(String message) {}

    /**
     * QQ 信息代理响应
     */
    public record QqInfoResponse(String qq, String nickname, String avatar, String email, String region) {}
}
