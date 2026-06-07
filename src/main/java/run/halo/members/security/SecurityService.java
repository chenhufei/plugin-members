package run.halo.members.security;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;
import run.halo.members.service.SettingConfigMember;

/**
 * 安全验证服务
 * 提供IP检查、User-Agent验证、内容安全检查等功能
 * 
 * @author Sky
 * @since 1.1.0
 */
@Slf4j
@Service
public class SecurityService {

    // 内容安全检查模式
    private static final Pattern MALICIOUS_CONTENT_PATTERN = Pattern.compile(
        "(?i)(script|javascript|vbscript|onload|onerror|onclick|eval|alert|confirm|prompt|document\\.cookie)",
        Pattern.CASE_INSENSITIVE
    );
    
    // SQL注入检查模式
    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
        "(?i)(union|select|insert|update|delete|drop|create|alter|exec|execute|script|javascript)",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 检查请求是否被允许
     * 
     * @param clientIP 客户端IP
     * @param userAgent User-Agent字符串
     * @return true如果请求被允许，false如果被阻止
     */
    public boolean isRequestAllowed(String clientIP, String userAgent) {
        return isRequestAllowed(clientIP, userAgent, new SettingConfigMember.BasicConfig());
    }

    /**
     * 按插件配置检查请求是否被允许。
     */
    public boolean isRequestAllowed(String clientIP, String userAgent,
        SettingConfigMember.BasicConfig config) {
        if (config == null || !config.isEnableSecurityBlocklist()) {
            return true;
        }

        // 检查IP黑名单
        if (isIPBlocked(clientIP, config.getBlockedIpList())) {
            log.warn("阻止来自黑名单IP的请求: {}", clientIP);
            return false;
        }

        // 检查User-Agent
        if (isUserAgentSuspicious(userAgent, config.getBlockedUserAgentKeywords(),
            config.isRejectMissingUserAgent())) {
            log.warn("阻止可疑User-Agent的请求: {} from IP: {}", userAgent, clientIP);
            return false;
        }

        return true;
    }

    /**
     * 检查IP是否在黑名单中
     * 
     * @param ip IP地址
     * @return true如果IP被阻止
     */
    public boolean isIPBlocked(String ip) {
        return isIPBlocked(ip, null);
    }

    public boolean isIPBlocked(String ip, String blockedIpList) {
        if (!StringUtils.hasText(ip)) {
            return false;
        }

        return parseList(blockedIpList).contains(ip.toLowerCase());
    }

    /**
     * 检查User-Agent是否可疑
     * 
     * @param userAgent User-Agent字符串
     * @return true如果User-Agent可疑
     */
    public boolean isUserAgentSuspicious(String userAgent) {
        return isUserAgentSuspicious(userAgent, null, false);
    }

    public boolean isUserAgentSuspicious(String userAgent, String blockedKeywords,
        boolean rejectMissingUserAgent) {
        if (!StringUtils.hasText(userAgent)) {
            return rejectMissingUserAgent;
        }

        Set<String> keywords = parseList(blockedKeywords);
        if (keywords.isEmpty()) {
            return false;
        }

        String lowerUserAgent = userAgent.toLowerCase();
        return keywords.stream()
            .anyMatch(lowerUserAgent::contains);
    }

    /**
     * 检查内容是否安全
     * 
     * @param content 要检查的内容
     * @return true如果内容安全
     */
    public boolean isContentSafe(String content) {
        if (!StringUtils.hasText(content)) {
            return true; // 空内容是安全的
        }
        
        // 检查XSS攻击
        if (MALICIOUS_CONTENT_PATTERN.matcher(content).find()) {
            log.warn("检测到潜在的XSS攻击内容: {}", content);
            return false;
        }
        
        // 检查SQL注入
        if (SQL_INJECTION_PATTERN.matcher(content).find()) {
            log.warn("检测到潜在的SQL注入内容: {}", content);
            return false;
        }
        
        return true;
    }
    
    /**
     * 清理和转义用户输入
     * 
     * @param input 用户输入
     * @return 清理后的安全内容
     */
    public String sanitizeInput(String input) {
        if (input == null) {
            return null;
        }
        
        // 移除潜在的恶意字符（& 必须最先替换，避免双重编码）
        return input
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll("\"", "&quot;")
            .replaceAll("'", "&#x27;")
            .trim();
    }
    
    /**
     * 验证邮箱是否为临时邮箱
     * 
     * @param email 邮箱地址
     * @return true如果是临时邮箱
     */
    public boolean isTemporaryEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return false;
        }
        
        // 常见临时邮箱域名
        Set<String> tempEmailDomains = Set.of(
            "10minutemail.com", "guerrillamail.com", "mailinator.com",
            "tempmail.org", "yopmail.com", "throwaway.email"
        );
        
        String domain = email.substring(email.lastIndexOf("@") + 1).toLowerCase();
        return tempEmailDomains.contains(domain);
    }
    
    /**
     * 验证学校名称是否合法
     * 
     * @param schoolName 学校名称
     * @return true如果学校名称合法
     */
    public boolean isValidSchoolName(String schoolName) {
        if (!StringUtils.hasText(schoolName)) {
            return false;
        }
        
        // 检查长度
        if (schoolName.length() < 2 || schoolName.length() > 100) {
            return false;
        }
        
        // 检查是否包含合法字符（中文、英文、数字、常见符号）
        Pattern validSchoolPattern = Pattern.compile("^[\\u4e00-\\u9fa5a-zA-Z0-9\\s\\-_()（）]+$");
        return validSchoolPattern.matcher(schoolName).matches();
    }

    private Set<String> parseList(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        return Arrays.stream(value.split("[,\\r\\n]+"))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .map(String::toLowerCase)
            .collect(Collectors.toUnmodifiableSet());
    }
}
