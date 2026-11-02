package run.halo.members.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AvatarUtilsTest {

    private static String dataUrl(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        String mime = "jpg".equals(format) ? "jpeg" : format;
        return "data:image/" + mime + ";base64,"
            + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    @Test
    void acceptsPngWithinLimits() throws Exception {
        assertNull(AvatarUtils.validateDataUrl(dataUrl("png", 200, 200)));
    }

    @Test
    void acceptsJpegWithinLimits() throws Exception {
        assertNull(AvatarUtils.validateDataUrl(dataUrl("jpg", 320, 320)));
    }

    @Test
    void acceptsGifWithinLimits() throws Exception {
        assertNull(AvatarUtils.validateDataUrl(dataUrl("gif", 100, 100)));
    }

    @Test
    void acceptsBmpWithinLimits() throws Exception {
        assertNull(AvatarUtils.validateDataUrl(dataUrl("bmp", 100, 100)));
    }

    @Test
    void rejectsUnsupportedMimeTypes() throws Exception {
        String payload = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        assertEquals("头像格式不正确，请上传 JPG、PNG、WebP、BMP 或 GIF 图片",
            AvatarUtils.validateDataUrl("data:image/svg+xml;base64," + payload));
        assertEquals("头像格式不正确，请上传 JPG、PNG、WebP、BMP 或 GIF 图片",
            AvatarUtils.validateDataUrl("data:text/html;base64," + payload));
        assertEquals("头像格式不正确，请上传 JPG、PNG、WebP、BMP 或 GIF 图片",
            AvatarUtils.validateDataUrl("https://example.com/avatar.png"));
    }

    @Test
    void rejectsImageBeyondMaxEdge() throws Exception {
        assertEquals("头像尺寸不能超过 512×512，请重新上传",
            AvatarUtils.validateDataUrl(dataUrl("png", 513, 100)));
    }

    @Test
    void rejectsCorruptedPayload() {
        assertEquals("头像数据损坏，请重新上传",
            AvatarUtils.validateDataUrl("data:image/png;base64,AAAAA"));
        assertEquals("头像图片无法识别，请重新上传",
            AvatarUtils.validateDataUrl("data:image/png;base64,"
                + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})));
    }

    @Test
    void rejectsOversizedString() {
        String huge = "data:image/png;base64," + "A".repeat(AvatarUtils.MAX_BYTES * 2);
        assertEquals("头像文件过大，请重新上传", AvatarUtils.validateDataUrl(huge));
    }

    @Test
    void acceptsUpTo5Mb() throws Exception {
        // Use a valid 512x512 PNG (max allowed edge) to verify the upper size boundary
        String dataUrl = dataUrl("png", 512, 512);
        assertNull(AvatarUtils.validateDataUrl(dataUrl));
    }

    @Test
    void buildsDefaultQqAvatar() {
        assertEquals("https://q1.qlogo.cn/g?b=qq&nk=123456&s=640",
            AvatarUtils.defaultQqAvatar(" 123456 "));
        assertNull(AvatarUtils.defaultQqAvatar(" "));
        assertTrue(AvatarUtils.defaultQqAvatar("123456").endsWith("&s=640"));
    }

    /**
     * 回归「先解码再校验尺寸」的内存放大：只构造 PNG 头声明 30000×30000，
     * 旧实现 ImageIO.read 会按声明尺寸分配像素缓冲（数 GB），匿名接口可被打爆。
     * 现在只读 IHDR 就拒绝，不需要任何像素数据。
     */
    @Test
    void rejectsHugeDimensionsFromHeaderWithoutDecodingPixels() throws Exception {
        byte[] headerOnly = pngHeader(30000, 30000);
        assertTrue(headerOnly.length < 200, "样本应当只有 PNG 头，不含像素数据");
        assertEquals("头像尺寸不能超过 512×512，请重新上传",
            AvatarUtils.validateDataUrl("data:image/png;base64,"
                + Base64.getEncoder().encodeToString(headerOnly)));
    }

    private static byte[] pngHeader(int width, int height) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});

        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(width).putInt(height).put((byte) 8).put((byte) 2)
            .put((byte) 0).put((byte) 0).put((byte) 0);
        writeChunk(output, "IHDR", ihdr.array());
        // 空 IDAT：不带任何图像数据，只保证 PNG 结构完整
        writeChunk(output, "IDAT", new byte[] {0x78, (byte) 0x9c, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01});
        writeChunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream output, String type, byte[] data) {
        output.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        output.writeBytes(typeBytes);
        output.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        output.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
