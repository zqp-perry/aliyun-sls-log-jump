package dev.slslogjump;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlsSettingsTest {
    @Test
    void freshInstallationHasNoTargetAndCannotQuery() {
        SlsSettings settings = new SlsSettings();
        assertFalse(settings.isConfigured());
        assertEquals("", settings.getEndpoint());
        assertEquals("", settings.getProject());
        assertEquals("", settings.getLogStore());
    }

    @Test
    void targetRequiresAllThreeValuesAndSupportsReplacement() {
        SlsSettings settings = new SlsSettings();
        settings.updateTarget("example.log.aliyuncs.com", "example-project", "");
        assertFalse(settings.isConfigured());
        settings.updateTarget(" example.log.aliyuncs.com ", " example-project ", " example-logs ");
        assertTrue(settings.isConfigured());
        assertEquals("example-project", settings.getProject());
        settings.updateTarget("other.log.aliyuncs.com", "other-project", "other-logs");
        assertEquals("other-project", settings.getProject());
        assertEquals("other-logs", settings.getLogStore());
    }

    @Test
    void settingsArePerInstanceAndSurviveReloadWithoutChangingDefaults() {
        SlsSettings a = new SlsSettings();
        a.updateTarget("example.log.aliyuncs.com", "example-project", "example-logs");
        SlsSettings b = new SlsSettings();
        assertFalse(b.isConfigured());
        b.loadState(a.getState());
        assertTrue(b.isConfigured());
        assertEquals("example-logs", b.getLogStore());
        assertEquals(2, b.getPollIntervalSeconds());
        assertEquals(15, b.getDefaultMinutes());
        SlsSettings.State empty = new SlsSettings.State();
        empty.endpoint = null;
        empty.project = null;
        b.loadState(empty);
        assertFalse(b.isConfigured());
    }
}
