package system.assessment.defense.application.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import system.assessment.defense.application.dto.*;
import system.assessment.defense.application.dto.front.StudentAppointmentCmd;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Stable application facade. Domain behavior lives in focused collaborators.
 */
@Service
@RequiredArgsConstructor
public class StudentAssessmentService {

    private final StudentAssessmentQueryService queryService;
    private final StudentAppointmentApplicationService appointmentService;
    private final StudentAssessmentAnalysisService analysisService;
    private final StudentInterviewSessionService interviewSessionService;

    public void schedulerCheckStudentAppointment() {
        appointmentService.schedulerCheckStudentAppointment();
    }

    public void schedulerCheckAnalysis() {
        analysisService.schedulerCheckAnalysis();
    }

    public Boolean reanalyze(Integer teacherId, Integer recordId) {
        return analysisService.reanalyze(teacherId, recordId);
    }

    public StudentDefenseStatisticDTO studentDefenseStatistic(int studentId) {
        return queryService.studentDefenseStatistic(studentId);
    }

    public IPage<StudentTodoDefenseDTO> todoDefenseList(
            StudentDefensePagingQuery pagingQuery, Integer studentId) {
        return queryService.todoDefenseList(pagingQuery, studentId);
    }

    public IPage<StudentTodoDefenseDTO> toAppointmentList(
            StudentDefensePagingQuery pagingQuery, Integer studentId) {
        return queryService.toAppointmentList(pagingQuery, studentId);
    }

    public IPage<StudentTodoDefenseDTO> analysisDefenseList(
            StudentDefensePagingQuery pagingQuery, Integer studentId) {
        return queryService.analysisDefenseList(pagingQuery, studentId);
    }

    public IPage<StudentDoneDefenseDTO> doneDefenseList(
            StudentDefensePagingQuery pagingQuery, Integer studentId) {
        return queryService.doneDefenseList(pagingQuery, studentId);
    }

    public void handleWebsocketMessage(
            Integer studentId,
            WebsocketMessageDTO messageDTO,
            Consumer<WebSocketResponse> consumer) {
        interviewSessionService.handleWebsocketMessage(studentId, messageDTO, consumer);
    }

    public Boolean uploadDefenseFile(StudentDefenseFileUploadCmd uploadCmd, Integer studentId) {
        return interviewSessionService.uploadDefenseFile(uploadCmd, studentId);
    }

    public void appendVoice(ByteBuffer payload, Integer studentId) throws IOException {
        interviewSessionService.appendVoice(payload, studentId);
    }

    public void releaseAudio(Integer studentId) {
        interviewSessionService.releaseAudio(studentId);
    }

    public StudentAssessmentDetailDTO assessmentDetailByRecordId(Integer teacherId, Integer id) {
        return queryService.assessmentDetailByRecordId(teacherId, id);
    }

    public StudentAssessmentDetailDTO assessmentDetail(Integer studentId, Integer id) {
        return queryService.assessmentDetail(studentId, id);
    }

    public Boolean studentAppointment(Integer studentId, StudentAppointmentCmd appointmentCmd) {
        return appointmentService.studentAppointment(studentId, appointmentCmd);
    }

    public Boolean cancelAppointment(Integer studentId, Integer assessmentId) {
        return appointmentService.cancelAppointment(studentId, assessmentId);
    }
}
