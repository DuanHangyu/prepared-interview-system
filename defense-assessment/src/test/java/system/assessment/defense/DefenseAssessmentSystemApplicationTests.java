package system.assessment.defense;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "aliyun.oss.accessKeyId=test-access-key-id",
        "aliyun.oss.accessKeySecret=test-access-key-secret",
        "spring.flyway.enabled=false",
        "scheduler.start=stop"
})
class DefenseAssessmentSystemApplicationTests {

    @Test
    void contextLoads() {
    }

}
