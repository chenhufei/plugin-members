package run.halo.members.utils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.commons.lang3.StringUtils;

/**
 * 认领流程里的头像处理。
 * <p>
 * Halo 2.27 没有面向插件的匿名附件上传接口，自助认领只能把客户端压缩后的图片以
 * data URL 写进 {@code Member.spec.avatar}；这里对入参做白名单与真实解码校验，
 * 避免把任意字符串或 SVG 写进成员资料。
 */
public final class AvatarUtils {

    public static final int MAX_BYTES = 5 * 1024 * 1024; // 5 MB
    public static final int MAX_EDGE = 512;
    private static final String QLOGO_TEMPLATE = "https://q1.qlogo.cn/g?b=qq&nk=%s&s=640";
    private static final Pattern DATA_URL =
        Pattern.compile("^data:image/(png|jpeg|webp|bmp|gif);base64,([A-Za-z0-9+/=]+)$");

    private AvatarUtils() {
    }

    /** 与成员自助申请保持同一份默认头像，未上传头像时补上 QQ 头像。 */
    public static String defaultQqAvatar(String qq) {
        String value = StringUtils.trimToEmpty(qq);
        if (value.isEmpty()) {
            return null;
        }
        return QLOGO_TEMPLATE.formatted(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    /**
     * 校验 data URL 头像。
     *
     * @return 错误信息；合法时返回 {@code null}
     */
    public static String validateDataUrl(String avatar) {
        if (avatar.length() > MAX_BYTES * 2) {
            return "头像文件过大，请重新上传";
        }
        Matcher matcher = DATA_URL.matcher(avatar);
        if (!matcher.matches()) {
            return "头像格式不正确，请上传 JPG、PNG、WebP、BMP 或 GIF 图片";
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(matcher.group(2));
        } catch (IllegalArgumentException error) {
            return "头像数据损坏，请重新上传";
        }
        if (bytes.length > MAX_BYTES) {
            return "头像文件过大，请重新上传";
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return "头像图片无法识别，请重新上传";
            }
            if (image.getWidth() > MAX_EDGE || image.getHeight() > MAX_EDGE) {
                return "头像尺寸不能超过 " + MAX_EDGE + "×" + MAX_EDGE + "，请重新上传";
            }
        } catch (IOException error) {
            return "头像图片无法识别，请重新上传";
        }
        return null;
    }
}
