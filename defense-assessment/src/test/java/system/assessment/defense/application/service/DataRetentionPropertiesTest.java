package system.assessment.defense.application.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DataRetentionPropertiesTest {

    @Test
    void defaultsKeepAuditsAndMediaBoundedWithoutAutomaticallyPurgingBusinessResults() {
        DataRetentionProperties properties = new DataRetentionProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getTokenAudit()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.getInterviewMedia()).isEqualTo(Duration.ofDays(180));
        assertThat(properties.getDeletedAssessmentMaterial()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.getBusinessRecord()).isEqualTo(Duration.ofDays(730));
        assertThat(properties.isPurgeBusinessRecords()).isFalse();
        assertThat(properties.getBatchSize()).isBetween(1, 1000);
    }
}
