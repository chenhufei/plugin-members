package run.halo.members.finders.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
 * 成员列表缓存行为测试（B4 / A5）。
 *
 * <p>这里刻意用**真实**的 {@link CacheService}，而不是 mock：要验的正是「缓存有没有命中」，
 * mock 掉的缓存永远 miss，等于什么都没验。
 */
class MemberFinderImplCacheTest {

    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final CacheService cacheService = new CacheService();
    private final MemberFinderImpl finder = new MemberFinderImpl(client, cacheService);

    @Test
    @DisplayName("翻多页只回源一次：分页在内存切片，不再按 page:size 分裂缓存键")
    void paginationSlicesOneCachedSnapshot() {
        stubListAll(
            approved("a", "2024-01-01T00:00:00Z", "A"),
            approved("b", "2024-02-01T00:00:00Z", "B"),
            approved("c", "2024-03-01T00:00:00Z", "C"));

        var page1 = finder.listApprovedMemberList(1, 2).block();
        var page2 = finder.listApprovedMemberList(2, 2).block();

        assertThat(page1).isNotNull();
        assertThat(page1.getItems())
            .extracting(vo -> vo.getMetadata().getName())
            .containsExactly("a", "b");
        assertThat(page1.getTotal()).isEqualTo(3);

        assertThat(page2).isNotNull();
        assertThat(page2.getItems())
            .extracting(vo -> vo.getMetadata().getName())
            .containsExactly("c");
        assertThat(page2.getPage()).isEqualTo(2);

        // 关键断言：两页共用同一份全量快照，只回源一次
        verify(client, times(1))
            .listAll(eq(Member.class), any(ListOptions.class), any(Sort.class));
    }

    @Test
    @DisplayName("不同页大小也共用同一份快照")
    void differentPageSizesShareTheSameSnapshot() {
        stubListAll(
            approved("a", "2024-01-01T00:00:00Z", "A"),
            approved("b", "2024-02-01T00:00:00Z", "B"),
            approved("c", "2024-03-01T00:00:00Z", "C"));

        assertThat(finder.listApprovedMemberList(1, 10).block().getItems()).hasSize(3);
        assertThat(finder.listApprovedMemberList(1, 1).block().getItems()).hasSize(1);
        assertThat(finder.listApprovedMemberList(1, 1000).block().getItems()).hasSize(3);

        verify(client, times(1))
            .listAll(eq(Member.class), any(ListOptions.class), any(Sort.class));
    }

    @Test
    @DisplayName("分页结果是独立副本，调用方改不动缓存")
    void pageItemsAreIndependentCopies() {
        stubListAll(
            approved("a", "2024-01-01T00:00:00Z", "A"),
            approved("b", "2024-02-01T00:00:00Z", "B"));

        var page = finder.listApprovedMemberList(1, 10).block();
        assertThat(page).isNotNull();

        // 不可变：任何就地修改都会抛，而不是悄悄把缓存里的数据改掉
        assertThatThrownBy(() -> page.getItems().clear())
            .isInstanceOf(UnsupportedOperationException.class);

        var again = finder.listApprovedMemberList(1, 10).block();
        assertThat(again).isNotNull();
        assertThat(again.getItems()).hasSize(2);
    }

    @Test
    @DisplayName("分组定义变更只清分组视图，不连带清掉成员列表缓存")
    void groupDefinitionChangeKeepsMemberListCache() {
        stubListAll(approved("a", "2024-01-01T00:00:00Z", "A"));

        finder.listApprovedMemberList(1, 10).block();
        finder.evictGroupDefinitionsCache();
        finder.listApprovedMemberList(1, 10).block();

        // 分组改名不影响成员列表本身，缓存应当仍然命中
        verify(client, times(1))
            .listAll(eq(Member.class), any(ListOptions.class), any(Sort.class));
    }

