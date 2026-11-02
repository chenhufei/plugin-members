package run.halo.members;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.members.finders.impl.MemberFinderImpl;

/**
 * Keeps public member query cache in sync with group changes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberGroupReconciler implements Reconciler<Reconciler.Request> {

    private final MemberFinderImpl memberFinder;

    @Override
    public Result reconcile(Request request) {
        log.debug("Reconciling member group cache: {}", request.name());
        memberFinder.evictCache();
        return Result.doNotRetry();
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new MemberGroup())
            .build();
    }
}
