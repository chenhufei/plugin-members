package run.halo.members;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.members.finders.MemberFinder;
import run.halo.members.vo.MemberGroupVo;

/**
 * 成员前台页面的模板解析守卫测试
 */
class MemberControllerTest {

    private static MemberGroupVo group(String name, String displayName) {
        Metadata metadata = new Metadata();
        metadata.setName(name);
        MemberGroup.MemberGroupSpec spec = new MemberGroup.MemberGroupSpec();
        spec.setDisplayName(displayName);
        return MemberGroupVo.builder()
            .metadata(metadata)
            .spec(spec)
            .members(List.of())
            .build();
    }

    private final MemberFinder memberFinder = mock(MemberFinder.class);
    private final TemplateNameResolver templateNameResolver = mock(TemplateNameResolver.class);
    private final MemberController controller =
        new MemberController(memberFinder, templateNameResolver);
    private final ServerWebExchange exchange = mock(ServerWebExchange.class);

    @Test
    @DisplayName("主题自带 members 模板时使用主题模板")
    void usesThemeTemplateWhenAvailable() {
        when(memberFinder.listApprovedMembers(null, null))
            .thenReturn(Mono.just(new ListResult<>(1, 0, 1,
                List.of(group("union", "联盟")))));
        when(templateNameResolver.resolveTemplateNameOrDefault(exchange, "members"))
            .thenReturn(Mono.just("members"));

        Model model = new ExtendedModelMap();
        String viewName = controller.members(null, model, exchange).block();

        assertEquals("members", viewName);
        verify(templateNameResolver).resolveTemplateNameOrDefault(exchange, "members");
        assertEquals(1, ((List<?>) model.getAttribute("groups")).size());
        assertEquals("PluginMembers", model.getAttribute("pluginName"));
    }

    @Test
    @DisplayName("主题缺少 members 模板时回退到插件默认页面")
    void fallsBackToPluginTemplate() {
        when(memberFinder.listApprovedMembers(null, null))
            .thenReturn(Mono.just(new ListResult<>(1, 0, 0, List.of())));
        when(templateNameResolver.resolveTemplateNameOrDefault(exchange, "members"))
            .thenReturn(Mono.just("plugin:PluginMembers:members"));

        Model model = new ExtendedModelMap();
        String viewName = controller.members(null, model, exchange).block();

        assertEquals("plugin:PluginMembers:members", viewName);
        assertNotNull(model.getAttribute("groups"));
    }

    @Test
    @DisplayName("分组筛选只保留选中分组，同时提供全部分组用于导航")
    void filtersGroupButKeepsNavigation() {
        var union = group("union", "联盟");
        var media = group("media", "媒体");
        when(memberFinder.listApprovedMembers(null, null))
            .thenReturn(Mono.just(new ListResult<>(1, 0, 2, List.of(union, media))));
        when(templateNameResolver.resolveTemplateNameOrDefault(exchange, "members"))
            .thenReturn(Mono.just("members"));

        Model model = new ExtendedModelMap();
        controller.members("media", model, exchange).block();

        assertEquals(List.of(media), model.getAttribute("groups"));
        assertEquals(2, ((List<?>) model.getAttribute("allGroups")).size());
        assertEquals("media", model.getAttribute("currentGroup"));
    }

    @Test
    @DisplayName("查询失败时页面仍可渲染空状态")
    void rendersEmptyPageWhenQueryFails() {
        when(memberFinder.listApprovedMembers(null, null))
            .thenReturn(Mono.error(new IllegalStateException("finder down")));
        when(templateNameResolver.resolveTemplateNameOrDefault(exchange, "members"))
            .thenReturn(Mono.just("plugin:PluginMembers:members"));

        Model model = new ExtendedModelMap();
        String viewName = controller.members(null, model, exchange).block();

        assertEquals("plugin:PluginMembers:members", viewName);
        assertInstanceOf(List.class, model.getAttribute("groups"));
        assertEquals(0, ((List<?>) model.getAttribute("groups")).size());
    }
}
