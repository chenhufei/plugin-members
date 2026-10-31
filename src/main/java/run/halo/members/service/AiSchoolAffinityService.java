package run.halo.members.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.AiModelService;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

/**
 * 基于 AI Foundation 默认语言模型的学校关联分析实现。
 *
 * <p>仅在类路径存在 AI Foundation SDK 时注册，避免站点未安装该插件时出现类加载失败。</p>
 *
 * @since 1.15.0
 */
@Component
@ConditionalOnClass(name = "run.halo.aifoundation.AiModelService")
@RequiredArgsConstructor
@Slf4j
public class AiSchoolAffinityService implements SchoolAffinityService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SCHOOLS = 40;
    private static final int MAX_LINKS = 20;

    private final ExtensionGetter extensionGetter;

    @Override
    public Mono<List<AffinityLink>> analyze(List<String> schools) {
        if (schools == null || schools.size() < 2) {
            return Mono.just(List.of());
        }
        var limited = schools.size() > MAX_SCHOOLS ? schools.subList(0, MAX_SCHOOLS) : schools;
        var request = GenerateTextRequest.builder()
            .system("你是数据分析助手，只输出 JSON，不要输出解释或 Markdown 代码块。")
            .prompt(buildPrompt(limited))
            .temperature(0.2)
            .maxOutputTokens(1200)
            .build();
        return extensionGetter.getEnabledExtension(AiModelService.class)
            .switchIfEmpty(Mono.error(new IllegalStateException("AI Foundation 未启用")))
            .flatMap(AiModelService::languageModel)
            .flatMap(model -> model.generateText(request))
            .map(result -> parse(result.getText(), limited))
            .onErrorResume(error -> {
                log.warn("学校关联分析失败：{}", error.getMessage());
                return Mono.just(List.of());
            });
    }

    private String buildPrompt(List<String> schools) {
        var builder = new StringBuilder();
        builder.append("以下是成员所属的学校列表：\n");
        for (int i = 0; i < schools.size(); i++) {
            builder.append(i + 1).append(". ").append(schools.get(i)).append('\n');
        }
        builder.append("""

            请判断这些学校之间是否存在以下关系：
            - 合作关系：联合办学、共建项目、学术或赛事合作
            - 地域/机构关联：同城、同一教育集团、同一主管部门
            - 共同的小组织：联盟、协会、社团、校友会等

            只输出 JSON 数组，不要任何解释或代码块标记。数组元素格式：
            {"a":"学校名称","b":"学校名称","strength":0.8,"reason":"简短理由"}

            要求：
            - a、b 必须来自上面的列表，且不能相同
            - strength 为 0 到 1 之间的小数，关联越强取值越大
            - 无法判断存在关联的学校对不要输出
            - 最多输出 20 条
            """);
        return builder.toString();
    }

    private List<AffinityLink> parse(String text, List<String> allowed) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var start = text.indexOf('[');
        var end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(text.substring(start, end + 1));
            if (!root.isArray()) {
                return List.of();
            }
            var allowedSet = new HashSet<>(allowed);
            var links = new ArrayList<AffinityLink>();
            for (JsonNode item : root) {
                var a = item.path("a").asText("").trim();
                var b = item.path("b").asText("").trim();
                if (a.isEmpty() || b.isEmpty() || a.equals(b)) {
                    continue;
                }
                if (!allowedSet.contains(a) || !allowedSet.contains(b)) {
                    continue;
                }
                double strength = item.path("strength").asDouble(0.3);
                strength = Math.max(0.05, Math.min(1.0, strength));
                links.add(new AffinityLink(a, b, strength, item.path("reason").asText("")));
                if (links.size() >= MAX_LINKS) {
                    break;
                }
            }
            return links;
        } catch (Exception error) {
            log.warn("解析学校关联结果失败：{}", error.getMessage());
            return List.of();
        }
    }
}
