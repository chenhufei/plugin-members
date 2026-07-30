package run.halo.members;

import java.util.Properties;

import com.google.common.base.Throwables;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.PropertyPlaceholderHelper;
import org.springframework.util.RouteMatcher;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PathPatternRouteMatcher;
import org.springframework.web.util.pattern.PatternParseException;
import org.thymeleaf.context.Contexts;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.web.IWebRequest;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.PluginContext;
import run.halo.app.theme.dialect.TemplateFooterProcessor;

@Component
@RequiredArgsConstructor
@Slf4j
public class MemberApplyWidgetFooterProcessor implements TemplateFooterProcessor {

    static final PropertyPlaceholderHelper PROPERTY_PLACEHOLDER_HELPER = new PropertyPlaceholderHelper("${", "}");

    private final PluginContext pluginContext;

    private final RouteMatcher routeMatcher = createRouteMatcher();

    @Override
    public Mono<Void> process(ITemplateContext context, IProcessableElementTag tag,
        IElementTagStructureHandler structureHandler, IModel model) {
        return Mono.just(true)
            .doOnNext(ignored -> {
                // 脚本与样式在全站注入，供任意页面调用 MemberApplyWidget；
                // 悬浮申请按钮仅在 /members 页面注入，避免全站出现浮动按钮。
                boolean onMembersPage = isRouteMatched(context, "/members");
                IModelFactory modelFactory = context.getModelFactory();
                String html = memberApplyWidgetScript(onMembersPage);
                model.add(modelFactory.createText(html));
            }).onErrorResume(e -> {
                log.error("MemberApplyWidgetFooterProcessor process failed", e);
                return Mono.empty();
            }).then();
    }

    private String memberApplyWidgetScript(boolean withFloatingButton) {
        String html = """
            <!-- MemberApplyWidget start -->
            <script src="/plugins/${pluginName}/assets/static/member-apply-widget.iife.js?version=${version}" defer></script>
            <link rel="stylesheet" href="/plugins/${pluginName}/assets/static/member-apply-widget.css?version=${version}" />
            <!-- MemberApplyWidget end -->
            """;

        if (withFloatingButton) {
            html += """
                <button title="申请加入"
                    onclick="MemberApplyWidget.open()"
                    style="position: fixed; right: 2rem; bottom: 6rem; width: 3rem; height: 3rem; border-radius: 50%; background-color: rgba(209, 62, 67, 0.9); border: none; display: flex; align-items: center; justify-content: center; cursor: pointer; transition: background-color 0.3s; z-index: 999; box-shadow: 0 2px 8px rgba(0,0,0,0.2);"
                    onmouseover="this.style.backgroundColor='rgba(209, 62, 67, 1)'"
                    onmouseout="this.style.backgroundColor='rgba(209, 62, 67, 0.9)'"><svg
                        viewBox="0 0 24 24" width="1.5em" height="1.5em">
                        <path fill="#fff"
                            d="M15 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm-9-1V8H4v3H1v2h3v3h2v-3h3v-2H6zm9 3c-2.67 0-8 1.34-8 4v3h16v-3c0-2.66-5.33-4-8-4z">
                        </path>
                    </svg>
                </button>
                """;
        }

        final Properties properties = new Properties();
        properties.setProperty("version", pluginContext.getVersion());
        properties.setProperty("pluginName", pluginContext.getName());
        return PROPERTY_PLACEHOLDER_HELPER.replacePlaceholders(html, properties);
    }

    public boolean isRouteMatched(ITemplateContext context, String rule) {
        if (!Contexts.isWebContext(context)) {
            return false;
        }
        IWebRequest request = Contexts.asWebContext(context).getExchange().getRequest();
        String requestPath = request.getRequestPath();
        RouteMatcher.Route requestRoute = routeMatcher.parseRoute(requestPath);

        return isMatchedRoute(requestRoute, rule);
    }

    private boolean isMatchedRoute(RouteMatcher.Route requestRoute, String rule) {
        try {
            return routeMatcher.match(rule, requestRoute);
        } catch (PatternParseException e) {
            log.warn("Parse route pattern [{}] failed", rule, Throwables.getRootCause(e));
        }
        return false;
    }

    RouteMatcher createRouteMatcher() {
        var parser = new PathPatternParser();
        parser.setPathOptions(PathContainer.Options.HTTP_PATH);
        return new PathPatternRouteMatcher(parser);
    }

}
