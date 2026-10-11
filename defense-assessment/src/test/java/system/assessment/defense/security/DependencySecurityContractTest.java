package system.assessment.defense.security;

import com.alibaba.fastjson2.JSON;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import org.junit.jupiter.api.Test;
import system.assessment.defense.application.manage.OssManager;

import static org.assertj.core.api.Assertions.assertThat;

class DependencySecurityContractTest {

    @Test
    void fastjsonIncludesTheUpstreamParserSecurityFixes() {
        String source = JSON.class.getProtectionDomain().getCodeSource().getLocation().toString();

        assertThat(source).contains("/fastjson2/2.0.65/");
    }

    @Test
    void signedUrlsNeverDeserializeValuesFromTheSharedCache() throws Exception {
        Cached cachePolicy = OssManager.class
                .getDeclaredMethod("getSignedUrl", String.class)
                .getAnnotation(Cached.class);

        assertThat(cachePolicy).isNotNull();
        assertThat(cachePolicy.cacheType()).isEqualTo(CacheType.LOCAL);
    }
}
