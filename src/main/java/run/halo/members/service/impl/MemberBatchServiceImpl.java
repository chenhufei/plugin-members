package run.halo.members.service.impl;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.MetadataUtil;
import run.halo.members.Member;
import run.halo.members.finders.impl.MemberFinderImpl;
import run.halo.members.service.MemberBatchService;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION;
import static run.halo.members.MemberConstant.REVIEW_NOTIFICATION_PENDING;

/**
 * 成员批量操作服务实现
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
        
        Flux<Member> memberFlux = memberNames != null && !memberNames.isEmpty()
            ? Flux.fromIterable(memberNames).flatMap(name -> client.fetch(Member.class, name))
            : client.listAll(Member.class, new run.halo.app.extension.ListOptions(),
                run.halo.app.extension.ExtensionUtil.defaultSort());
        
        return memberFlux
            .collectList()
            .map(members -> {
                StringBuilder csv = new StringBuilder();
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
        
        Flux<Member> memberFlux = memberNames != null && !memberNames.isEmpty()
            ? Flux.fromIterable(memberNames).flatMap(name -> client.fetch(Member.class, name))
            : client.listAll(Member.class, new run.halo.app.extension.ListOptions(),
                run.halo.app.extension.ExtensionUtil.defaultSort());
        
        return memberFlux
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
    
    @Override
    public Mono<BatchOperationResult> batchImportFromJSON(String jsonData, String defaultGroup) {
        log.info("从JSON批量导入成员: defaultGroup={}", defaultGroup);
        
        return Mono.fromCallable(() -> {
            var rootNode = MAPPER.readTree(jsonData);
            if (!rootNode.isArray()) {
                throw new IllegalArgumentException("JSON数据必须是数组格式");
            }
            
            List<Member.MemberSpec> specs = new java.util.ArrayList<>();
            for (var node : rootNode) {
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
                specs.add(spec);
            }
            return specs;
        }).flatMap(specs -> {
            if (specs.isEmpty()) {
                return Mono.just(BatchOperationResult.failed(0, "JSON数据为空"));
            }
            
            return Flux.fromIterable(specs)
                .flatMap(spec -> {
                    Member member = new Member();
                    member.setSpec(spec);
                    var metadata = new run.halo.app.extension.Metadata();
                    metadata.setName(generateMemberName(spec));
                    member.setMetadata(metadata);
                    return client.create(member)
                        .thenReturn(member.getMetadata().getName())
                        .onErrorResume(e -> {
                            log.error("导入成员失败: {}", spec.getDisplayName(), e);
                            return Mono.empty();
                        });
                }, BATCH_CONCURRENCY)
                .collectList()
                .map(successList -> toBatchResult(
                    specs.stream().map(Member.MemberSpec::getDisplayName).toList(),
                    successList
                ));
        }).onErrorResume(e -> {
            log.error("解析JSON失败", e);
            return Mono.just(BatchOperationResult.failed(0, "解析JSON失败: " + e.getMessage()));
        });
    }
    
    @Override
    public Mono<BatchOperationResult> batchImportFromCSV(String csvData, String defaultGroup) {
        log.info("从CSV批量导入成员: defaultGroup={}", defaultGroup);
        
        return Mono.fromCallable(() -> {
            String[] lines = csvData.split("\n");
            if (lines.length < 2) {
                throw new IllegalArgumentException("CSV数据必须包含头部和至少一行数据");
            }
            
            String[] headers = lines[0].split(",");
            List<Member.MemberSpec> specs = new java.util.ArrayList<>();
            
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                
                String[] values = parseCsvLine(line);
                if (values.length < headers.length) continue;
                
                Member.MemberSpec spec = new Member.MemberSpec();
                for (int j = 0; j < headers.length; j++) {
                    String header = headers[j].trim();
                    String value = values[j].trim();
                    switch (header) {
                        case "displayName", "账号名称" -> spec.setDisplayName(value);
                        case "email", "邮箱" -> spec.setEmail(value);
                        case "school", "所属学校" -> spec.setSchool(value);
                        case "qq", "QQ号" -> spec.setQq(value);
                        case "groupName", "分组" -> spec.setGroupName(value);
                        case "qqFriendLink", "QQ加好友链接" -> spec.setQqFriendLink(value);
                    }
                }
                
                if (spec.getGroupName() == null && defaultGroup != null) {
                    spec.setGroupName(defaultGroup);
                }
                spec.setStatus("PENDING");
                specs.add(spec);
            }
            return specs;
        }).flatMap(specs -> {
            if (specs.isEmpty()) {
                return Mono.just(BatchOperationResult.failed(0, "CSV数据为空"));
            }
            
            return Flux.fromIterable(specs)
                .flatMap(spec -> {
                    Member member = new Member();
                    member.setSpec(spec);
                    var metadata = new run.halo.app.extension.Metadata();
                    metadata.setName(generateMemberName(spec));
                    member.setMetadata(metadata);
                    return client.create(member)
                        .thenReturn(member.getMetadata().getName())
                        .onErrorResume(e -> {
                            log.error("导入成员失败: {}", spec.getDisplayName(), e);
                            return Mono.empty();
                        });
                }, BATCH_CONCURRENCY)
                .collectList()
                .map(successList -> toBatchResult(
                    specs.stream().map(Member.MemberSpec::getDisplayName).toList(),
                    successList
                ));
        }).onErrorResume(e -> {
            log.error("解析CSV失败", e);
            return Mono.just(BatchOperationResult.failed(0, "解析CSV失败: " + e.getMessage()));
        });
    }
    
    private String generateMemberName(Member.MemberSpec spec) {
        String base = spec.getQq() != null && !spec.getQq().isEmpty() 
            ? spec.getQq() 
            : spec.getEmail() != null && !spec.getEmail().isEmpty()
                ? spec.getEmail().replaceAll("[^a-zA-Z0-9]", "")
                : spec.getDisplayName().replaceAll("[^a-zA-Z0-9]", "");
        return base + "-" + System.currentTimeMillis();
    }
    
    private String[] parseCsvLine(String line) {
        List<String> values = new java.util.ArrayList<>();
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
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        // 如果包含逗号、引号或换行符，需要用引号包裹并转义引号
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
