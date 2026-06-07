package run.halo.members;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 成员审核事件
 * @since 1.0.34
 */
@Getter
public class ReviewMemberEvent extends ApplicationEvent {

    private final Member member;

    public ReviewMemberEvent(Object source, Member member) {
        super(source);
        this.member = member;
    }
}
