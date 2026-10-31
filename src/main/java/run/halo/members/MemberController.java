package run.halo.members;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ServerWebExchange;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.members.finders.MemberFinder;
import run.halo.members.vo.MemberGroupVo;

/**
 * 成员前台控制器
 * @since 1.0.0
 */
@Slf4j
@Controller
@RequestMapping("/members")
@RequiredArgsConstructor
public class MemberController {

    static final String TEMPLATE_NAME = "members";

    private final MemberFinder memberFinder;
    private final TemplateNameResolver templateNameResolver;

    @GetMapping
    public Mono<String> members(@RequestParam(required = false) String group, Model model,
        ServerWebExchange exchange) {
        return memberFinder.listApprovedMembers(null, null)
            .doOnNext(result -> populateModel(model, result.getItems(), group))
            .onErrorResume(error -> {
                log.error("Error loading members", error);
                populateModel(model, List.of(), group);
                return Mono.empty();
            })
            .then(templateNameResolver.resolveTemplateNameOrDefault(exchange, TEMPLATE_NAME));
    }

    private void populateModel(Model model, List<MemberGroupVo> groups, String group) {
        List<MemberGroupVo> filteredGroups = group == null || group.isBlank()
            ? groups
            : groups.stream()
                .filter(g -> group.equals(g.getMetadata().getName()))
                .collect(Collectors.toList());
        model.addAttribute("groups", filteredGroups);
        model.addAttribute("allGroups", groups);
        model.addAttribute("currentGroup", group);
        model.addAttribute("pluginName", "PluginMembers");
        log.debug("Returning {} member groups for member page (group filter: {})",
            filteredGroups.size(), group);
    }
}
