package run.halo.members.validation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import run.halo.members.endpoint.MemberEndpoint;
import run.halo.members.security.SecurityService;

/**
 * Centralized validation and normalization for anonymous member submissions.
 */
@Component
@RequiredArgsConstructor
public class MemberSubmitRequestValidator {

    private static final Pattern DISPLAY_NAME_PATTERN =
        Pattern.compile("^[\\u4e00-\\u9fa5a-zA-Z0-9\\s\\-_]+$");
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern QQ_PATTERN = Pattern.compile("^\\d{5,12}$");

    private final SecurityService securityService;

    public MemberEndpoint.MemberSubmitRequest validateAndSanitize(
        MemberEndpoint.MemberSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("提交内容不能为空");
        }

        String displayName = StringUtils.trimToEmpty(request.displayName());
        String email = StringUtils.trimToEmpty(request.email());
        String school = StringUtils.trimToEmpty(request.school());
        String qq = StringUtils.trimToEmpty(request.qq());
        String qqFriendLink = StringUtils.trimToNull(request.qqFriendLink());
        String groupName = StringUtils.trimToNull(request.groupName());

        validateDisplayName(displayName);
        validateEmail(email);
        validateSchool(school);
        validateQq(qq);
        validateQqFriendLink(qqFriendLink);

        return new MemberEndpoint.MemberSubmitRequest(
            securityService.sanitizeInput(displayName),
            email,
            securityService.sanitizeInput(school),
            qq,
            qqFriendLink,
            groupName
        );
    }

    private void validateDisplayName(String displayName) {
        if (StringUtils.isBlank(displayName)) {
            throw new IllegalArgumentException("账号名称不能为空");
        }
        if (displayName.length() < 2 || displayName.length() > 50) {
            throw new IllegalArgumentException("账号名称长度必须在2-50字符之间");
        }
        if (!DISPLAY_NAME_PATTERN.matcher(displayName).matches()) {
            throw new IllegalArgumentException("账号名称包含非法字符");
        }
    }

    private void validateEmail(String email) {
        if (StringUtils.isBlank(email)) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        if (securityService.isTemporaryEmail(email)) {
            throw new IllegalArgumentException("不允许使用临时邮箱");
        }
    }

    private void validateSchool(String school) {
        if (StringUtils.isBlank(school)) {
            throw new IllegalArgumentException("账号所属学校不能为空");
        }
        if (!securityService.isValidSchoolName(school)) {
            throw new IllegalArgumentException("学校名称不合法");
        }
    }

    private void validateQq(String qq) {
        if (StringUtils.isBlank(qq)) {
            throw new IllegalArgumentException("QQ号不能为空");
        }
        if (!QQ_PATTERN.matcher(qq).matches()) {
            throw new IllegalArgumentException("QQ号格式不正确");
        }
    }

    private void validateQqFriendLink(String qqFriendLink) {
        if (StringUtils.isBlank(qqFriendLink)) {
            return;
        }
        if (qqFriendLink.length() > 500) {
            throw new IllegalArgumentException("QQ好友链接不能超过500个字符");
        }
        try {
            URI uri = new URI(qqFriendLink);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("QQ好友链接必须是 http 或 https 地址");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("QQ好友链接格式不正确", e);
        }
    }
}
