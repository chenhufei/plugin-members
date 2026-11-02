package run.halo.members.finders.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * 缓存里这些成员的「可见状态」快照（资源名 → status|groupName）。
     * 只占几十个短字符串，用来判断库里的数据是否已经与缓存脱节。
     */
    private final Map<String, String> visibleFingerprints = new ConcurrentHashMap<>();
    
    // 缓存键前缀
    private static final String CACHE_PREFIX_GROUPS = "member:groups";
    private static final String CACHE_PREFIX_MEMBERS = "member:members";
    private static final String CACHE_PREFIX_APPROVED = "member:approved";

    /** 已审核成员的「全量有序列表」唯一 key，分页在内存切片，不再按 page:size 分裂。 */
    private static final String CACHE_KEY_APPROVED_FLAT = CACHE_PREFIX_APPROVED + ":flat";

    /** 分组视图（按分组聚合的成员）与分组定义列表，两者是固定 key。 */
    private static final String CACHE_KEY_APPROVED_ALL = CACHE_PREFIX_APPROVED + ":all";
    private static final String CACHE_KEY_GROUPS_ALL = CACHE_PREFIX_GROUPS + ":all";
    
    // 缓存时间
    private static final Duration CACHE_TTL_GROUPS = Duration.ofMinutes(10);
    private static final Duration CACHE_TTL_MEMBERS = Duration.ofMinutes(5);

    /**
     * 已审核成员（按分组）。
     *
     * <p>这个方法从来不支持分页：旧签名收了 page/size 却完全忽略，缓存键也固定为 {@code :all}，
     * 将来真传 page 只会拿到全量第一页。现在直接把参数去掉，签名不再说谎。
     */
    @Override
    public Mono<ListResult<MemberGroupVo>> listApprovedMembers() {
        String cacheKey = CACHE_KEY_APPROVED_ALL;
        
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
                .filter(member -> member.getSpec() != null)
                .collect(Collectors.groupingBy(
                    member -> member.getSpec().getGroupName() != null ?
                        member.getSpec().getGroupName() : "default"
                ));
            
            Set<String> knownGroupNames = allGroups.stream()
                .map(group -> group.getMetadata().getName())
                .collect(Collectors.toSet());

            // 为每个分组附上成员列表。
            // 注意：allGroups 的元素来自 listAllGroups() 的缓存实例，**不能**在原对象上
            // setMembers：那样会把缓存里的分组悄悄改写成「带成员的分组」，随后公开分组接口
            // 就会把成员的 email / qq / status 一起吐给匿名访客。这里一律返回副本。
            List<MemberGroupVo> groupsWithMembers = allGroups.stream()
                .map(group -> {
                    List<MemberVo> groupMembers = new ArrayList<>(
                        membersByGroup.getOrDefault(group.getMetadata().getName(), List.of())
                    );

                    // 使用稳定的创建时间与名称排序，不再依赖旧的 priority 字段。
                    groupMembers.sort(this::compareMemberOrder);

                    return group.toBuilder().members(groupMembers).build();
                })
                .collect(Collectors.toCollection(ArrayList::new));

            List<MemberVo> ungroupedMembers = allMembers.stream()
                .filter(member -> member.getSpec() != null)
                .filter(member -> {
                    String groupName = member.getSpec().getGroupName();
                    return groupName == null || !knownGroupNames.contains(groupName);
                })
                .sorted(this::compareMemberOrder)
                .toList();
            if (!ungroupedMembers.isEmpty()) {
                groupsWithMembers.add(createVirtualGroup("ungrouped", "未分组", ungroupedMembers));
            }

            // 按分组优先级排序
            List<MemberGroupVo> visibleGroups = groupsWithMembers.stream()
                .filter(group -> group.getMembers() != null && !group.getMembers().isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));

            visibleGroups.sort(Comparator
                .comparing((MemberGroupVo group) -> group.getSpec() != null
                        ? group.getSpec().getDisplayName() : null,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(group -> group.getMetadata() != null
                        ? group.getMetadata().getName() : ""));
            
            ListResult<MemberGroupVo> result = new ListResult<>(visibleGroups);
            
            // 缓存结果，同时记下「缓存里这些成员长什么样」
            rememberVisibleState(visibleGroups);
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

        List<MemberVo> cached = cachedList(CACHE_KEY_APPROVED_FLAT);
        if (cached != null) {
            log.debug("从缓存获取已审核成员平铺列表");
            return Mono.just(toPage(cached, normalizedPage, normalizedSize));
        }

        return getAllApprovedMembers()
            .map(members -> {
                members.sort(this::compareMemberOrder);
                // 只缓存「全量已排序」这一份，分页在内存里切片。
                // 旧实现把分页结果按 page:size 各存一个 key，翻 10 页就写 10 份全量列表的
                // 切片，512 条上限很快被挤爆，反而把别的热点缓存冲掉。
                // List.copyOf 保证缓存对象不可变：调用方拿到的分页是独立副本，
                // 任何就地修改都不会写穿到缓存里。
                List<MemberVo> snapshot = List.copyOf(members);
                rememberVisibleState(snapshot);
                cacheService.put(CACHE_KEY_APPROVED_FLAT, snapshot, CACHE_TTL_MEMBERS);
                return toPage(snapshot, normalizedPage, normalizedSize);
            });
    }

    /** 从全量有序列表切出请求的那一页。 */
    private ListResult<MemberVo> toPage(List<MemberVo> all, int page, int size) {
        return new ListResult<>(page, size, all.size(), paginate(all, page, size));
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
        
        return client.listAll(Member.class, new run.halo.app.extension.ListOptions(),
                run.halo.app.extension.ExtensionUtil.defaultSort())
            .filter(member -> "APPROVED".equals(member.getSpec().getStatus())
                && groupName.equals(member.getSpec().getGroupName()))
            .map(MemberVo::from)
            .collectList()
            .doOnSuccess(members -> {
                members.sort(this::compareMemberOrder);
                // 缓存结果
                cacheService.put(cacheKey, members, CACHE_TTL_MEMBERS);
                log.debug("分组成员查询完成: {}, 成员数: {}", groupName, members.size());
            })
            .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Flux<MemberGroupVo> listAllGroups() {
        String cacheKey = CACHE_KEY_GROUPS_ALL;
        
        // 尝试从缓存获取
        List<MemberGroupVo> cached = cachedList(cacheKey);
        if (cached != null) {
            log.debug("从缓存获取所有分组");
            return Flux.fromIterable(cached);
        }
        
        return client.listAll(MemberGroup.class, new run.halo.app.extension.ListOptions(),
                run.halo.app.extension.ExtensionUtil.defaultSort())
            .filter(group -> group.getSpec() != null)
            .sort(Comparator
                .comparing((MemberGroup group) -> group.getSpec().getDisplayName(),
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(group -> group.getMetadata().getName()))
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
        return client.listAll(Member.class, new run.halo.app.extension.ListOptions(),
                run.halo.app.extension.ExtensionUtil.defaultSort())
            .filter(member -> member.getSpec() != null
                && "APPROVED".equals(member.getSpec().getStatus()))
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
     * 清除全部成员与分组缓存。
     *
     * <p>只在「确实写入了成员数据」之后调用。曾经每次 reconcile 都无条件调用，
     * 而 reconcile 自己又会 update 成员再触发一轮，等于自己把自己的缓存清掉，命中率≈0。
     */
    public void evictCache() {
        cacheService.evictByPattern(CACHE_PREFIX_GROUPS + "*");
        cacheService.evictByPattern(CACHE_PREFIX_MEMBERS + "*");
        cacheService.evictByPattern(CACHE_PREFIX_APPROVED + "*");
        log.debug("已清除成员相关缓存");
    }

    /**
     * 分组定义变更（新增/改名/删除分组）后的失效。
     *
     * <p>只碰分组相关视图：成员列表只依赖成员自身的 {@code groupName}，分组的显示名改了
     * 不影响它们；而按分组聚合的视图里嵌了分组的 displayName，必须重算。
     * 用精确 key 而不是 {@code evictByPattern("*")} 全扫，是因为这里能点名的就这几个。
     */
    public void evictGroupDefinitionsCache() {
        cacheService.evict(CACHE_KEY_GROUPS_ALL);
        cacheService.evict(CACHE_KEY_APPROVED_ALL);
        log.debug("已清除分组定义缓存");
    }

    /**
     * 清除特定分组的缓存
     */
    public void evictGroupCache(String groupName) {
        cacheService.evict(CACHE_PREFIX_MEMBERS + ":" + groupName);
        cacheService.evict(CACHE_KEY_APPROVED_ALL);
        cacheService.evict(CACHE_KEY_APPROVED_FLAT);
        log.debug("已清除分组缓存: {}", groupName);
    }

    /**
     * 库里的「可见状态」与缓存快照不一致时清缓存。
     *
     * <p>成员写库的入口不止一处：后台审核、批量操作、MCP 工具、认领接口，甚至管理员直接在
     * Halo 后台改扩展资源——后者完全不经过插件的 service，没有任何人负责失效缓存。
     * 所以 reconcile 需要兜底，但也不能像原来那样「每次 reconcile 都全清」，那会让缓存
     * 命中率归零（reconcile 自己 update 一次又会再触发一轮）。
     *
     * <p>折中：只比对决定「是否出现在列表里 / 出现在哪个分组」的两个字段。其余字段
     * （姓名、链接等）改了最多让缓存显示旧值到 TTL 到期，不值得为它每次 reconcile 都查一次。
     *
     * @return true 表示这次确实清了缓存
     */
    public boolean evictIfVisibleStateChanged(Member member) {
        if (member.getSpec() == null || member.getMetadata() == null) {
            return false;
        }
        if (cacheService.get(CACHE_KEY_APPROVED_ALL, Object.class) == null
            && cacheService.get(CACHE_KEY_APPROVED_FLAT, Object.class) == null) {
            // 压根没有缓存，没什么可清的
            return false;
        }
        String name = member.getMetadata().getName();
        String current = visibleFingerprint(member.getSpec());
        String cached = visibleFingerprints.get(name);
        if (current.equals(cached)) {
            return false;
        }
        // 只有「现在可见」或「曾经可见」的变化才影响列表；本来就不在列表里的成员不用管
        boolean nowVisible = "APPROVED".equals(member.getSpec().getStatus());
        if (!nowVisible && cached == null) {
            return false;
        }
        visibleFingerprints.put(name, current);
        evictCache();
        log.debug("成员 {} 的可见状态已变化，清除列表缓存", name);
        return true;
    }

    private void rememberVisibleState(Collection<MemberGroupVo> groups) {
        Map<String, String> snapshot = new HashMap<>();
        for (MemberGroupVo group : groups) {
            if (group.getMembers() == null) {
                continue;
            }
            for (MemberVo member : group.getMembers()) {
                snapshot.put(member.getMetadata().getName(), visibleFingerprint(member.getSpec()));
            }
        }
        visibleFingerprints.clear();
        visibleFingerprints.putAll(snapshot);
    }

    private void rememberVisibleState(List<MemberVo> members) {
        Map<String, String> snapshot = new HashMap<>();
        for (MemberVo member : members) {
            snapshot.put(member.getMetadata().getName(), visibleFingerprint(member.getSpec()));
        }
        visibleFingerprints.clear();
        visibleFingerprints.putAll(snapshot);
    }

    /** 决定「是否出现在前台列表 / 出现在哪个分组」的字段。 */
    private static String visibleFingerprint(Member.MemberSpec spec) {
        if (spec == null) {
            return "";
        }
        return spec.getStatus() + "|" + spec.getGroupName();
    }

    /**
     * 成员排序：加入时间 → 名称 → 资源名。
     *
     * <p>加入时间在 {@link MemberVo#from} 里已解析好，这里只读字段。比较器路径上不允许再出现
     * 解析、日志或任何可能抛异常的操作——它会被调用 O(n log n) 次。
     */
    private int compareMemberOrder(MemberVo m1, MemberVo m2) {
        var first = m1.getJoinedAtTs();
        var second = m2.getJoinedAtTs();
        if (first == null && second != null) return 1;
        if (first != null && second == null) return -1;
        if (first != null && second != null) {
            // 最早加入的成员排在前面，后申请的依次往后
            int byTime = first.compareTo(second);
            if (byTime != 0) return byTime;
        }
        String firstName = m1.getSpec() == null || m1.getSpec().getDisplayName() == null
            ? "" : m1.getSpec().getDisplayName();
        String secondName = m2.getSpec() == null || m2.getSpec().getDisplayName() == null
            ? "" : m2.getSpec().getDisplayName();
        int byName = String.CASE_INSENSITIVE_ORDER.compare(firstName, secondName);
        if (byName != 0) return byName;
        return String.CASE_INSENSITIVE_ORDER.compare(
            m1.getMetadata().getName(), m2.getMetadata().getName());
    }

    private MemberGroupVo createVirtualGroup(String name, String displayName, List<MemberVo> members) {
        Metadata metadata = new Metadata();
        metadata.setName(name);

        MemberGroup.MemberGroupSpec spec = new MemberGroup.MemberGroupSpec();
        spec.setDisplayName(displayName);
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

    /**
     * 分页切片。
     *
     * <p>返回的是拷贝而不是 {@code subList} 视图：视图会一直挂着缓存里那个列表，
     * 调用方只要就地 {@code set} 一笔，缓存内容就被悄悄改掉了。
     */
    private List<MemberVo> paginate(List<MemberVo> members, int page, int size) {
        int fromIndex = Math.min((page - 1) * size, members.size());
        int toIndex = Math.min(fromIndex + size, members.size());
        return List.copyOf(members.subList(fromIndex, toIndex));
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
