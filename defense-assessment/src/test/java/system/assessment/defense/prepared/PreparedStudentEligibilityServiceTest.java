package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.publication.PreparedPublication;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedStudentEligibilityServiceTest {
    final Instant now=Instant.parse("2026-10-04T10:00:00Z");
    final ResourceAccessControlService access=mock(ResourceAccessControlService.class);
    final PreparedStudentEligibilityRepository repository=mock(PreparedStudentEligibilityRepository.class);
    final PreparedStudentEligibilityService service=new PreparedStudentEligibilityService(access,repository,Clock.fixed(now,ZoneOffset.UTC));
    PreparedStudentEligibilityRepository.Context context(boolean appointment,boolean history,Instant slot,String body,String hash) {
        return new PreparedStudentEligibilityRepository.Context("可变考核主题",appointment,history,0,slot,
                body==null?null:UUID.randomUUID().toString(),hash,body);
    }
    String published() {
        String body="不可泄漏的题目", q=UUID.randomUUID().toString(), a=UUID.randomUUID().toString();
        return PreparedPublication.encode(new PreparedPublication.Snapshot(1,12,new PreparedPublication.Rules("发布时主题",10,6,1,false),
                List.of(new PreparedPublication.Question(q,"MANUAL",body,1,10,120,"秘密评分标准",
                        new PreparedPublication.Audio(a,PreparedJobInput.digest(body),"a".repeat(64),"b".repeat(64),48044,1000,"audio/wav")))));
    }
    @Test void unauthorizedRequestsNeverReadContextOrPublication() {
        doThrow(new BusinessException(403,"无权限")).when(access).requireAssignedStudent(3,12);
        assertThatThrownBy(()->service.status(3,12)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
    }
    @Test void freshScopeCheckFailsClosedAfterAssignmentOrIdentityChange() {
        when(repository.context(3,12)).thenThrow(new BusinessException(403,"无权限"));
        assertThatThrownBy(()->service.status(3,12)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);
        var order=inOrder(access,repository);order.verify(access).requireAssignedStudent(3,12);order.verify(repository).context(3,12);
    }
    @Test void unpublishedStatusContainsNoPlaceholderQuestionsAndCreatesNothing() {
        when(repository.context(3,12)).thenReturn(context(false,false,null,null,null));
        var result=service.status(3,12);assertThat(result.publicationReady()).isFalse();assertThat(result.questionCount()).isZero();
        assertThat(result.phase()).isEqualTo(PreparedStudentEligibility.Phase.PAPER_NOT_PUBLISHED);
        assertThat(result.message()).contains("发布");assertThat(result.serverTime()).isEqualTo(now.toString());
        assertThat(result.appointmentStartsAt()).isNull();assertThat(result.latestFirstStartAt()).isNull();
        verify(repository).context(3,12);verifyNoMoreInteractions(repository);
    }
    @Test void immutablePublicationSuppliesSafeThemeAndCountNotMutableAssessmentOrFullSnapshot() throws Exception {
        String content=published();when(repository.context(3,12)).thenReturn(context(false,false,null,content,PreparedJobInput.digest(content)));
        var result=service.status(3,12);assertThat(result.theme()).isEqualTo("发布时主题");assertThat(result.questionCount()).isEqualTo(1);
        assertThat(result.phase()).isEqualTo(PreparedStudentEligibility.Phase.READY_FOR_PREFLIGHT);
        assertThat(result.message()).contains("预检");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result)).doesNotContain("不可泄漏的题目","秘密评分标准","audio","rubric");
    }
    @Test void appointmentAndHistoryConditionsAreExplicitAndDoNotPunishOrWriteRecords() {
        String content=published();when(repository.context(3,12)).thenReturn(context(true,false,now.plusSeconds(1),content,PreparedJobInput.digest(content)));
        var result=service.status(3,12);assertThat(result.phase()).isEqualTo(PreparedStudentEligibility.Phase.BEFORE_APPOINTMENT);
        assertThat(result.appointmentStartsAt()).isEqualTo(now.plusSeconds(1).toString());
        assertThat(result.latestFirstStartAt()).isEqualTo(now.plusSeconds(601).toString());
        when(repository.context(3,12)).thenReturn(context(false,true,null,content,PreparedJobInput.digest(content)));
        assertThat(service.status(3,12).phase()).isEqualTo(PreparedStudentEligibility.Phase.HISTORY_REQUIRES_REVIEW);
    }
    @Test void corruptedPublicationNeverProducesReadyOrReturnsSensitiveError() {
        when(repository.context(3,12)).thenReturn(context(false,false,null,published(),"c".repeat(64)));
        assertThatThrownBy(()->service.status(3,12)).isInstanceOf(IllegalStateException.class).hasMessage("试卷快照完整性验证失败");
    }
    @Test void invalidIdsAreRejectedBeforeAnyResourceLookup() {
        for(int invalid:new int[]{0,-1,Integer.MIN_VALUE}){
            assertThatThrownBy(()->service.status(invalid,12)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->service.status(3,invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(access,repository);
    }
    @Test void everyPhaseHasAnExplicitRecoveryMessage() {
        for(var phase:PreparedStudentEligibility.Phase.values())assertThat(phase.message()).isNotBlank();
    }
}
