package run.halo.members.finders.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import reactor.core.publisher.Flux;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.members.Member;
import run.halo.members.cache.CacheService;

/**
 * 成员排序的集成路径测试（F5 / F10）。
 */
class MemberFinderImplSortTest {

    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final CacheService cacheService = mock(CacheService.class);
    private final MemberFinderImpl finder = new MemberFinderImpl(client, cacheService);

    @Test
    @DisplayName("按加入时间升序；同一时间再按名称，最后按资源名")
    void sortsByJoinedAtThenName() {
        var later = approved("a", "2024-05-01T00:00:00Z", "Zoe");
        var sameTimeLi = approved("b", "2024-01-01T00:00:00Z", "Li");
        var sameTimeAn = approved("c", "2024-01-01T00:00:00Z", "An");
        stubListAll(later, sameTimeLi, sameTimeAn);

        var result = finder.listApprovedMemberList(1, 100).block();

        assertThat(result).isNotNull();
        assertThat(result.getItems())
            .extracting(vo -> vo.getMetadata().getName())
            .containsExactly("c", "b", "a");
    }

    @Test
    @DisplayName("joinedAt 缺失时按创建时间排，不会排到最前")
    void membersWithoutJoinedAtSortByCreationTimestamp() {
        var withJoinedAt = approved("a", "2024-05-01T00:00:00Z", "Zoe");
        var withoutJoinedAt = member("b", null, "Adam");
        withoutJoinedAt.getSpec().setStatus("APPROVED");
        withoutJoinedAt.getMetadata()
            .setCreationTimestamp(java.time.Instant.parse("2024-02-01T00:00:00Z"));
        stubListAll(withJoinedAt, withoutJoinedAt);

        var result = finder.listApprovedMemberList(1, 100).block();

        assertThat(result.getItems())
            .extracting(vo -> vo.getMetadata().getName())
            .containsExactly("b", "a");
    }

    private void stubListAll(Member... members) {
        when(client.listAll(eq(Member.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.fromArray(members));
    }

    private static Member approved(String name, String joinedAt, String displayName) {
        var member = member(name, joinedAt, displayName);
        member.getSpec().setStatus("APPROVED");
        return member;
    }

    private static Member member(String name, String joinedAt, String displayName) {
        var metadata = new Metadata();
        metadata.setName(name);
        var spec = new Member.MemberSpec();
        spec.setDisplayName(displayName);
        spec.setJoinedAt(joinedAt);
        var member = new Member();
        member.setMetadata(metadata);
        member.setSpec(spec);
        return member;
    }
}
