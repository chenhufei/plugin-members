package run.halo.members;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import run.halo.members.finders.MemberFinder;

/**
 * 成员前台控制器
 * @since 1.0.0
 */
@Slf4j
@Controller
@RequestMapping("/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberFinder memberFinder;

    @GetMapping
    public Mono<String> members(@RequestParam(required = false) String group, Model model) {
        return memberFinder.listApprovedMembers(null, null)
            .doOnNext(result -> {
                List<run.halo.members.vo.MemberGroupVo> filteredGroups = result.getItems();

                if (group != null && !group.isBlank()) {
                    filteredGroups = filteredGroups.stream()
                        .filter(g -> group.equals(g.getMetadata().getName()))
                        .collect(java.util.stream.Collectors.toList());
                }

                model.addAttribute("groups", filteredGroups);
                model.addAttribute("currentGroup", group);
                model.addAttribute("pluginName", "PluginMembers");
                log.debug("Returning {} member groups for theme page (group filter: {})",
                    filteredGroups.size(), group);
            })
            .onErrorResume(error -> {
                log.error("Error loading members", error);
                model.addAttribute("groups", List.of());
                model.addAttribute("currentGroup", group);
                model.addAttribute("pluginName", "PluginMembers");
                return Mono.empty();
            })
            .thenReturn("members");
    }
}