    @Test
    @DisplayName("成员变更后列表缓存必须失效")
    void memberChangeEvictsListCache() {
        stubListAll(approved("a", "2024-01-01T00:00:00Z", "A"));

        finder.listApprovedMemberList(1, 10).block();
        finder.evictCache();
        finder.listApprovedMemberList(1, 10).block();

        // 第二次必须回源，否则刚审核通过的成员不会出现在前台
        verify(client, times(2))
            .listAll(eq(Member.class), any(ListOptions.class), any(Sort.class));
    }

    @Test
    @DisplayName("没有缓存时不做任何失效动作")
    void evictIfChangedIsNoopWithoutCache() {
        assertThat(finder.evictIfVisibleStateChanged(
            approved("a", "2024-01-01T00:00:00Z", "A"))).isFalse();
    }

    @Test
    @DisplayName("可见状态没变就不清缓存（否则 reconcile 会把命中率打回 0）")
    void unchangedVisibleStateKeepsCache() {
        stubListAll(approved("a", "2024-01-01T00:00:00Z", "A"));
        finder.listApprovedMemberList(1, 10).block();

        var same = approved("a", "2024-01-01T00:00:00Z", "A");
        assertThat(finder.evictIfVisibleStateChanged(same)).isFalse();

        finder.listApprovedMemberList(1, 10).block();
        verify(client, times(1))
            .listAll(eq(Member.class), any(ListOptions.class), any(Sort.class));
    }

    @Test
    @DisplayName("直接改 CRD 把成员置为 APPROVED 时，缓存兜底失效")
    void newlyApprovedMemberEvictsCache() {
        stubListAll(approved("a", "2024-01-01T00:00:00Z", "A"));
        finder.listApprovedMemberList(1, 10).block();

        // 这条成员从没进过缓存，但现在应当可见
        var newcomer = approved("b", "2024-02-01T00:00:00Z", "B");
        assertThat(finder.evictIfVisibleStateChanged(newcomer)).isTrue();
    }

    @Test
    @DisplayName("成员被下架时缓存兜底失效")
    void rejectedMemberEvictsCache() {
        stubListAll(approved("a", "2024-01-01T00:00:00Z", "A"));
        finder.listApprovedMemberList(1, 10).block();

        var rejected = approved("a", "2024-01-01T00:00:00Z", "A");
        rejected.getSpec().setStatus("REJECTED");
        assertThat(finder.evictIfVisibleStateChanged(rejected)).isTrue();
    }

    @Test
    @DisplayName("换分组也算可见状态变化")
    void groupChangeEvictsCache() {
        stubListAll(approved("a", "2024-01-01T00:00:00Z", "A"));
        finder.listApprovedMemberList(1, 10).block();

        var moved = approved("a", "2024-01-01T00:00:00Z", "A");
        moved.getSpec().setGroupName("union");
        assertThat(finder.evictIfVisibleStateChanged(moved)).isTrue();
    }

    @Test
    @DisplayName("本就不在列表里的成员改状态，不需要清缓存")
    void pendingMemberDoesNotEvict() {
        stubListAll(approved("a", "2024-01-01T00:00:00Z", "A"));
        finder.listApprovedMemberList(1, 10).block();

        var pending = approved("c", "2024-03-01T00:00:00Z", "C");
        pending.getSpec().setStatus("PENDING");
        assertThat(finder.evictIfVisibleStateChanged(pending)).isFalse();
    }

    private void stubListAll(Member... members) {
        when(client.listAll(eq(Member.class), any(ListOptions.class), any(Sort.class)))
            .thenReturn(Flux.fromArray(members));
    }

    private static Member approved(String name, String joinedAt, String displayName) {
        var metadata = new Metadata();
        metadata.setName(name);
        var spec = new Member.MemberSpec();
        spec.setDisplayName(displayName);
        spec.setJoinedAt(joinedAt);
        spec.setStatus("APPROVED");
        var member = new Member();
        member.setMetadata(metadata);
        member.setSpec(spec);
        return member;
    }
}
