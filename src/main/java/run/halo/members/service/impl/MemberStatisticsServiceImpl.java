package run.halo.members.service.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.members.Member;
import run.halo.members.MemberGroup;
import run.halo.members.service.MemberStatisticsService;

/**
 * 成员统计分析服务实现
 * 
 * @author Sky
 * @since 2.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberStatisticsServiceImpl implements MemberStatisticsService {
    
    private final ReactiveExtensionClient client;
    
    @Override
    public Mono<OverallStatistics> getOverallStatistics() {
        log.debug("获取总体统计信息");
        
        return Mono.zip(
            // 获取所有成员
            client.listAll(Member.class, null, null).collectList(),
            // 获取所有分组
            client.listAll(MemberGroup.class, null, null).count()
        ).map(tuple -> {
            var members = tuple.getT1();
            int totalGroups = tuple.getT2().intValue();
            
            int total = members.size();
            int approved = (int) members.stream()
                .filter(m -> "APPROVED".equals(m.getSpec().getStatus()))
                .count();
            int pending = (int) members.stream()
                .filter(m -> "PENDING".equals(m.getSpec().getStatus()))
                .count();
            int rejected = (int) members.stream()
                .filter(m -> "REJECTED".equals(m.getSpec().getStatus()))
                .count();
            
            double approvalRate = total > 0 ? (double) approved / total * 100 : 0;
            
            // 最受欢迎的分组
            String mostPopularGroup = members.stream()
                .collect(Collectors.groupingBy(
                    m -> m.getSpec().getGroupName() != null ? m.getSpec().getGroupName() : "未分组",
                    Collectors.counting()
                ))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("无");
            
            // 最受欢迎的学校
            String mostPopularSchool = members.stream()
                .collect(Collectors.groupingBy(
                    m -> m.getSpec().getSchool() != null ? m.getSpec().getSchool() : "未知",
                    Collectors.counting()
                ))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("无");
            
            return new OverallStatistics(
                total,
                approved,
                pending,
                rejected,
                totalGroups,
                Math.round(approvalRate * 100.0) / 100.0,
                mostPopularGroup,
                mostPopularSchool
            );
        });
    }
    
    @Override
    public Mono<Map<String, Integer>> getGroupStatistics() {
        log.debug("获取分组统计");
        
        return client.listAll(Member.class, null, null)
            .collect(Collectors.groupingBy(
                m -> m.getSpec().getGroupName() != null ? m.getSpec().getGroupName() : "未分组",
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
            ))
            .map(map -> map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (e1, e2) -> e1,
                    LinkedHashMap::new
                ))
            );
    }
    
    @Override
    public Mono<Map<String, Integer>> getStatusStatistics() {
        log.debug("获取状态统计");
        
        return client.listAll(Member.class, null, null)
            .collect(Collectors.groupingBy(
                m -> m.getSpec().getStatus() != null ? m.getSpec().getStatus() : "UNKNOWN",
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
            ));
    }
    
    @Override
    public Mono<Map<String, Integer>> getSchoolStatistics() {
        log.debug("获取学校统计（Top 10）");
        
        return client.listAll(Member.class, null, null)
            .collect(Collectors.groupingBy(
                m -> m.getSpec().getSchool() != null ? m.getSpec().getSchool() : "未知",
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
            ))
            .map(map -> map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (e1, e2) -> e1,
                    LinkedHashMap::new
                ))
            );
    }
    
    @Override
    public Mono<Map<String, Integer>> getApplicationTrend(int days) {
        log.debug("获取最近{}天的申请趋势", days);
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate startDate = LocalDate.now().minusDays(days - 1);
        Instant cutoffTime = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
        
        return client.listAll(Member.class, null, null)
            .filter(member -> {
                Instant creationTime = member.getMetadata().getCreationTimestamp();
                return creationTime != null && !creationTime.isBefore(cutoffTime);
            })
            .collect(Collectors.groupingBy(
                member -> {
                    Instant creationTime = member.getMetadata().getCreationTimestamp();
                    LocalDate date = creationTime.atZone(ZoneId.systemDefault()).toLocalDate();
                    return date.format(formatter);
                },
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
            ))
            .map(map -> {
                Map<String, Integer> result = new LinkedHashMap<>();
                for (int i = 0; i < days; i++) {
                    String dateStr = startDate.plusDays(i).format(formatter);
                    result.put(dateStr, map.getOrDefault(dateStr, 0));
                }
                return result;
            });
    }
}
