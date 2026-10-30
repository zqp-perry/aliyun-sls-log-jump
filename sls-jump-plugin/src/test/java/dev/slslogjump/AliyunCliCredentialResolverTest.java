package dev.slslogjump;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AliyunCliCredentialResolverTest {
    @TempDir Path dir;

    @Test
    void readsSelectedProfileInsteadOfFirstAccount() throws Exception {
        Path p = dir.resolve("config.json");
        Files.writeString(p, """
            {"current":"second","profiles":[
              {"name":"first","access_key_id":"fake-first","access_key_secret":"fake-first-secret"},
              {"name":"second","access_key_id":"fake-second","access_key_secret":"fake-second-secret","sts_token":"fake-token"}
            ]}
            """);
        var credential = AliyunCliCredentialResolver.fromConfig(p);
        assertEquals("fake-second", credential.accessKeyId);
        assertEquals("fake-token", credential.securityToken);
        assertTrue(credential.hasToken());
    }

    @Test
    void staleProfileDoesNotSilentlyUseDifferentAccount() throws Exception {
        Path p = dir.resolve("config.json");
        Files.writeString(p, """
            {"current":"missing","profiles":[
              {"name":"first","access_key_id":"fake-first","access_key_secret":"fake-secret"}
            ]}
            """);
        assertThrows(IllegalStateException.class, () -> AliyunCliCredentialResolver.fromConfig(p));
    }

    @Test
    void singleProfileWithoutCurrentSupportsLongTermKey() throws Exception {
        Path p = dir.resolve("config.json");
        Files.writeString(p, """
            {"profiles":[{"name":"default","access_key_id":"fake-id","access_key_secret":"fake-secret"}]}
            """);
        assertFalse(AliyunCliCredentialResolver.fromConfig(p).hasToken());
    }

    @Test
    void roleProfilesNeverUseTheSourceIdentityAsTheTargetIdentity() throws Exception {
        Path p = dir.resolve("config.json");
        for (String mode : new String[]{"RamRoleArn", "EcsRamRole", "CloudSSO", "External"}) {
            Files.writeString(p, "{\"current\":\"role\",\"profiles\":[{\"name\":\"role\",\"mode\":\"" + mode
                    + "\",\"access_key_id\":\"fake-source-id\",\"access_key_secret\":\"fake-source-secret\"}]}");
            var error = assertThrows(IllegalStateException.class, () -> AliyunCliCredentialResolver.fromConfig(p));
            assertTrue(error.getMessage().contains("AK/StsToken"));
            assertFalse(error.getMessage().contains("fake-source"));
        }
    }

    @Test
    void supportedCliModesReadTheirCredentials() throws Exception {
        Path p = dir.resolve("config.json");
        for (String mode : new String[]{"AK", "StsToken"}) {
            Files.writeString(p, "{\"current\":\"default\",\"profiles\":[{\"name\":\"default\",\"mode\":\"" + mode
                    + "\",\"access_key_id\":\"fake-id\",\"access_key_secret\":\"fake-secret\",\"sts_token\":\"fake-token\"}]}");
            assertEquals("fake-id", AliyunCliCredentialResolver.fromConfig(p).accessKeyId);
        }
    }

    @Test
    void malformedConfigurationDoesNotEchoItsContentsOrLocalPath() throws Exception {
        Path p = dir.resolve("config.json");
        Files.writeString(p, "{\"access_key_secret\": fake-sensitive-marker}");
        var error = assertThrows(IllegalStateException.class, () -> AliyunCliCredentialResolver.fromConfig(p));
        assertFalse(error.getMessage().contains("fake-sensitive-marker"));
        assertFalse(error.getMessage().contains(dir.toString()));
        assertNull(error.getCause());
    }

    @Test
    void emptyOrIncompleteConfigurationFailsClearly() throws Exception {
        Path p = dir.resolve("config.json");
        for (String content : new String[]{"", "{}", "{\"profiles\":[]}",
                "{\"profiles\":[{\"name\":\"default\",\"access_key_id\":\"\",\"access_key_secret\":\"\"}]}"}) {
            Files.writeString(p, content);
            assertThrows(IllegalStateException.class, () -> AliyunCliCredentialResolver.fromConfig(p));
        }
    }
}
