package run.halo.members.vo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import run.halo.app.extension.Metadata;
import run.halo.members.Member;

/**
 * 加入时间解析与排序的回归测试。
 *
 * <p>对应 F5：解析从「每次比较都做」改成「转换时做一次」。
 */
class MemberVoTest {

    @Test
    @DisplayName("ISO 时间串解析为 joinedAtTs")
    void parsesIsoInstant() {
        var member = member("m1", "2024-03-01T10:00:00Z");
        assertThat(MemberVo.from(member).getJoinedAtTs())
            .isEqualTo(Instant.parse("2024-03-01T10:00:00Z"));
    }

    @Test
    @DisplayName("无时区的本地时间按系统时区解析")
    void parsesLocalDateTime() {
        var member = member("m2", "2024-03-01T10:00:00");
        assertThat(MemberVo.from(member).getJoinedAtTs()).isNotNull();
    }

    @Test
    @DisplayName("无法解析时回退创建时间，不再在比较器里抛异常")
    void fallsBackToCreationTimestamp() {
        var creation = Instant.parse("2023-01-01T00:00:00Z");
        var member = member("m3", "2024年3月");
        member.getMetadata().setCreationTimestamp(creation);

        assertThat(MemberVo.from(member).getJoinedAtTs()).isEqualTo(creation);
    }

    @Test
    @DisplayName("joinedAt 缺失时用创建时间")
    void missingJoinedAtUsesCreationTimestamp() {
        var creation = Instant.parse("2023-01-01T00:00:00Z");
        var member = member("m4", null);
        member.getMetadata().setCreationTimestamp(creation);

        assertThat(MemberVo.from(member).getJoinedAtTs()).isEqualTo(creation);
    }

    @Test
    @DisplayName("1000 名成员转换 + 排序：顺序正确，且不再随比较次数重复解析")
    void sortsThousandMembersQuickly() {
        var random = new Random(42);
        var members = new ArrayList<Member>(1000);
        var expected = new ArrayList<Instant>(1000);
        for (int i = 0; i < 1000; i++) {
            var joinedAt = Instant.parse("2024-01-01T00:00:00Z").plusSeconds(random.nextInt(10_000_000));
            expected.add(joinedAt);
            // 故意打乱顺序，避免「本来就排好」的假象
            members.add(0, member("m" + i, joinedAt.toString()));
        }

        var vos = new ArrayList<>(members.stream().map(MemberVo::from).toList());
        var started = System.nanoTime();
        vos.sort((a, b) -> a.getJoinedAtTs().compareTo(b.getJoinedAtTs()));
        var sortElapsed = Duration.ofNanos(System.nanoTime() - started);

        Collections.sort(expected);
        assertThat(vos).hasSize(1000);
        assertThat(vos.stream().map(MemberVo::getJoinedAtTs).toList()).isEqualTo(expected);
        // 验收标准：1000 名成员排序 < 30ms。旧实现每次比较都解析日期（实测 255ms），
        // 这里比较器只读字段，正常在 1–2ms 量级，30ms 上限留了十几倍余量，不会因机器慢而误报
        assertThat(sortElapsed).isLessThan(Duration.ofMillis(30));
    }

    private static Member member(String name, String joinedAt) {
        var metadata = new Metadata();
        metadata.setName(name);
        var spec = new Member.MemberSpec();
        spec.setDisplayName(name);
        spec.setJoinedAt(joinedAt);
        var member = new Member();
        member.setMetadata(metadata);
        member.setSpec(spec);
        return member;
    }
}
