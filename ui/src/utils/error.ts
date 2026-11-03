/**
 * 把后端错误翻成一句能直接给用户看的中文。
 *
 * <p>Halo 的错误响应是 RFC 7807：{@code detail} 是最具体的说明，{@code title} 是概览，
 * 老接口可能还留着一个 {@code message}。这三者都没有时**不要**退回
 * {@code error.message}——axios 在那里放的是 "Request failed with status code 500"
 * 这类英文，控制台里的站长看到只会更困惑，还不如用调用方给的兜底文案。
 */
export function errorMessage(error: unknown, fallback: string): string {
  const data = (error as { response?: { data?: unknown } })?.response?.data;
  if (data && typeof data === "object") {
    const problem = data as Record<string, unknown>;
    for (const key of ["detail", "message", "title"]) {
      const value = problem[key];
      if (typeof value === "string" && value.trim()) {
        return value.trim();
      }
    }
  }
  if (typeof data === "string" && data.trim() && !data.trim().startsWith("<")) {
    // 少数端点直接回纯文本，但不要把整页 HTML 错误页贴给用户
    return data.trim().slice(0, 200);
  }
  return fallback;
}
