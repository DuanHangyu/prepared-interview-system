package system.assessment.defense.application.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "data-retention")
public class DataRetentionProperties {

    private boolean enabled = true;

    private Duration tokenAudit = Duration.ofDays(30);

    private Duration interviewMedia = Duration.ofDays(180);

    private Duration deletedAssessmentMaterial = Duration.ofDays(30);

    private Duration businessRecord = Duration.ofDays(730);

    private boolean purgeBusinessRecords = false;

    private int batchSize = 100;

    private int maxBatchesPerRun = 10;
}
