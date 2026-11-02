package run.halo.members.service.impl;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.members.Member;
import run.halo.members.finders.impl.MemberFinderImpl;
import run.halo.members.service.MemberBatchService;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION_PENDING;

/**
 * 成员批量操作服务实现
 *
 * <p>批量审核 / 删除 / 改分组以「资源名」为单位，结果可以直接按名字求差集；
 * 批量导入以「一行数据」为单位，成功与否只能按行判定。两者的失败清单不在同一个
 * 名字域，因此分别用 {@link #toBatchResult} 与 {@link #toImportResult} 汇总。
 *
 * @author Sky
 * @since 2.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberBatchServiceImpl implements MemberBatchService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BATCH_CONCURRENCY = 8;

    /** 资源名白名单：{@code metadata.name} 同时是 URL 路径片段，只能是这个字符集。 */
    private static final String NAME_ILLEGAL_CHARS = "[^a-z0-9-]";
    private static final int NAME_MAX_LENGTH = 63;

    private final ReactiveExtensionClient client;
    private final MemberFinderImpl memberFinder;

    @Override
    public Mono<BatchOperationResult> batchApprove(List<String> memberNames, boolean approved) {
        log.info("批量审核成员: count={}, approved={}", sizeOf(memberNames), approved);

        return executeBatch(memberNames, "审核成员失败", member -> {
            member.getSpec().setStatus(approved ? "APPROVED" : "REJECTED");
            MetadataUtil.nullSafeAnnotations(member)
                .put(REVIEW_NOTIFICATION, REVIEW_NOTIFICATION_PENDING);
            return client.update(member);
        });
    }

    @Override
    public Mono<BatchOperationResult> batchDelete(List<String> memberNames) {
        log.info("批量删除成员: count={}", sizeOf(memberNames));

        return executeBatch(memberNames, "删除成员失败", client::delete);
    }

    @Override
    public Mono<BatchOperationResult> batchChangeGroup(List<String> memberNames, String groupName) {
        if (groupName == null || groupName.isEmpty()) {
            return Mono.just(BatchOperationResult.failed(sizeOf(memberNames), "分组名称不能为空"));
        }

        log.info("批量修改分组: count={}, groupName={}", sizeOf(memberNames), groupName);

        return executeBatch(memberNames, "修改成员分组失败", member -> {
            member.getSpec().setGroupName(groupName);
            return client.update(member);
        });
    }

    private Mono<BatchOperationResult> executeBatch(List<String> memberNames, String errorMessage,
        Function<Member, Mono<?>> operation) {
        List<String> normalizedNames = normalizeMemberNames(memberNames);
        if (normalizedNames.isEmpty()) {
            return Mono.just(BatchOperationResult.failed(0, "成员列表为空"));
        }

        return Flux.fromIterable(normalizedNames)
            .flatMap(name -> client.fetch(Member.class, name)
                .flatMap(member -> operation.apply(member).thenReturn(name))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("成员不存在，跳过: {}", name);
                    return Mono.<String>empty();
                }))
                .onErrorResume(e -> {
                    log.error("{}: name={}", errorMessage, name, e);
                    return Mono.empty();
                }),
                BATCH_CONCURRENCY
            )
            .collectList()
            .map(successList -> toBatchResult(normalizedNames, successList));
    }

    /**
     * 按资源名汇总批量操作结果，入参与出参在同一个名字域（{@code metadata.name}）。
     */
    private BatchOperationResult toBatchResult(List<String> memberNames, List<String> successList) {
        int success = successList.size();
        int total = memberNames.size();

        memberFinder.evictCache();

        if (success == total) {
            return BatchOperationResult.success(total);
        }

        Set<String> successfulMembers = Set.copyOf(successList);
        List<String> failed = memberNames.stream()
            .filter(name -> !successfulMembers.contains(name))
            .toList();
        return BatchOperationResult.partial(total, success, failed);
    }

    private List<String> normalizeMemberNames(List<String> memberNames) {
        if (memberNames == null || memberNames.isEmpty()) {
            return List.of();
        }
        return memberNames.stream()
            .filter(name -> name != null && !name.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new))
            .stream()
            .toList();
    }

    private int sizeOf(List<String> memberNames) {
        return memberNames == null ? 0 : memberNames.size();
    }

    @Override
    public Mono<String> exportToCSV(List<String> memberNames) {
        log.info("导出成员数据为CSV: count={}", memberNames != null ? memberNames.size() : "全部");

        return membersFor(memberNames)
            .collectList()
            .map(members -> {
                StringBuilder csv = new StringBuilder();
                // UTF-8 BOM：缺了它，Windows 下的 Excel 会按本地代码页解码，中文整列乱码。
                // 导入端 sanitizeCsvText 会把 BOM 再剥掉，往返一次不会累积。
                csv.append('\ufeff');
                // CSV 头部
                csv.append("账号名称,邮箱,所属学校,QQ号,分组,状态,QQ加好友链接,创建时间\n");

                // CSV 数据行
                DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
                for (Member member : members) {
                    Member.MemberSpec spec = member.getSpec();
                    csv.append(escapeCsv(spec.getDisplayName())).append(",");
                    csv.append(escapeCsv(spec.getEmail())).append(",");
                    csv.append(escapeCsv(spec.getSchool())).append(",");
                    csv.append(escapeCsv(spec.getQq())).append(",");
                    csv.append(escapeCsv(spec.getGroupName())).append(",");
                    csv.append(escapeCsv(spec.getStatus())).append(",");
                    csv.append(escapeCsv(spec.getQqFriendLink())).append(",");

                    // 修复 Instant 格式化
                    Instant creationTime = member.getMetadata().getCreationTimestamp();
                    if (creationTime != null) {
                        csv.append(creationTime.atZone(java.time.ZoneId.systemDefault())
                            .toLocalDateTime().format(formatter));
                    }
                    csv.append("\n");
                }

                return csv.toString();
            });
    }

    @Override
    public Mono<String> exportToJSON(List<String> memberNames) {
        log.info("导出成员数据为JSON: count={}", memberNames != null ? memberNames.size() : "全部");

        return membersFor(memberNames)
            .collectList()
            .map(members -> {
                try {
                    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(members);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    log.error("导出JSON失败", e);
                    throw new RuntimeException("导出JSON失败: " + e.getMessage(), e);
                }
            });
    }

    private Flux<Member> membersFor(List<String> memberNames) {
        return memberNames != null && !memberNames.isEmpty()
            ? Flux.fromIterable(memberNames).flatMap(name -> client.fetch(Member.class, name))
            : client.listAll(Member.class, new ListOptions(), ExtensionUtil.defaultSort());
    }

    @Override
    public Mono<BatchOperationResult> batchImportFromJSON(String jsonData, String defaultGroup) {
        log.info("从JSON批量导入成员: defaultGroup={}", defaultGroup);

        return Mono.fromCallable(() -> {
            var rootNode = MAPPER.readTree(jsonData);
            if (!rootNode.isArray()) {
                throw new IllegalArgumentException("JSON数据必须是数组格式");
            }

            List<ImportRow> rows = new ArrayList<>();
            int index = 0;
            for (var node : rootNode) {
                index++;
                Member.MemberSpec spec = new Member.MemberSpec();
                if (node.has("displayName")) spec.setDisplayName(node.get("displayName").asText());
                if (node.has("email")) spec.setEmail(node.get("email").asText());
                if (node.has("school")) spec.setSchool(node.get("school").asText());
                if (node.has("qq")) spec.setQq(node.get("qq").asText());
                if (node.has("groupName")) {
                    spec.setGroupName(node.get("groupName").asText());
                } else if (defaultGroup != null) {
                    spec.setGroupName(defaultGroup);
                }
                if (node.has("qqFriendLink")) spec.setQqFriendLink(node.get("qqFriendLink").asText());
                if (node.has("avatar")) spec.setAvatar(node.get("avatar").asText());
                if (node.has("background")) spec.setBackground(node.get("background").asText());
                spec.setStatus("PENDING");
                rows.add(new ImportRow(rowLabel(spec, index), spec));
            }
            return rows;
        })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(rows -> importRows(rows, "JSON数据为空"));
    }

    @Override
    public Mono<BatchOperationResult> batchImportFromCSV(String csvData, String defaultGroup) {
        log.info("从CSV批量导入成员: defaultGroup={}", defaultGroup);

        return Mono.fromCallable(() -> {
            String[] lines = sanitizeCsvText(csvData).split("\n");
            if (lines.length < 2) {
                throw new IllegalArgumentException("CSV数据必须包含头部和至少一行数据");
            }

            // 表头必须和数据行用同一套解析。数据行支持引号包裹，表头若直接 split(",")，
            // 带引号的表头（Excel 另存 CSV 的常见形态）会解析成 "\"账号名称" 这种名字，
            // 于是每一列都匹配不上，整批成员都是空 spec。
            String[] rawHeaders = parseCsvLine(lines[0]);
            List<String> headers = new ArrayList<>(rawHeaders.length);
            for (String rawHeader : rawHeaders) {
                headers.add(stripBom(rawHeader).trim());
            }

            List<ImportRow> rows = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parsed = parseCsvLine(line);
                Member.MemberSpec spec = new Member.MemberSpec();
                for (int j = 0; j < headers.size(); j++) {
                    // 列数不足按空值补齐。旧实现直接 continue 丢掉整行：
                    // 导 100 行只入库 90 行却报"全部成功"，用户无从发现。
                    String value = j < parsed.length ? stripBom(parsed[j]).trim() : "";
                    switch (headers.get(j)) {
                        case "displayName", "账号名称" -> spec.setDisplayName(value);
                        case "email", "邮箱" -> spec.setEmail(value);
                        case "school", "所属学校" -> spec.setSchool(value);
                        case "qq", "QQ号" -> spec.setQq(value);
                        case "groupName", "分组" -> spec.setGroupName(value);
                        case "qqFriendLink", "QQ加好友链接" -> spec.setQqFriendLink(value);
                        default -> {
                            // 未知列忽略：导出文件多了新列不该让整批失败
                        }
                    }
                }

                if (spec.getGroupName() == null && defaultGroup != null) {
                    spec.setGroupName(defaultGroup);
                }
                spec.setStatus("PENDING");
                rows.add(new ImportRow(rowLabel(spec, i), spec));
            }
            return rows;
        })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(rows -> importRows(rows, "CSV数据为空"));
    }

    /**
     * 导入主流程：行级预校验 -> 判重 -> 并发创建 -> 按行汇总。
     *
     * <p>失败粒度是「行」而不是「批」。旧实现里，单行数据有问题（字段全空触发 NPE）
     * 会在 {@code flatMap} 的 mapper 中同步抛出，异常越过了内层的 {@code onErrorResume}
     * 直接冒到最外层，于是整批归零，只回一句「解析CSV失败: null」。
     */
    private Mono<BatchOperationResult> importRows(List<ImportRow> rows, String emptyMessage) {
        if (rows.isEmpty()) {
            return Mono.just(BatchOperationResult.failed(0, emptyMessage));
        }

        List<ImportOutcome> rejected = new ArrayList<>();
        List<ImportRow> candidates = new ArrayList<>();
        for (ImportRow row : rows) {
            String problem = validateRow(row.spec());
            if (problem != null) {
                rejected.add(ImportOutcome.failed(row.label(), problem));
            } else {
                candidates.add(row);
            }
        }

        return existingMemberIndex()
            .flatMap(index -> {
                List<ImportRow> toCreate = new ArrayList<>();
                int skipped = 0;
                for (ImportRow row : candidates) {
                    String key = dedupeKey(row.spec());
                    if (key == null) {
                        // 既无 QQ 也无邮箱，没有可靠的判重依据，只能照常入库
                        toCreate.add(row);
                        continue;
                    }
                    if (index.containsKey(key)) {
                        log.info("导入跳过重复成员: {} -> 已存在 {}", row.label(), index.get(key));
                        skipped++;
                        continue;
                    }
                    // 同一份文件里的重复行同样要挡掉，否则一次导入就能造出两条一样的记录
                    index.put(key, row.label());
                    toCreate.add(row);
                }
                int skippedRows = skipped;

                return Flux.fromIterable(toCreate)
                    .flatMap(this::createMember, BATCH_CONCURRENCY)
                    .collectList()
                    .map(outcomes -> {
                        List<ImportOutcome> all = new ArrayList<>(rejected);
                        all.addAll(outcomes);
                        return toImportResult(rows.size(), all, skippedRows);
                    });
            });
    }

    private Mono<ImportOutcome> createMember(ImportRow row) {
        return Mono.fromCallable(() -> {
                Member member = new Member();
                member.setSpec(row.spec());
                Metadata metadata = new Metadata();
                metadata.setName(generateMemberName(row.spec()));
                member.setMetadata(metadata);
                return member;
            })
            .flatMap(client::create)
            .thenReturn(ImportOutcome.ok(row.label()))
            .onErrorResume(e -> {
                // 只把「能给人看的原因」回传前端，异常类名与堆栈留在日志里
                log.warn("导入成员失败: label={}, displayName={}", row.label(),
                    row.spec().getDisplayName(), e);
                return Mono.just(ImportOutcome.failed(row.label(), friendlyError(e)));
            });
    }

    /**
     * 按行汇总导入结果。总数 = 提交行数 = 成功 + 失败 + 跳过。
     */
    private BatchOperationResult toImportResult(int total, List<ImportOutcome> outcomes, int skipped) {
        memberFinder.evictCache();

        List<ImportOutcome> failedRows = outcomes.stream()
            .filter(outcome -> outcome.error() != null)
            .toList();
        int failed = failedRows.size();
        int success = outcomes.size() - failed;

        if (failed == 0 && skipped == 0) {
            return BatchOperationResult.success(total);
        }

        List<String> failedLabels = failedRows.stream()
            .map(ImportOutcome::displayName)
            .toList();

        StringBuilder message = new StringBuilder();
        message.append(String.format("成功 %d", success));
        if (skipped > 0) {
            message.append(String.format("，跳过重复 %d", skipped));
        }
        if (failed > 0) {
            message.append(String.format("，失败 %d", failed));
            // 原因通常只有一两种，去重后最多列两条，避免消息过长
            String reasons = failedRows.stream()
                .map(ImportOutcome::error)
                .distinct()
                .limit(2)
                .collect(Collectors.joining("；"));
            if (!reasons.isBlank()) {
                message.append("：").append(reasons);
            }
        }

        return new BatchOperationResult(total, success, failed, failedLabels, message.toString());
    }

    /**
     * 判重索引：QQ 号与邮箱各自映射到已存在的资源名。
     *
     * <p>一次全量列出后建内存索引，而不是每行查一次库——导入是低频操作，
     * 全量一次的开销远小于 N 次查询，也不依赖尚未建立的字段索引。
     */
    private Mono<Map<String, String>> existingMemberIndex() {
        return client.listAll(Member.class, new ListOptions(), ExtensionUtil.defaultSort())
            .collectList()
            .map(members -> {
                Map<String, String> index = new HashMap<>();
                for (Member member : members) {
                    String name = member.getMetadata() != null
                        ? member.getMetadata().getName() : null;
                    if (name == null || member.getSpec() == null) {
                        continue;
                    }
                    String qq = trimToNull(member.getSpec().getQq());
                    if (qq != null) {
                        index.put("qq:" + qq, name);
                    }
                    String email = trimToNull(member.getSpec().getEmail());
                    if (email != null) {
                        index.put("email:" + email.toLowerCase(Locale.ROOT), name);
                    }
                }
                return index;
            });
    }

    private static String dedupeKey(Member.MemberSpec spec) {
        String qq = trimToNull(spec.getQq());
        if (qq != null) {
            return "qq:" + qq;
        }
        String email = trimToNull(spec.getEmail());
        if (email != null) {
            return "email:" + email.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * 行级预校验：账号名称、QQ 号、邮箱三者全空才算无效行。
     *
     * <p>注意这里<b>不</b>要求标识必须含 ASCII 字符——中文名是常态，
     * 资源名清洗后为空会由 {@link #generateMemberName} 兜底，不该因此拒收这一行。
     *
     * @return 失败原因，{@code null} 表示这一行可以入库
     */
    private static String validateRow(Member.MemberSpec spec) {
        String displayName = trimToNull(spec.getDisplayName());
        String qq = trimToNull(spec.getQq());
        String email = trimToNull(spec.getEmail());
        if (displayName == null && qq == null && email == null) {
            return "缺少账号名称 / QQ 号 / 邮箱";
        }
        return null;
    }

    /**
     * 生成资源名。
     *
     * <p>旧实现直接取原始 QQ / 邮箱拼时间戳，QQ 填成 {@code 非法QQ@#} 就会把
     * {@code @#} 和中文写进 {@code metadata.name}。这个名字同时是 URL 路径片段，
     * 之后删除必须先手工 URL 编码，否则 404。这里统一按白名单清洗并截断。
     */
    private static String generateMemberName(Member.MemberSpec spec) {
        String base = trimToNull(spec.getQq());
        if (base == null) {
            base = trimToNull(spec.getEmail());
        }
        if (base == null) {
            base = trimToNull(spec.getDisplayName());
        }
        if (base == null) {
            base = "member";
        }
        String sanitized = base.toLowerCase(Locale.ROOT)
            .replaceAll(NAME_ILLEGAL_CHARS, "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-+|-+$", "");
        if (sanitized.isEmpty()) {
            // 纯中文名 / 纯符号在清洗后什么都不剩，用原始串的哈希留个线索，
            // 至少同一个名字每次生成的前缀一致，排查时能对上人
            sanitized = "m" + Integer.toHexString(base.hashCode() & 0x7fffffff);
        }
        // 毫秒时间戳不足以保证唯一：同一批导入里 QQ 相同、名字也相同的记录会撞名，
        // 后一条直接覆盖前一条。补一段随机 UUID，批量导入才算真的"批量"。
        String suffix = "-" + System.currentTimeMillis()
            + "-" + UUID.randomUUID().toString().substring(0, 8);
        int keep = Math.max(1, NAME_MAX_LENGTH - suffix.length());
        if (sanitized.length() > keep) {
            sanitized = sanitized.substring(0, keep);
        }
        return sanitized + suffix;
    }

    /**
     * 把异常翻译成可以给用户看的一句话。
     *
     * <p>直接把 {@code e.getMessage()} 回传前端会带出内部类名
     * （如 {@code Cannot invoke "java.lang.String.replaceAll(...)" because ... is null}），
     * 既没意义也是信息泄露。
     */
    private static String friendlyError(Throwable e) {
        if (e instanceof IllegalArgumentException && e.getMessage() != null
            && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        if (e instanceof NullPointerException) {
            return "数据格式不完整，必填字段缺失";
        }
        String message = e.getMessage();
        if (message != null && message.contains("already exists")) {
            return "同名成员已存在";
        }
        return "写入失败，请检查数据格式后重试";
    }

    private static String rowLabel(Member.MemberSpec spec, int index) {
        String displayName = trimToNull(spec.getDisplayName());
        if (displayName != null) {
            return displayName;
        }
        String qq = trimToNull(spec.getQq());
        if (qq != null) {
            return qq;
        }
        String email = trimToNull(spec.getEmail());
        if (email != null) {
            return email;
        }
        return "第 " + index + " 行";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 清洗导入的 CSV 文本：去掉 UTF-8 BOM，并把换行统一成 {@code \n}。
     *
     * <p>公式前缀（{@code = + - @}）<b>不在这里处理</b>。导入端的职责是保真落库，
     * 这个阶段改写首字符会把 {@code -张三} 这类合法名字变成 {@code '-张三}；
     * 需要中和的时机是「把库里的数据写成 CSV 给表格软件打开」，由
     * {@link #escapeCsv} / {@link #escapeCsvFormula} 在导出时完成。
     */
    private static String sanitizeCsvText(String csvData) {
        if (csvData == null || csvData.isEmpty()) {
            return csvData;
        }
        String text = stripBom(csvData);
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static String stripBom(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.charAt(0) == '\ufeff' ? value.substring(1) : value;
    }

    /**
     * 导出 CSV 时对单元格做公式前缀转义，避免导出的表格被 Excel 当成公式执行。
     */
    private static String escapeCsvFormula(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
            || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    private static String[] parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());

        return values.toArray(new String[0]);
    }

    /**
     * CSV 字段转义
     */
    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        // 先中和公式前缀：导出的 CSV 会被人用表格软件打开，
        // 以 = + - @ 开头的单元格在 Excel 里会被当成公式执行（CSV 注入）
        String safe = escapeCsvFormula(value);
        // 再处理分隔符：包含逗号、引号或换行符时用引号包裹并转义引号
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    /**
     * 一行待导入的数据。
     *
     * @param label 对外回报用的行标识（优先账号名称，其次 QQ / 邮箱，最后行号）
     * @param spec 已解析的成员数据
     */
    private record ImportRow(String label, Member.MemberSpec spec) {
    }

    /**
     * 一行的导入结果。只带「给用户看的名字」和失败原因——落库后的
     * {@code metadata.name} 是另一个名字域，汇总时用不上，就不往里塞了。
     */
    private record ImportOutcome(String displayName, String error) {

        static ImportOutcome ok(String displayName) {
            return new ImportOutcome(displayName, null);
        }

        static ImportOutcome failed(String displayName, String error) {
            return new ImportOutcome(displayName, error);
        }
    }
}
