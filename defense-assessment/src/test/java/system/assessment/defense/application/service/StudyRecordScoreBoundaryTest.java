package system.assessment.defense.application.service;

import org.junit.jupiter.api.Test;
import system.assessment.defense.application.dto.backed.StudentRecordChangeScoreCmd;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentQuestionAnswerService;
import system.assessment.defense.infrastructure.repository.dao.service.StudentAssessmentRecordService;
import system.assessment.defense.infrastructure.repository.dao.service.UserService;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudyRecordScoreBoundaryTest {

    @Test
    void rejectsTeacherScoreAboveAssessmentTotal() {
        StudentAssessmentRecordService recordService = mock(StudentAssessmentRecordService.class);
        AssessmentSettingService settingService = mock(AssessmentSettingService.class);
        ResourceAccessControlService accessControl = mock(ResourceAccessControlService.class);
        StudyRecordService service = new StudyRecordService(
                recordService,
                mock(StudentAssessmentQuestionAnswerService.class),
                mock(UserService.class),
                settingService,
                accessControl, mock(system.assessment.defense.prepared.processing.PreparedRecordBridge.class));
        StudentAssessmentRecordPO record = StudentAssessmentRecordPO.builder()
                .id(8)
                .assessmentId(20)
                .build();
        when(recordService.getById(8)).thenReturn(record);
        when(settingService.getById(20)).thenReturn(AssessmentSettingPO.builder()
                .id(20)
                .totalScore(100)
                .build());
        StudentRecordChangeScoreCmd command = new StudentRecordChangeScoreCmd();
        command.setId(8);
        command.setCheckScore(new BigDecimal("101"));

        assertThatThrownBy(() -> service.changeScore(7, command))
                .isInstanceOf(BusinessException.class);
        verify(accessControl).requireAssessmentManager(7, 20);
        verify(recordService, never()).updateById(record);
    }
}
