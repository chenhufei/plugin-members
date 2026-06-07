package run.halo.members.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.members.Member;
import run.halo.members.vo.MemberVo;

/**
 * Server-side member filtering, sorting, and pagination for console views.
 */
@Service
@RequiredArgsConstructor
public class MemberQueryService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 500;

    private final ReactiveExtensionClient client;

    public Mono<ListResult<MemberVo>> listMembers(MemberQuery query) {
        MemberQuery normalizedQuery = query.normalized();

        return client.listAll(Member.class, null, null)
            .map(MemberVo::from)
            .filter(member -> matchesStatus(member, normalizedQuery.status()))
            .filter(member -> matchesGroup(member, normalizedQuery.groupName()))
            .filter(member -> matchesKeyword(member, normalizedQuery.keyword()))
            .collectList()
            .map(members -> toListResult(members, normalizedQuery));
    }

    private ListResult<MemberVo> toListResult(List<MemberVo> members, MemberQuery query) {
        members.sort(comparator(query.sort()));

        int total = members.size();
        int fromIndex = Math.min((query.page() - 1) * query.size(), total);
        int toIndex = Math.min(fromIndex + query.size(), total);

        return new ListResult<>(
            query.page(),
            query.size(),
            total,
            members.subList(fromIndex, toIndex)
        );
    }

    private boolean matchesStatus(MemberVo member, String status) {
        return StringUtils.isBlank(status) || status.equals(member.getSpec().getStatus());
    }

    private boolean matchesGroup(MemberVo member, String groupName) {
        return StringUtils.isBlank(groupName) || groupName.equals(member.getSpec().getGroupName());
    }

    private boolean matchesKeyword(MemberVo member, String keyword) {
        if (StringUtils.isBlank(keyword)) {
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
            case "priority-asc" -> Comparator
                .comparingInt((MemberVo member) -> priority(member))
                .thenComparing(this::createdAt, Comparator.reverseOrder());
            case "priority-desc" -> Comparator
                .comparingInt((MemberVo member) -> priority(member))
                .reversed()
                .thenComparing(this::createdAt, Comparator.reverseOrder());
            case "createdTime-asc" -> Comparator.comparing(this::createdAt);
            case "name-asc" -> Comparator.comparing(this::displayName);
            case "name-desc" -> Comparator.comparing(this::displayName).reversed();
            case "status-priority" -> Comparator
                .comparingInt((MemberVo member) -> statusOrder(member.getSpec().getStatus()))
                .thenComparing(this::createdAt, Comparator.reverseOrder());
            case "createdTime-desc" -> Comparator.comparing(this::createdAt).reversed();
            default -> Comparator.comparing(this::createdAt).reversed();
        };
    }

    private int priority(MemberVo member) {
        Integer priority = member.getSpec().getPriority();
        return priority == null ? 0 : priority;
    }

    private Instant createdAt(MemberVo member) {
        Instant creationTimestamp = member.getMetadata().getCreationTimestamp();
        return creationTimestamp == null ? Instant.EPOCH : creationTimestamp;
    }

    private String displayName(MemberVo member) {
        return StringUtils.defaultString(member.getSpec().getDisplayName());
    }

    private int statusOrder(String status) {
        return switch (StringUtils.defaultString(status)) {
            case "PENDING" -> 0;
            case "REJECTED" -> 1;
            case "APPROVED" -> 2;
            default -> 3;
        };
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
