package run.halo.members;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WidgetCdnTest {

    @Test
    void shouldLoadBundledJsQrWithoutRuntimeCdn() throws IOException {
        var widgetSource = Files.readString(
            Path.of("widget", "src", "member-apply-widget.iife.js")
        );

        assertThat(widgetSource)
            .contains("new URL('vendor/jsQR.js', WIDGET_ASSET_BASE).href")
            .doesNotContain(
                "npm.elemecdn.com", "cdn.jsdelivr.net", "cdnjs.cloudflare.com", "unpkg.com"
            );

        assertThat(Path.of("ui", "node_modules", "jsqr", "dist", "jsQR.js"))
            .exists();
        assertThat(Path.of("src", "main", "resources", "static", "vendor", "jsQR.LICENSE.txt"))
            .exists();
    }
}
