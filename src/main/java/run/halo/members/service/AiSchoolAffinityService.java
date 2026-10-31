package run.halo.members.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * <p>分析维度由后台「学校关联分析 → 分析维度」决定：未选中的维度不写进提示词，
 * 模型便不会输出该类关系；每条结论都要求模型给出 {@code reason}，
 * 前端据此展示「凭什么连这条线」，而不是只有一条无法解释的线。</p>
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

    private final ExtensionGetter extensionGetter;

    @Override
    public Mono<List<AffinityLink>> analyze(List<String> schools, AffinityOptions options) {
        if (schools == null || schools.size() < 2) {
            return Mono.just(List.of());
        }
        var effective = options == null ? AffinityOptions.defaults() : options;
        var limited = schools.size() > MAX_SCHOOLS ? schools.subList(0, MAX_SCHOOLS) : schools;
        var request = GenerateTextRequest.builder()
            .system("你是数据分析助手，只输出 JSON，不要输出解释或 Markdown 代码块。")
            .prompt(buildPrompt(limited, effective))
            .temperature(0.2)
            .maxOutputTokens(1200)
            .build();
        return extensionGetter.getEnabledExtension(AiModelService.class)
            .switchIfEmpty(Mono.error(new IllegalStateException("AI Foundation 未启用")))
            .flatMap(AiModelService::languageModel)
            .flatMap(model -> model.generateText(request))
            .map(result -> parse(result.getText(), limited, effective))
            .onErrorResume(error -> {
                log.warn("学校关联分析失败：{}", error.getMessage());
                return Mono.just(List.of());
            });
    }

    private String buildPrompt(List<String> schools, AffinityOptions options) {
        var aspects = options.aspects();
        var builder = new StringBuilder();
        builder.append("以下是成员所属的学校列表：\n");
        for (int i = 0; i < schools.size(); i++) {
            builder.append(i + 1).append(". ").append(schools.get(i)).append('\n');
        }
        builder.append("\n请判断这些学校之间是否存在以下关系：\n");
        if (aspects.contains("cooperation")) {
            builder.append("- 合作关系（category=cooperation）：联合办学、共建项目、学术或赛事合作\n");
        }
        if (aspects.contains("region")) {
            builder.append("- 地域/机构关联（category=region）：同城、同一教育集团、同一主管部门\n");
        }
        if (aspects.contains("organization")) {
            builder.append("- 共同的小组织（category=organization）：联盟、协会、社团、校友会等\n");
        }
        builder.append("""

            只输出 JSON 数组，不要任何解释或代码块标记。数组元素格式：
            {"a":"学校名称","b":"学校名称","strength":0.8,"category":"cooperation","reason":"简短理由"}

            要求：
            - a、b 必须来自上面的列表，且不能相同
            - strength 为 0 到 1 之间的小数，关联越强取值越大
            - category 必须是上面列出的类别之一
            - reason 用一句不超过 30 字的中文说明判断依据，不要只写「有关联」
            - 无法判断存在关联的学校对不要输出
            """);
        builder.append("- 最多输出 ").append(options.maxLinks()).append(" 条\n");
        return builder.toString();
    }

    private List<AffinityLink> parse(String text, List<String> allowed, AffinityOptions options) {
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
            var aspects = options.aspects();
            var fallbackCategory = fallbackCategory(aspects);
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
                var category = item.path("category").asText("").trim();
                if (!aspects.contains(category)) {
                    category = fallbackCategory;
                }
                var reason = item.path("reason").asText("").trim();
                links.add(new AffinityLink(a, b, strength, category, reason));
                if (links.size() >= options.maxLinks()) {
                    break;
                }
            }
            return links;
        } catch (Exception error) {
            log.warn("解析学校关联结果失败：{}", error.getMessage());
            return List.of();
        }
    }

    /** 模型未给出合法类别时的兜底类别，按固定顺序取第一个已启用维度。 */
    private static String fallbackCategory(Set<String> aspects) {
        if (aspects.contains("cooperation")) {
            return "cooperation";
        }
        if (aspects.contains("region")) {
            return "region";
        }
        if (aspects.contains("organization")) {
            return "organization";
        }
        return "cooperation";
    }
}
