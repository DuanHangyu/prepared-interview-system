package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.attempt.PreparedStudentEligibility;
import static org.assertj.core.api.Assertions.*;

class PreparedPreflightContextTest {
    final PreparedStudentEligibility.Status status=new PreparedStudentEligibility.Status(8,"预检",0,false,false,
            PreparedStudentEligibility.Phase.PAPER_NOT_PUBLISHED,"等待教师准备","2026-10-04T12:00:00Z",null,null);
    @Test void contextCarriesOnlyCurrentServerIdentityAndSafeReadOnlyEligibility() {
        var context=new PreparedStudentEligibility.PreflightContext(3,"prepared-local",status);
        assertThat(context.studentId()).isEqualTo(3);
        assertThat(context.deploymentId()).isEqualTo("prepared-local");
        assertThat(context.eligibility()).isSameAs(status);
    }
    @Test void invalidIdentityOrMissingSafeEligibilityFailsClosed() {
        assertThatThrownBy(()->new PreparedStudentEligibility.PreflightContext(0,"prepared-local",status)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new PreparedStudentEligibility.PreflightContext(3,"prepared-local",null)).isInstanceOf(IllegalStateException.class);
        for(String deployment:new String[]{null,"","legacy","prepared-","prepared-Local","prepared-a/../b","prepared-"+"a".repeat(73)})
            assertThatThrownBy(()->new PreparedStudentEligibility.PreflightContext(3,deployment,status)).isInstanceOf(IllegalStateException.class);
    }
    @Test void explicitWorkflowFlagIsReadOnlyAndCompatibilityConstructionRemainsClosed() {
        assertThat(new PreparedStudentEligibility.PreflightContext(3,"prepared-local",status).workflowEnabled()).isFalse();
        assertThat(new PreparedStudentEligibility.PreflightContext(3,"prepared-local",status,true).workflowEnabled()).isTrue();
    }
}
