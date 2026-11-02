package run.halo.members;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 成员申请事件
 * @since 1.0.34
 */
@Getter
public class MemberEvent extends ApplicationEvent {

    private final Member member;

    public MemberEvent(Object source, Member member) {
        super(source);
        this.member = member;
    }
}
