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
import run.halo.members.Member;
import run.halo.members.finders.impl.MemberFinderImpl;
import run.halo.members.service.MemberBatchService;

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
    
    @Override
    public Mono<BatchOperationResult> batchChangePriority(List<String> memberNames, Integer priority) {
        if (priority == null) {
            return Mono.just(BatchOperationResult.failed(sizeOf(memberNames), "优先级不能为空"));
        }
        
        log.info("批量修改优先级: count={}, priority={}", sizeOf(memberNames), priority);

        return executeBatch(memberNames, "修改成员优先级失败", member -> {
            member.getSpec().setPriority(priority);
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
            : client.listAll(Member.class, null, null);
        
        return memberFlux
            .collectList()
            .map(members -> {
                StringBuilder csv = new StringBuilder();
                // CSV 头部
                csv.append("账号名称,邮箱,所属学校,QQ号,分组,状态,优先级,QQ加好友链接,创建时间\n");
                
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
                    csv.append(spec.getPriority() != null ? String.valueOf(spec.getPriority()) : "0").append(",");
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
            : client.listAll(Member.class, null, null);
        
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
