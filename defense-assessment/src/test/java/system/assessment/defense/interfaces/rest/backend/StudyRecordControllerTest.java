package system.assessment.defense.interfaces.rest.backend;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import system.assessment.defense.application.dto.StudyRecordDTO;
import system.assessment.defense.application.dto.StudyRecordPagingQuery;
import system.assessment.defense.application.service.StudentAssessmentService;
import system.assessment.defense.application.service.StudyRecordService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudyRecordControllerTest {

    @Test
    void listPassesAuthenticatedTeacherIdToVisibilityBoundary() {
        StudyRecordService studyRecordService = mock(StudyRecordService.class);
        StudyRecordController controller = new StudyRecordController();
        ReflectionTestUtils.setField(controller, "studyRecordService", studyRecordService);
        ReflectionTestUtils.setField(controller, "studentAssessmentService", mock(StudentAssessmentService.class));
        StudyRecordPagingQuery query = new StudyRecordPagingQuery();
        query.setPage(1);
        query.setSize(20);
        Page<StudyRecordDTO> expected = new Page<>(1, 20);
        when(studyRecordService.recordList(42, query)).thenReturn(expected);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsInt).thenReturn(42);

            assertThat(controller.recordList(query)).isSameAs(expected);
        }

        verify(studyRecordService).recordList(42, query);
    }
}
