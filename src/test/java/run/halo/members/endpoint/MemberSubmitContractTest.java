package run.halo.members.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.members.Member;
import run.halo.members.exception.DuplicateMemberException;
import run.halo.members.exception.PendingCapacityExceededException;
import run.halo.members.finders.MemberFinder;
import run.halo.members.security.RateLimitService;
import run.halo.members.security.SecurityService;
import run.halo.members.security.captcha.MemberCaptchaService;
import run.halo.members.service.MemberService;
import run.halo.members.service.SettingConfigMember;

/**
 * 匿名提交接口的状态码契约测试。
 * 请求体非法与验证码失败使用 400，业务冲突（重复、待审核已满）使用 409。
 */
class MemberSubmitContractTest {

    private static final String BASE = "/apis/api.member.plugin.halo.run/v1alpha1";
    private static final String SUBMIT = BASE + "/membersubmits/-/submit";
    private static final String CAPTCHA = BASE + "/membersubmits/-/captcha";

    private final MemberFinder memberFinder = mock(MemberFinder.class);
    private final MemberService memberService = mock(MemberService.class);
    private final SettingConfigMember settingConfigMember = mock(SettingConfigMember.class);
    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final SecurityService securityService = mock(SecurityService.class);
    private final MemberCaptchaService captchaService = mock(MemberCaptchaService.class);

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        MemberEndpoint endpoint = new MemberEndpoint(memberFinder, memberService,
            settingConfigMember, rateLimitService, securityService, captchaService);
        // Halo 核心用 path 前缀组合 CustomEndpoint 的相对路由，这里复用同样的组合方式。
        RouterFunction<ServerResponse> router = RouterFunctions.route()
            .path(BASE, () -> endpoint.endpoint())
            .build();
        client = WebTestClient.bindToRouterFunction(router).build();

        when(settingConfigMember.getBasicConfig())
            .thenReturn(Mono.just(new SettingConfigMember.BasicConfig()));
        when(securityService.isRequestAllowed(any(), any(), any())).thenReturn(true);
        when(rateLimitService.isRequestAllowed(any(), anyInt(), any(Duration.class)))
            .thenReturn(true);
        when(captchaService.verifyChallenge(any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("验证码无效返回 400 problem+json")
    void invalidCaptchaIsBadRequest() {
        when(captchaService.verifyChallenge(any(), any())).thenReturn(false);

        submit()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.type").isEqualTo(
                "https://halo.run/probs/invalid-member-application-captcha");
    }

    @Test
    @DisplayName("重复申请返回 409")
    void duplicateMemberIsConflict() {
        when(memberService.submitMember(any())).thenReturn(
            Mono.error(new DuplicateMemberException("该 QQ 号已提交申请")));

        submit()
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.type").isEqualTo(
                "https://halo.run/probs/duplicate-member-application");
    }

    @Test
    @DisplayName("待审核申请已满返回 409，不是 400")
    void pendingCapacityReachedIsConflict() {
        when(memberService.submitMember(any())).thenReturn(
            Mono.error(new PendingCapacityExceededException("待审核申请已达上限")));

        submit()
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.type").isEqualTo(
                "https://halo.run/probs/member-application-capacity-reached");
    }

    @Test
    @DisplayName("安全策略拒绝返回 403")
    void securityRejectionIsForbidden() {
        when(securityService.isRequestAllowed(any(), any(), any())).thenReturn(false);

        submit()
            .expectStatus().isForbidden()
            .expectBody().jsonPath("$.type").isEqualTo(
                "https://halo.run/probs/member-application-forbidden");
    }

    @Test
    @DisplayName("频率超限返回 429")
    void rateLimitedIsTooManyRequests() {
        when(rateLimitService.isRequestAllowed(any(), anyInt(), any(Duration.class)))
            .thenReturn(false);

        submit()
            .expectStatus().isEqualTo(429)
            .expectBody().jsonPath("$.type").isEqualTo(
                "https://halo.run/probs/request-not-permitted");
    }

    @Test
    @DisplayName("提交成功返回 200 与成员资源")
    void submitSucceeds() {
        Member member = new Member();
        Metadata metadata = new Metadata();
        metadata.setName("member-1");
        member.setMetadata(metadata);
        Member.MemberSpec spec = new Member.MemberSpec();
        spec.setDisplayName("测试成员");
        member.setSpec(spec);
        when(memberService.submitMember(any())).thenReturn(Mono.just(member));

        client.post().uri(SUBMIT)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(validBody())
            .exchange()
            .expectStatus().isOk()
            .expectBody().jsonPath("$.metadata.name").isEqualTo("member-1");
    }

    @Test
    @DisplayName("空请求体返回 400 而不是 500")
    void emptyBodyIsBadRequest() {
        client.post().uri(SUBMIT)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("")
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("错误响应使用 application/problem+json")
    void errorUsesProblemJsonContentType() {
        when(captchaService.verifyChallenge(any(), any())).thenReturn(false);

        submit().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("验证码被限流返回 429、验证码服务不可用返回 503")
    void captchaIssueStatusCodes() {
        when(captchaService.issue(any(), any())).thenReturn(
            Mono.just(captchaIssue(MemberCaptchaService.IssueStatus.RATE_LIMITED)));

        client.post().uri(CAPTCHA).exchange()
            .expectStatus().isEqualTo(429)
            .expectBody().jsonPath("$.type")
            .isEqualTo("https://halo.run/probs/request-not-permitted");

        when(captchaService.issue(any(), any())).thenReturn(
            Mono.just(captchaIssue(MemberCaptchaService.IssueStatus.UNAVAILABLE)));

        client.post().uri(CAPTCHA).exchange()
            .expectStatus().isEqualTo(503)
            .expectBody().jsonPath("$.type")
            .isEqualTo("https://halo.run/probs/member-application-unavailable");
    }

    @Test
    @DisplayName("验证码下发成功返回 200 与 base64 图片")
    void captchaIssueSucceeds() {
        when(captchaService.issue(any(), any())).thenReturn(
            Mono.just(new MemberCaptchaService.IssueResult(
                MemberCaptchaService.IssueStatus.ISSUED, "challenge-1",
                new byte[] {1, 2, 3}, 300, 0)));

        client.post().uri(CAPTCHA).exchange()
            .expectStatus().isOk()
            .expectHeader().cacheControl(CacheControl.noStore())
            .expectBody().jsonPath("$.challengeId").isEqualTo("challenge-1");
    }

    private WebTestClient.ResponseSpec submit() {
        return client.post().uri(SUBMIT)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(validBody())
            .exchange();
    }

    private static MemberCaptchaService.IssueResult captchaIssue(
        MemberCaptchaService.IssueStatus status) {
        return new MemberCaptchaService.IssueResult(status, null, null, 0,
            status == MemberCaptchaService.IssueStatus.RATE_LIMITED ? 60 : 0);
    }

    private static String validBody() {
        return """
            {
              "displayName": "测试成员",
              "email": "member@example.com",
              "school": "清华大学",
              "qq": "12345678",
              "groupName": "default-group",
              "challengeId": "challenge-1",
              "captchaCode": "AB234"
            }
            """;
    }
}
