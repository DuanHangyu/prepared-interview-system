package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.application.service.FileSecurityService;
import system.assessment.defense.infrastructure.config.AliyunOssConfig;
import system.assessment.defense.infrastructure.config.GlobalException;

import static org.assertj.core.api.Assertions.*;

/** Actual Spring wiring/SDK credential rejection, without a network call or fake successful storage. */
class PreparedLegacyStorageStartupTest {
    private ApplicationContextRunner context(String id, String secret) {
        return new ApplicationContextRunner()
                .withUserConfiguration(AliyunOssConfig.class, OssManager.class, FileSecurityService.class)
                .withPropertyValues("aliyun.oss.endpoint=oss-cn-beijing.aliyuncs.com",
                        "aliyun.oss.bucketName=prepared-interview-local",
                        "aliyun.oss.accessKeyId=" + id, "aliyun.oss.accessKeySecret=" + secret);
    }

    @Test void noCloudCredentialsDoesNotPreventIndependentAccountAndManualPaperStartup() {
        context("", "").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(OssManager.class)).isNotNull();
            assertThat(c.getBeanFactory().containsSingleton("ossClient")).isFalse();
        });
    }

    @Test void incompleteCloudCredentialsRemainUnusedAtStartup() {
        for (String[] pair : new String[][]{{"", "synthetic-sdk-only-secret"}, {"synthetic-sdk-only-id", ""}}) {
            context(pair[0], pair[1]).run(c -> {
                assertThat(c).hasNotFailed();
                assertThat(c.getBean(OssManager.class)).isNotNull();
                assertThat(c.getBeanFactory().containsSingleton("ossClient")).isFalse();
            });
        }
    }

    @Test void actualMissingCredentialOperationNeverReturnsASuccessfulSignedUrl() {
        context("", "").run(c -> {
            assertThat(c).hasNotFailed();
            Exception failed = catchException(() -> c.getBean(OssManager.class).getSignedUrl("synthetic-not-an-object"));
            assertThat(failed).isNotNull();
            var response = new GlobalException().handleException(failed);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().getCode()).isEqualTo(503);
            assertThat(response.getBody().getMessage()).isEqualTo("文件存储配置不可用，请联系教师或管理员；未确认文件不能视为已保存");
            assertThat(c.getBeanFactory().containsSingleton("ossClient")).isFalse();
        });
    }

    @Test void nestedSdkCredentialFailureReturnsSafeUnavailableNotAParameterOrSuccessfulResult() {
        var failure = new RuntimeException("synthetic-wrapper-secret-never-displayed",
                new com.aliyun.oss.common.auth.InvalidCredentialsException("synthetic-secret-never-displayed"));
        var response = new GlobalException().handleException(failure);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().getCode()).isEqualTo(503);
        assertThat(response.getBody().getMessage()).doesNotContain("synthetic", "secret");
    }
}
