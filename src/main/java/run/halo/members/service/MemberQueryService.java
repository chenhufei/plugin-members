package run.halo.members.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.members.Member;
import run.halo.members.vo.MemberVo;

import static run.halo.app.extension.index.query.Queries.equal;

/**
 * Server-side member filtering, sorting, and pagination for console views.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberQueryService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    // The grouped management view deliberately requests the complete collection.
    // Keep the limit bounded, but do not silently truncate that view at 500 records.
    // 保持 10000 不动：MCP 的六个批量工具（批量审核建议 / 批量分组 / 生成令牌 / 导出 /
    // 高级搜索 / 重复检测）本来就要求扫全量，压低上限等于静默丢数据。真正该做的是把
    // status 与 groupName 下推到索引（已做），这样这些工具取到的集合已经小了一个量级。
    public static final int MAX_SIZE = 10_000;

    private final ReactiveExtensionClient client;

    public Mono<ListResult<MemberVo>> listMembers(MemberQuery query) {
        MemberQuery normalizedQuery = query.normalized();

        // 状态与分组都有索引（MemberPlugin 注册了 spec.status / spec.groupName），
        // 下推到 ListOptions 后只回传匹配的那部分，不再「全表拉回来再内存 filter」。
        // MCP 那边 6 个工具都按 status/groupName 取集合，之前每次都是全量 listAll。
        // 关键字是模糊匹配、没有索引，仍留在内存里过滤。
        var optionsBuilder = ListOptions.builder();
        if (normalizedQuery.status() != null) {
            optionsBuilder.andQuery(equal("spec.status", normalizedQuery.status()));
        }
        if (normalizedQuery.groupName() != null) {
            optionsBuilder.andQuery(equal("spec.groupName", normalizedQuery.groupName()));
        }

        return listByOptions(optionsBuilder.build())
            // 先过滤实体、最后再转 VO：MemberVo.from 要解析加入时间、算摘要，
            // 对不匹配的记录做这些纯属白干（关键词搜索时尤其明显）。
            // 索引查询缩小范围后，matchesStatus / matchesGroup 退化成兜底校验：
            // 索引重建或字段为空时仍能保证结果集正确。
            .filter(member -> matchesStatus(member, normalizedQuery.status()))
            .filter(member -> matchesGroup(member, normalizedQuery.groupName()))
            .filter(member -> matchesKeyword(member, normalizedQuery.keyword()))
            .map(MemberVo::from)
            .collectList()
            .map(members -> toListResult(members, normalizedQuery));
    }

    /**
     * 按查询条件取成员，索引不可用时回退全表。
     *
     * <p>插件升级会重建索引，重建期间查询可能报错（而不是返回空）。后台列表宁可慢一点，
     * 也不能因为一次索引重建就打不开。
     */
    private Flux<Member> listByOptions(ListOptions options) {
        return client.listAll(Member.class, options, ExtensionUtil.defaultSort())
            .onErrorResume(error -> {
                log.warn("成员索引查询失败，回退全表扫描", error);
                return client.listAll(Member.class, new ListOptions(),
                    ExtensionUtil.defaultSort());
            });
    }

    private ListResult<MemberVo> toListResult(List<MemberVo> members, MemberQuery query) {
        members.sort(comparator(query.sort()));

        int total = members.size();
        // page 只做了「< 1 归一」，没有上限：(page-1)*size 在 int 下溢出为负数，
        // subList(负数, ...) 会直接抛 IndexOutOfBoundsException。用 long 计算并夹到 [0, total]。
        long offset = (long) (query.page() - 1) * query.size();
        int fromIndex = (int) Math.min(Math.max(offset, 0L), total);
        int toIndex = Math.min(fromIndex + query.size(), total);

        return new ListResult<>(
            query.page(),
            query.size(),
            total,
            members.subList(fromIndex, toIndex)
        );
    }

    private boolean matchesStatus(Member member, String status) {
        return StringUtils.isBlank(status)
            || status.equals(member.getSpec() == null ? null : member.getSpec().getStatus());
    }

    private boolean matchesGroup(Member member, String groupName) {
        return StringUtils.isBlank(groupName)
            || groupName.equals(member.getSpec() == null
                ? null : member.getSpec().getGroupName());
    }

    private boolean matchesKeyword(Member member, String keyword) {
        if (StringUtils.isBlank(keyword) || member.getSpec() == null) {
            return true;
        }

        String searchTerm = keyword.toLowerCase();
        var spec = member.getSpec();
        return contains(spec.getDisplayName(), searchTerm)
            || contains(spec.getEmail(), searchTerm)
            || contains(spec.getSchool(), searchTerm)
            || contains(spec.getQq(), searchTerm);
    }

    private boolean contains(String value, String searchTerm) {
        return value != null && value.toLowerCase().contains(searchTerm);
    }

    private Comparator<MemberVo> comparator(String sort) {
        return switch (sort) {
            case "createdTime-asc" -> Comparator.comparing(this::createdAt);
            case "name-asc" -> Comparator.comparing(this::displayName);
            case "name-desc" -> Comparator.comparing(this::displayName).reversed();
            case "createdTime-desc" -> Comparator.comparing(this::createdAt).reversed();
            default -> Comparator.comparing(this::createdAt).reversed();
        };
    }

    private Instant createdAt(MemberVo member) {
        Instant creationTimestamp = member.getMetadata().getCreationTimestamp();
        return creationTimestamp == null ? Instant.EPOCH : creationTimestamp;
    }

    private String displayName(MemberVo member) {
        return StringUtils.defaultString(member.getSpec().getDisplayName());
    }


    public record MemberQuery(
        int page,
        int size,
        String keyword,
        String status,
        String groupName,
        String sort
    ) {
        public MemberQuery normalized() {
            int normalizedPage = page < 1 ? DEFAULT_PAGE : page;
            int normalizedSize = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
            return new MemberQuery(
                normalizedPage,
                normalizedSize,
                StringUtils.trimToNull(keyword),
                StringUtils.trimToNull(status),
                StringUtils.trimToNull(groupName),
                StringUtils.defaultIfBlank(sort, "createdTime-desc")
            );
        }
    }
}
