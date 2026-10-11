package system.assessment.defense.application.manage;

import com.aliyun.oss.OSS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OssManagerDeletionTest {

    private OSS client;
    private OssManager manager;

    @BeforeEach
    void setUp() {
        client = mock(OSS.class);
        manager = new OssManager();
        ReflectionTestUtils.setField(manager, "ossClient", client);
        ReflectionTestUtils.setField(manager, "bucketName", "bucket");
        ReflectionTestUtils.setField(manager, "endpoint", "oss.example.com");
    }

    @Test
    void deletesOnlyObjectsOwnedByThisApplication() {
        String key = "defense-assessment-new/system/2026/10/02/voice.wav";

        assertThat(manager.deleteStoredObject(key)).isTrue();

        verify(client).deleteObject("bucket", key);
    }

    @Test
    void rejectsTraversalAndForeignObjectKeys() {
        assertThatThrownBy(() -> manager.deleteStoredObject("../other-system/private.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.deleteStoredObject("other-system/private.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOnlyAUrlForTheConfiguredBucketAndEndpoint() {
        String key = "defense-assessment-new/users/7/2026/10/02/file.pdf";

        assertThat(manager.deleteStoredObject("https://bucket.oss.example.com/" + key)).isTrue();

        verify(client).deleteObject("bucket", key);
        assertThatThrownBy(() -> manager.deleteStoredObject("https://evil.example.com/" + key))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
