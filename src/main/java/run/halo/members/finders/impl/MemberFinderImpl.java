package run.halo.members.finders.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.members.Member;
import run.halo.members.MemberGroup;
import run.halo.members.cache.CacheService;
import run.halo.members.finders.MemberFinder;
import run.halo.members.vo.MemberGroupVo;
import run.halo.members.vo.MemberVo;

/**
 * 成员查询实现 - v2.0.0 优化版本
 * 解决N+1查询问题，添加缓存支持
 * 
 * @author Sky
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberFinderImpl implements MemberFinder {

    private final ReactiveExtensionClient client;
    private final CacheService cacheService;
    
    // 缓存键前缀
    private static final String CACHE_PREFIX_GROUPS = "member:groups";
    private static final String CACHE_PREFIX_MEMBERS = "member:members";
    private static final String CACHE_PREFIX_APPROVED = "member:approved";
    
    // 缓存时间
    private static final Duration CACHE_TTL_GROUPS = Duration.ofMinutes(10);
    private static final Duration CACHE_TTL_MEMBERS = Duration.ofMinutes(5);

    @Override
    public Mono<ListResult<MemberGroupVo>> listApprovedMembers(@Nullable Integer page, @Nullable Integer size) {
        String cacheKey = CACHE_PREFIX_APPROVED + ":all";
        
        // 尝试从缓存获取
        ListResult<MemberGroupVo> cached = cachedListResult(cacheKey);
        if (cached != null) {
            log.debug("从缓存获取已审核成员列表");
            return Mono.just(cached);
        }
        
        // 批量查询所有数据，避免N+1问题
        return Mono.zip(
            getAllApprovedMembers(),
            getAllGroups()
        )
        .map(tuple -> {
            List<MemberVo> allMembers = tuple.getT1();
            List<MemberGroupVo> allGroups = tuple.getT2();
            
            // 按分组聚合成员
            Map<String, List<MemberVo>> membersByGroup = allMembers.stream()
                .collect(Collectors.groupingBy(
                    member -> member.getSpec().getGroupName() != null ? 
                        member.getSpec().getGroupName() : "default"
                ));
            
            Set<String> knownGroupNames = allGroups.stream()
                .map(group -> group.getMetadata().getName())
                .collect(Collectors.toSet());

            // 为每个分组设置成员列表
            allGroups.forEach(group -> {
                String groupName = group.getMetadata().getName();
                List<MemberVo> groupMembers = new ArrayList<>(
                    membersByGroup.getOrDefault(groupName, List.of())
                );
                
                // 按优先级排序成员
                groupMembers.sort(this::compareMemberPriorityDesc);
                
                group.setMembers(groupMembers);
            });

            List<MemberVo> ungroupedMembers = allMembers.stream()
                .filter(member -> {
                    String groupName = member.getSpec().getGroupName();
                    return groupName == null || !knownGroupNames.contains(groupName);
                })
                .sorted(this::compareMemberPriorityDesc)
                .toList();
            if (!ungroupedMembers.isEmpty()) {
                allGroups.add(createVirtualGroup("ungrouped", "未分组", ungroupedMembers));
            }
            
            // 按分组优先级排序
            List<MemberGroupVo> visibleGroups = allGroups.stream()
                .filter(group -> group.getMembers() != null && !group.getMembers().isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));

            visibleGroups.sort(Comparator.comparing(
                g -> g.getSpec().getPriority(), 
                Comparator.nullsLast(Comparator.reverseOrder())
            ));
            
            ListResult<MemberGroupVo> result = new ListResult<>(visibleGroups);
            
            // 缓存结果
            cacheService.put(cacheKey, result, CACHE_TTL_MEMBERS);
            
            return result;
        })
        .doOnSuccess(result -> log.debug("已审核成员列表查询完成，分组数: {}", result.getItems().size()))
        .doOnError(error -> log.error("查询已审核成员列表失败", error));
    }

    @Override
    public Mono<ListResult<MemberVo>> listApprovedMemberList(@Nullable Integer page,
        @Nullable Integer size) {
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size, 1000);
        String cacheKey = CACHE_PREFIX_APPROVED + ":flat:" + normalizedPage + ":" + normalizedSize;

        ListResult<MemberVo> cached = cachedListResult(cacheKey);
        if (cached != null) {
            log.debug("从缓存获取已审核成员平铺列表");
            return Mono.just(cached);
        }

        return getAllApprovedMembers()
            .map(members -> {
                members.sort(this::compareMemberPriorityDesc);
                int total = members.size();
                List<MemberVo> pageItems = paginate(members, normalizedPage, normalizedSize);
                ListResult<MemberVo> result = new ListResult<>(
                    normalizedPage,
                    normalizedSize,
                    total,
                    pageItems
                );
                cacheService.put(cacheKey, result, CACHE_TTL_MEMBERS);
                return result;
            });
    }

    @Override
    public Flux<MemberVo> listMembersByGroup(String groupName) {
        String cacheKey = CACHE_PREFIX_MEMBERS + ":" + groupName;
        
        // 尝试从缓存获取
        List<MemberVo> cached = cachedList(cacheKey);
        if (cached != null) {
            log.debug("从缓存获取分组成员: {}", groupName);
            return Flux.fromIterable(cached);
        }
        
        return client.listAll(Member.class, null, null)
            .filter(member -> "APPROVED".equals(member.getSpec().getStatus())
                && groupName.equals(member.getSpec().getGroupName()))
            .sort((m1, m2) -> {
                Integer p1 = m1.getSpec().getPriority();
                Integer p2 = m2.getSpec().getPriority();
                if (p1 == null && p2 == null) return 0;
                if (p1 == null) return 1;
                if (p2 == null) return -1;
                return p2.compareTo(p1); // 降序
            })
            .map(MemberVo::from)
            .collectList()
            .doOnSuccess(members -> {
                // 缓存结果
                cacheService.put(cacheKey, members, CACHE_TTL_MEMBERS);
                log.debug("分组成员查询完成: {}, 成员数: {}", groupName, members.size());
            })
            .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Flux<MemberGroupVo> listAllGroups() {
        String cacheKey = CACHE_PREFIX_GROUPS + ":all";
        
        // 尝试从缓存获取
        List<MemberGroupVo> cached = cachedList(cacheKey);
        if (cached != null) {
            log.debug("从缓存获取所有分组");
            return Flux.fromIterable(cached);
        }
        
        return client.listAll(MemberGroup.class, null, null)
            .sort((g1, g2) -> {
                Integer p1 = g1.getSpec().getPriority();
                Integer p2 = g2.getSpec().getPriority();
                if (p1 == null && p2 == null) return 0;
                if (p1 == null) return 1;
                if (p2 == null) return -1;
                return p2.compareTo(p1); // 降序
            })
            .map(MemberGroupVo::from)
            .collectList()
            .doOnSuccess(groups -> {
                // 缓存结果
                cacheService.put(cacheKey, groups, CACHE_TTL_GROUPS);
                log.debug("所有分组查询完成: {}", groups.size());
            })
            .flatMapMany(Flux::fromIterable);
    }
    
    /**
     * 获取所有已审核成员（批量查询）
     */
    private Mono<List<MemberVo>> getAllApprovedMembers() {
        return client.listAll(Member.class, null, null)
            .filter(member -> "APPROVED".equals(member.getSpec().getStatus()))
            .map(MemberVo::from)
            .collectList();
    }
    
    /**
     * 获取所有分组（批量查询）
     */
    private Mono<List<MemberGroupVo>> getAllGroups() {
        return listAllGroups().collectList();
    }
    
    /**
     * 清除相关缓存
     * 当成员或分组数据发生变化时调用
     */
    public void evictCache() {
        cacheService.evictByPattern(CACHE_PREFIX_GROUPS + "*");
        cacheService.evictByPattern(CACHE_PREFIX_MEMBERS + "*");
        cacheService.evictByPattern(CACHE_PREFIX_APPROVED + "*");
        log.info("已清除成员相关缓存");
    }
    
    /**
     * 清除特定分组的缓存
     */
    public void evictGroupCache(String groupName) {
        cacheService.evict(CACHE_PREFIX_MEMBERS + ":" + groupName);
        cacheService.evictByPattern(CACHE_PREFIX_APPROVED + "*");
        log.debug("已清除分组缓存: {}", groupName);
    }

    private int compareMemberPriorityDesc(MemberVo m1, MemberVo m2) {
        Integer p1 = m1.getSpec().getPriority();
        Integer p2 = m2.getSpec().getPriority();
        if (p1 == null && p2 == null) {
            return 0;
        }
        if (p1 == null) {
            return 1;
        }
        if (p2 == null) {
            return -1;
        }
        return p2.compareTo(p1);
    }

    private MemberGroupVo createVirtualGroup(String name, String displayName, List<MemberVo> members) {
        Metadata metadata = new Metadata();
        metadata.setName(name);

        MemberGroup.MemberGroupSpec spec = new MemberGroup.MemberGroupSpec();
        spec.setDisplayName(displayName);
        spec.setPriority(0);

        return MemberGroupVo.builder()
            .metadata(metadata)
            .spec(spec)
            .members(members)
            .build();
    }

    private int normalizePage(@Nullable Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private int normalizeSize(@Nullable Integer size, int defaultSize) {
        if (size == null || size < 1) {
            return defaultSize;
        }
        return Math.min(size, 1000);
    }

    private List<MemberVo> paginate(List<MemberVo> members, int page, int size) {
        int fromIndex = Math.min((page - 1) * size, members.size());
        int toIndex = Math.min(fromIndex + size, members.size());
        return members.subList(fromIndex, toIndex);
    }

    @SuppressWarnings("unchecked")
    private <T> ListResult<T> cachedListResult(String cacheKey) {
        return cacheService.get(cacheKey, ListResult.class);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> cachedList(String cacheKey) {
        return cacheService.get(cacheKey, List.class);
    }
}
