package run.halo.members.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.members.Member;
import run.halo.members.MemberGroup;
import run.halo.members.finders.impl.MemberFinderImpl;

/**
 * 批量导入导出的行级结果契约。
 *
 * <p>这一组用例守的是同一条底线：**失败粒度是行**。单行数据坏掉时，其余行照常入库，
 * 失败清单里只出现真正失败的那一行，且不会把 Java 内部错误信息回传给前端。
 */
class MemberBatchServiceImplTest {

    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final MemberFinderImpl memberFinder = mock(MemberFinderImpl.class);

    private final MemberBatchServiceImpl service =
        new MemberBatchServiceImpl(client, memberFinder);

    @BeforeEach
    void stubCreate() {
        when(client.listAll(eq(Member.class), any(ListOptions.class), any()))
            .thenReturn(Flux.empty());
        when(client.listAll(eq(MemberGroup.class), any(ListOptions.class), any()))
            .thenReturn(Flux.empty());
        when(client.create(any(Member.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    private static final String CSV_HEADER =
        "账号名称,邮箱,所属学校,QQ号,分组,状态,QQ加好友链接,创建时间";

    @Test
    @DisplayName("单行创建失败时，失败清单只包含该行")
    void failureListContainsOnlyFailedRow() {
        when(client.create(any(Member.class))).thenAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            if ("乙同学".equals(member.getSpec().getDisplayName())) {
                return Mono.error(new IllegalStateException("boom"));
            }
            return Mono.just(member);
        });

        var csv = CSV_HEADER + "\n"
            + "甲同学,甲@test.local,,1001,,\n"
            + "乙同学,乙@test.local,,1002,,\n"
            + "丙同学,丙@test.local,,1003,,\n";

        var result = service.batchImportFromCSV(csv, null).block();

        assertThat(result).isNotNull();
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.success()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failedMembers()).containsExactly("乙同学");
    }

    @Test
    @DisplayName("整行字段全空时只判该行失败，不影响其它行")
    void emptyRowFailsAlone() {
        var csv = CSV_HEADER + "\n"
            + "甲同学,甲@test.local,,1001,,\n"
            + ",,,,,,,\n"
            + "丙同学,丙@test.local,,1003,,\n";

        var result = service.batchImportFromCSV(csv, null).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failedMembers()).hasSize(1);
        // 回给用户的是可读原因，不是 NPE 文案或内部类名
        assertThat(result.message())
            .contains("失败 1")
            .doesNotContain("null")
            .doesNotContain("Exception")
            .doesNotContain("run.halo.members");
    }

    @Test
    @DisplayName("表头带引号也能正确匹配列")
    void quotedHeadersAreParsed() {
        var csv = "\"账号名称\",\"邮箱\",\"所属学校\",\"QQ号\"\n"
            + "\"甲同学\",\"甲@test.local\",\"某大学\",\"1001\"\n";

        var result = service.batchImportFromCSV(csv, null).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failed()).isZero();

