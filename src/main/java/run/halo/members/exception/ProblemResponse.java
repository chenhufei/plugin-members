package run.halo.members.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * RFC 7807 错误响应体，插件内所有 HTTP 错误（公开接口、管理接口、全局兜底）共用的唯一形态。
 *
 * <p>字段语义固定：{@code type} 是错误类型标识（处理器可读），{@code title} 是给人看的概览，
 * {@code status} 与 HTTP 状态码一致，{@code detail} 是本次失败的具体原因。
 * 扩展属性（{@code code} / {@code errors} / {@code traceId} / {@code timestamp}）通过
 * {@link #withExtension(String, Object)} 平铺到顶层，不嵌套子对象——前端读 {@code data.detail}
 * 就能拿到可展示文案，读 {@code data.code} 就能做分支处理。
 *
 * @author Sky
 * @since 2.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"type", "title", "status", "detail"})
public final class ProblemResponse {

    private final String type;
    private final String title;
    private final int status;
    private final String detail;
    private Map<String, Object> extensions;

    public ProblemResponse(String type, String title, int status, String detail) {
        this.type = type;
        this.title = title;
        this.status = status;
        this.detail = detail;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public int getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    /**
     * 平铺到 JSON 顶层的扩展字段；空映射不会输出任何键。
     */
    @JsonAnyGetter
    public Map<String, Object> getExtensions() {
        return extensions == null ? Collections.emptyMap() : extensions;
    }

    /**
     * 追加一个扩展字段，返回自身以便链式调用。
     */
    public ProblemResponse withExtension(String name, Object value) {
        if (value != null) {
            if (extensions == null) {
                extensions = new LinkedHashMap<>();
            }
            extensions.put(name, value);
        }
        return this;
    }
}
