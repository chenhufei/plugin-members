package run.halo.members;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WidgetCdnTest {

    @Test
    void shouldUseMainlandFriendlyCdnForJsQr() throws IOException {
        var widgetSource = Files.readString(
            Path.of("widget", "src", "member-apply-widget.iife.js")
        );

        assertThat(widgetSource)
            .contains("https://npm.elemecdn.com/jsqr@1.4.0/dist/jsQR.js")
            .doesNotContain("cdn.jsdelivr.net", "cdnjs.cloudflare.com", "unpkg.com");
    }
}
