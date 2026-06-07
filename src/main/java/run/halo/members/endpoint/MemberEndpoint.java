package run.halo.members.endpoint;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
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

    private final MemberFinder memberFinder;
    private final MemberService memberService;
    private final SettingConfigMember settingConfigMember;
    private final RateLimitService rateLimitService;
    private final SecurityService securityService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "anonymous.member.plugin.halo.run/v1alpha1/Member";
        return route()
            .GET("membergroups", this::listGroups,
                builder -> builder.operationId("ListMemberGroups")
                    .description("List all member groups")
                    .tag(tag))
            .POST("membersubmits/-/submit", this::submitMember,
                builder -> builder.operationId("SubmitMember")
                    .description("Submit member application")
                    .tag(tag))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("anonymous.member.plugin.halo.run/v1alpha1");
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
}
