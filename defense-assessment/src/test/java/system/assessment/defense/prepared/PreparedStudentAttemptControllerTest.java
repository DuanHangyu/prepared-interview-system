package system.assessment.defense.prepared;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.*;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparedStudentAttemptControllerTest {
    @Test void formalRoutesAreStudentOnlyAndHaveNoClientSuppliedStudentIdentity() throws Exception {
        var type=PreparedStudentAttemptController.class;
        assertThat(type.getAnnotation(SaCheckRole.class).value()).containsExactly("STUDENT");
        assertThat(type.getAnnotation(RequestMapping.class).value()).containsExactly("/backend/prepared-interviews");
        assertThat(type.getDeclaredMethod("start",int.class,PreparedAttemptStart.Command.class).getAnnotation(PostMapping.class).value()).containsExactly("/{assessmentId}/attempts");
        assertThat(type.getDeclaredMethod("current",int.class).getAnnotation(GetMapping.class).value()).containsExactly("/{assessmentId}/attempts/current");
        assertThat(type.getDeclaredMethod("startQuestion",int.class,String.class,PreparedQuestionGrant.Command.class).getAnnotation(PostMapping.class).value()).containsExactly("/{assessmentId}/attempts/{attemptId}/questions/start");
        assertThat(type.getDeclaredMethod("currentQuestion",int.class,String.class,String.class).getAnnotation(GetMapping.class).value()).containsExactly("/{assessmentId}/attempts/{attemptId}/questions/current");
        assertThat(type.getDeclaredMethod("end",int.class,String.class,String.class,PreparedAnswerEnd.Command.class).getAnnotation(PostMapping.class).value()).containsExactly("/{assessmentId}/attempts/{attemptId}/answers/{answerId}/end");
    }
    @Test void commandsUseAuthenticatedIdentityAndDelegateOriginalRequestsUnchanged() {
        var attempts=mock(PreparedAttemptStartService.class);var questions=mock(PreparedQuestionGrantService.class);var ends=mock(PreparedAnswerEndService.class);
        var controller=new PreparedStudentAttemptController(attempts,questions,ends,true);
        var start=new PreparedAttemptStart.Command("original-key");var grant=new PreparedQuestionGrant.Command("original-grant","original-client",1);
        var end=new PreparedAnswerEnd.Command("original-end","original-client",null);
        try(var identity=mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsInt).thenReturn(72);
            controller.start(9,start);controller.current(9);controller.startQuestion(9,"attempt",grant);
            controller.currentQuestion(9,"attempt","client");controller.end(9,"attempt","answer",end);
        }
        verify(attempts).start(72,9,start);verify(attempts).current(72,9);
        verify(questions).start(72,9,"attempt",grant);verify(questions).current(72,9,"attempt","client");
        verify(ends).end(72,9,"attempt","answer",end);
    }
    @Test void closedRolloutRefusesMutationsButKeepsReadOnlyRecoveryAvailable() {
        var attempts=mock(PreparedAttemptStartService.class);var questions=mock(PreparedQuestionGrantService.class);var ends=mock(PreparedAnswerEndService.class);
        var controller=new PreparedStudentAttemptController(attempts,questions,ends,false);
        assertThatThrownBy(()->controller.start(9,null)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        assertThatThrownBy(()->controller.startQuestion(9,"attempt",null)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        assertThatThrownBy(()->controller.end(9,"attempt","answer",null)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        verifyNoInteractions(attempts,questions,ends);
        try(var identity=mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsInt).thenReturn(72);
            when(attempts.current(72,9)).thenReturn(Optional.empty());when(questions.current(72,9,"attempt","client")).thenReturn(Optional.empty());
            assertThat(controller.current(9)).isNull();assertThat(controller.currentQuestion(9,"attempt","client")).isNull();
        }
    }
}
