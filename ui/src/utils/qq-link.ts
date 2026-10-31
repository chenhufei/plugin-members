import jsQR from "jsqr";

/**
 * QQ 加好友链接与二维码解析（与主题前端 modules/qq-link.ts 同一套规则）。
 *
 * QQ 客户端导出的加好友二维码有两种官方形态：
 *   - https://qm.qq.com/q/<token>
 *   - https://qm.qq.com/cgi-bin/qm/qr?k=<token>&jump_from=webapi
 * 只按 /q/ 前缀判定会让后者被判成"不是 QQ 链接"，识别率因此偏低。
 */

const QQ_HOST = "qm.qq.com";
const SHORT_PATH_PREFIX = "/q/";
const QR_PATH = "/cgi-bin/qm/qr";
const MAX_EDGE = 1000;
const SCALES = [1, 0.62, 0.4];

/**
 * 旧版二维码链接的路径恰好就是 /cgi-bin/qm/qr，token 放在 k 查询参数里，
 * 不能再要求"路径比前缀长"——那会把 QQ 客户端导出的二维码链接全部判成非法。
 */
function isOfficialPath(path: string, query: string): boolean {
  if (path.startsWith(SHORT_PATH_PREFIX) && path.length > SHORT_PATH_PREFIX.length) {
    return true;
  }
  if (path !== QR_PATH && !path.startsWith(`${QR_PATH}/`)) return false;
  return query
    .split("&")
    .some((parameter) => parameter.startsWith("k=") && parameter.length > 2);
}

/** 归一化：合法返回规范 https 地址，非法返回空串 */
export function normalizeQqLink(raw?: string): string {
  const value = String(raw ?? "").trim().split(/\s+/)[0] || "";
  if (!value) return "";
  let url: URL;
  try {
    url = new URL(/^[a-z][a-z0-9+.-]*:/i.test(value) ? value : `https://${value}`);
  } catch {
    return "";
  }
  if (url.hostname.toLowerCase() !== QQ_HOST) return "";
  if (!isOfficialPath(url.pathname || "", url.search.replace(/^\?/, ""))) return "";
  url.protocol = "https:";
  return url.toString();
}

/** QQ 号 → QQ 头像地址 */
export function qqAvatar(qq?: string): string {
  const value = String(qq ?? "").trim();
  if (!value) return "";
  return `https://q1.qlogo.cn/g?b=qq&nk=${encodeURIComponent(value)}&s=640`;
}

function binarize(imageData: ImageData): Uint8ClampedArray {
  const { data } = imageData;
  const copy = new Uint8ClampedArray(data.length);
  for (let i = 0; i < data.length; i += 4) {
    const luma = data[i] * 0.299 + data[i + 1] * 0.587 + data[i + 2] * 0.114;
    const value = luma > 138 ? 255 : 0;
    copy[i] = value;
    copy[i + 1] = value;
    copy[i + 2] = value;
    copy[i + 3] = 255;
  }
  return copy;
}

function drawToCanvas(img: HTMLImageElement, width: number, height: number) {
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) return null;
  // 白底：png 透明区域的 RGB 是 0，直接读会把二维码当成反向图
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, width, height);
  ctx.drawImage(img, 0, 0, width, height);
  return ctx.getImageData(0, 0, width, height);
}

/**
 * 图片 → 二维码文本；识别不到返回空串。
 * 依次尝试原图 / 62% / 40% 三档，每档都跑一次原图与一次二值化，并允许反色。
 */
export function decodeQrFromImage(img: HTMLImageElement): string {
  const edge = Math.max(img.naturalWidth || img.width, img.naturalHeight || img.height);
  const base = edge > MAX_EDGE ? MAX_EDGE / edge : 1;
  for (const scale of SCALES) {
    const ratio = base * scale;
    const width = Math.max(32, Math.round((img.naturalWidth || img.width) * ratio));
    const height = Math.max(32, Math.round((img.naturalHeight || img.height) * ratio));
    const imageData = drawToCanvas(img, width, height);
    if (!imageData) continue;

    const code = jsQR(imageData.data, width, height, { inversionAttempts: "attemptBoth" });
    if (code?.data) return code.data;

    const binary = binarize(imageData);
    const inverted = jsQR(binary, width, height, { inversionAttempts: "attemptBoth" });
    if (inverted?.data) return inverted.data;
  }
  return "";
}