        var created = captureCreated();
        assertThat(created).hasSize(1);
        assertThat(created.get(0).getSpec().getDisplayName()).isEqualTo("甲同学");
        assertThat(created.get(0).getSpec().getSchool()).isEqualTo("某大学");
    }

    @Test
    @DisplayName("列数不足的行按空值补齐，不再静默丢弃")
    void shortRowIsImportedInsteadOfDropped() {
        var csv = CSV_HEADER + "\n"
            + "只有名字的一行\n";

        var result = service.batchImportFromCSV(csv, null).block();

        assertThat(result).isNotNull();
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.success()).isEqualTo(1);

        var created = captureCreated();
        assertThat(created).hasSize(1);
        assertThat(created.get(0).getSpec().getDisplayName()).isEqualTo("只有名字的一行");
    }

    @Test
    @DisplayName("QQ 号重复的行计入跳过，不再重复建成员")
    void duplicateQqIsSkipped() {
        Member existing = new Member();
        Metadata metadata = new Metadata();
        metadata.setName("existing-1001");
        existing.setMetadata(metadata);
        Member.MemberSpec spec = new Member.MemberSpec();
        spec.setDisplayName("老成员");
        spec.setQq("1001");
        existing.setSpec(spec);
        when(client.listAll(eq(Member.class), any(ListOptions.class), any()))
            .thenReturn(Flux.just(existing));

        var csv = CSV_HEADER + "\n"
            + "重复的同学,重复@test.local,,1001,,\n"
            + "新同学,新@test.local,,2002,,\n";

        var result = service.batchImportFromCSV(csv, null).block();

        assertThat(result).isNotNull();
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.success()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.message()).contains("跳过重复 1");

        var created = captureCreated();
        assertThat(created).hasSize(1);
        assertThat(created.get(0).getSpec().getQq()).isEqualTo("2002");
    }

    @Test
    @DisplayName("同一份 CSV 内的重复 QQ 也只入库一次")
    void duplicateInsideSameFileIsSkipped() {
        var csv = CSV_HEADER + "\n"
            + "甲同学,甲@test.local,,1001,,\n"
            + "甲同学再填一遍,甲2@test.local,,1001,,\n";

        var result = service.batchImportFromCSV(csv, null).block();

        assertThat(result).isNotNull();
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.success()).isEqualTo(1);
        assertThat(result.message()).contains("跳过重复 1");
        assertThat(captureCreated()).hasSize(1);
    }

    @Test
    @DisplayName("导入时保留原始首字符，公式转义只发生在导出侧")
    void importKeepsLeadingDash() {
        var csv = CSV_HEADER + "\n"
            + "-张三,zhang@test.local,,,\n";

        var result = service.batchImportFromCSV(csv, null).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isEqualTo(1);

        var created = captureCreated();
        assertThat(created.get(0).getSpec().getDisplayName()).isEqualTo("-张三");
    }

    @Test
    @DisplayName("导出的 CSV 带 UTF-8 BOM")
    void exportStartsWithBom() {
        Member member = new Member();
        Metadata metadata = new Metadata();
        metadata.setName("m1");
        member.setMetadata(metadata);
        Member.MemberSpec spec = new Member.MemberSpec();
        spec.setDisplayName("甲同学");
        member.setSpec(spec);
        when(client.listAll(eq(Member.class), any(ListOptions.class), any()))
            .thenReturn(Flux.just(member));

        String csv = service.exportToCSV(null).block();

        assertThat(csv).isNotNull();
        assertThat(csv).startsWith("\ufeff");
        assertThat(csv).contains("账号名称,邮箱,所属学校");
        assertThat(csv).contains("甲同学");
    }

    @Test
    @DisplayName("资源名清洗掉非法字符，删除时不需要再手工编码")
    void resourceNameIsSanitized() {
        var csv = CSV_HEADER + "\n"
            + "某人,某@test.local,,非法QQ@#,,\n";

        var result = service.batchImportFromCSV(csv, null).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isEqualTo(1);

        String name = captureCreated().get(0).getMetadata().getName();
        assertThat(name).matches("[a-z0-9-]+");
        assertThat(name).doesNotContain("@").doesNotContain("#");
    }

    @Test
    @DisplayName("全部行都重复时不创建任何成员")
    void allDuplicatesCreatesNothing() {
        Member existing = new Member();
        Metadata metadata = new Metadata();
        metadata.setName("existing");
        existing.setMetadata(metadata);
        Member.MemberSpec spec = new Member.MemberSpec();
        spec.setEmail("dup@test.local");
        existing.setSpec(spec);
        when(client.listAll(eq(Member.class), any(ListOptions.class), any()))
            .thenReturn(Flux.just(existing));

        var csv = CSV_HEADER + "\n"
            + "重复,dup@test.local,,,\n";

        var result = service.batchImportFromCSV(csv, null).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isZero();
        assertThat(result.total()).isEqualTo(1);
        verify(client, never()).create(any(Member.class));
    }

    private List<Member> captureCreated() {
        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(client, org.mockito.Mockito.atLeast(1)).create(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("JSON 导入同样按行汇总")
    void jsonImportReportsPerRow() {
        when(client.create(any(Member.class))).thenAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            if ("乙".equals(member.getSpec().getDisplayName())) {
                return Mono.error(new NullPointerException("internal detail"));
            }
            return Mono.just(member);
        });

        var json = """
            [
              {"displayName": "甲", "qq": "3001"},
              {"displayName": "乙", "qq": "3002"},
              {"displayName": "丙", "qq": "3003"}
            ]
            """;

        var result = service.batchImportFromJSON(json, null).block();

        assertThat(result).isNotNull();
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.success()).isEqualTo(2);
        assertThat(result.failedMembers()).containsExactly("乙");
        assertThat(result.message()).doesNotContain("internal detail");
    }

    @Test
    @DisplayName("CSV 里写分组显示名时，入库换成真实资源名")
    void groupDisplayNameIsResolvedToResourceName() {
        MemberGroup group = new MemberGroup();
        Metadata metadata = new Metadata();
        metadata.setName("alumni");
        group.setMetadata(metadata);
        MemberGroup.MemberGroupSpec groupSpec = new MemberGroup.MemberGroupSpec();
        groupSpec.setDisplayName("校友");
        group.setSpec(groupSpec);
        when(client.listAll(eq(MemberGroup.class), any(ListOptions.class), any()))
            .thenReturn(Flux.just(group));

        // 模板里写的正是显示名，旧实现会原样入库，前台按分组聚合时归入「未分组」
        var csv = CSV_HEADER + "\n"
            + "甲同学,甲@test.local,,5001,校友,\n";
        var result = service.batchImportFromCSV(csv, null).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isEqualTo(1);
        assertThat(captureCreated().get(0).getSpec().getGroupName()).isEqualTo("alumni");
    }

    @Test
    @DisplayName("分组名既不是资源名也不是显示名时保留原值，不静默改掉")
    void unknownGroupIsKeptAsIs() {
        var csv = CSV_HEADER + "\n"
            + "甲同学,甲@test.local,,5002,查无此组,\n";
        var result = service.batchImportFromCSV(csv, null).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isEqualTo(1);
        assertThat(captureCreated().get(0).getSpec().getGroupName()).isEqualTo("查无此组");
    }

    @Test
    @DisplayName("默认分组为空串时不写入空字符串分组")
    void blankDefaultGroupIsIgnored() {
        var csv = CSV_HEADER + "\n"
            + "甲同学,甲@test.local,,5003,,\n";
        var result = service.batchImportFromCSV(csv, "  ").block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isEqualTo(1);
        assertThat(captureCreated().get(0).getSpec().getGroupName()).isNull();
    }

    @Test
    @DisplayName("并发计数不被重复消费破坏")
    void createCalledOncePerRow() {        AtomicInteger counter = new AtomicInteger();
        when(client.create(any(Member.class))).thenAnswer(invocation -> {
            counter.incrementAndGet();
            return Mono.just(invocation.getArgument(0));
        });

        var csv = CSV_HEADER + "\n"
            + "甲,甲@test.local,,4001,,\n"
            + "乙,乙@test.local,,4002,,\n"
            + "丙,丙@test.local,,4003,,\n";

        service.batchImportFromCSV(csv, null).block();

        assertThat(counter.get()).isEqualTo(3);
    }
}
