package run.halo.members;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

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
    public Mono<String> members(Model model) {
        return memberFinder.listApprovedMembers(null, null)
            .doOnNext(result -> {
                model.addAttribute("groups", result.getItems());
                model.addAttribute("pluginName", "PluginMembers");
                log.debug("Returning {} member groups for theme page", result.getItems().size());
            })
            .doOnError(error -> {
                log.error("Error loading members", error);
            })
            .thenReturn("members");
    }
}
