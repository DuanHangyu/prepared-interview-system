package system.assessment.defense.prepared;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.paper.PreparedPaper;
import system.assessment.defense.prepared.paper.PreparedPaper.Draft;
import system.assessment.defense.prepared.paper.PreparedPaper.Question;
import system.assessment.defense.prepared.paper.PreparedPaper.QuestionInput;
import system.assessment.defense.prepared.paper.PreparedPaperRepository;
import system.assessment.defense.prepared.paper.PreparedPaperService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PreparedPaperServiceTest {
    static final String Q1 = "11111111-1111-4111-8111-111111111111";
    static final String Q2 = "22222222-2222-4222-8222-222222222222";
    static final String Q3 = "33333333-3333-4333-8333-333333333333";
    ResourceAccessControlService access;
    PreparedPaperRepository repository;
    PreparedPaperService service;

    @BeforeEach void setup() {
        access = mock(ResourceAccessControlService.class);
        repository = mock(PreparedPaperRepository.class);
        service = new PreparedPaperService(access, repository);
        when(repository.load(21)).thenReturn(new Draft(21, 0, List.of()));
        when(repository.loadForUpdate(21)).thenReturn(new Draft(21, 0, List.of()));
        when(repository.replaceDraft(any(), anyLong(), anyInt())).thenReturn(true);
    }

    QuestionInput input(String id, String body) {
        return new QuestionInput(id, body, 10, 120, "说明依据");
    }

    Question approved(String id, int ordinal) {
        return new Question(id, "MANUAL", "题目 " + ordinal, ordinal, 10, 120,
                "说明依据", true, "READY", "asset/" + id);
    }

    @Test void persistsThreeManualQuestionsInListOrderAndDoesNotAutoApprove() {
        var draft = service.save(4, 21, 0, List.of(input(Q1, "第一题"), input(Q2, "第二题"), input(Q3, "第三题")));
        assertEquals(1, draft.revision());
        assertEquals(List.of(1, 2, 3), draft.questions().stream().map(Question::ordinal).toList());
        assertTrue(draft.questions().stream().noneMatch(Question::approved));
        assertTrue(draft.questions().stream().allMatch(q -> q.source().equals("MANUAL") && q.audioState().equals("PENDING")));
        verify(access).requireAssessmentManager(4, 21);
        verify(repository).replaceDraft(draft, 0, 4);
    }

    @Test void readsPersistedDraftOnlyAfterResourceAuthorization() {
        service.getDraft(4, 21);
        verify(access).requireAssessmentManager(4, 21);
        verify(repository).load(21);
    }

    @Test void deniesStudentOrUnrelatedTeacherWithoutTouchingRepository() {
        doThrow(new BusinessException(403, "无权限")).when(access).requireAssessmentManager(12, 21);
        assertThrows(BusinessException.class, () -> service.getDraft(12, 21));
        assertThrows(BusinessException.class, () -> service.save(12, 21, 0, List.of(input(Q1, "第一题"))));
        assertThrows(BusinessException.class, () -> service.approve(12, 21, 0, List.of(Q1)));
        verifyNoInteractions(repository);
    }

    @Test void rejectsStaleRevisionWithoutOverwritingDraft() {
        when(repository.loadForUpdate(21)).thenReturn(new Draft(21, 3, List.of()));
        var failure = assertThrows(BusinessException.class, () -> service.save(4, 21, 2, List.of(input(Q1, "题目"))));
        assertEquals(409, failure.getCode());
        verify(repository, never()).replaceDraft(any(), anyLong(), anyInt());
    }

    @Test void handlesPersistenceRaceAsConflict() {
        when(repository.replaceDraft(any(), anyLong(), anyInt())).thenReturn(false);
        var failure = assertThrows(BusinessException.class, () -> service.save(4, 21, 0, List.of(input(Q1, "题目"))));
        assertEquals(409, failure.getCode());
    }

    @Test void bodyEditInvalidatesApprovalAndExactBoundAudio() {
        when(repository.loadForUpdate(21)).thenReturn(new Draft(21, 2, List.of(approved(Q1, 1))));
        var changed = service.save(4, 21, 2, List.of(input(Q1, "已修改正文"))).questions().getFirst();
        assertFalse(changed.approved());
        assertEquals("STALE", changed.audioState());
        assertNull(changed.audioObjectKey());
    }

    @Test void ruleEditInvalidatesApprovalButCanKeepSameBodyAudio() {
        when(repository.loadForUpdate(21)).thenReturn(new Draft(21, 2, List.of(approved(Q1, 1))));
        var changed = service.save(4, 21, 2, List.of(new QuestionInput(Q1, "题目 1", 20, 120, "说明依据"))).questions().getFirst();
        assertFalse(changed.approved());
        assertEquals("READY", changed.audioState());
        assertEquals("asset/" + Q1, changed.audioObjectKey());
    }

    @Test void reorderAndDeletionDoNotChangeQuestionIdentityOrUnchangedApproval() {
        when(repository.loadForUpdate(21)).thenReturn(new Draft(21, 2, List.of(approved(Q1, 1), approved(Q2, 2), approved(Q3, 3))));
        var draft = service.save(4, 21, 2, List.of(input(Q3, "题目 3"), input(Q1, "题目 1")));
        assertEquals(List.of(Q3, Q1), draft.questions().stream().map(Question::questionId).toList());
        assertTrue(draft.questions().stream().allMatch(Question::approved));
        assertEquals(List.of(1, 2), draft.questions().stream().map(Question::ordinal).toList());
    }

    @Test void approvalIsExplicitAndBoundToCurrentRevision() {
        when(repository.loadForUpdate(21)).thenReturn(new Draft(21, 2,
                List.of(new Question(Q1, "MANUAL", "题目", 1, 10, 120, "说明依据", false, "PENDING", null))));
        var draft = service.approve(4, 21, 2, List.of(Q1));
        assertEquals(3, draft.revision());
        assertTrue(draft.questions().getFirst().approved());
        var captured = ArgumentCaptor.forClass(Draft.class);
        verify(repository).replaceDraft(captured.capture(), eq(2L), eq(4));
        assertTrue(captured.getValue().questions().getFirst().approved());
        verify(repository).recordApproval(draft, 4, List.of(Q1));
    }

    @Test void cannotApproveMissingQuestionOrEmptySelection() {
        assertThrows(IllegalArgumentException.class, () -> service.approve(4, 21, 0, List.of(Q1)));
        assertThrows(IllegalArgumentException.class, () -> service.approve(4, 21, 0, List.of()));
        verify(repository, never()).replaceDraft(any(), anyLong(), anyInt());
    }

    @Test void rejectsEmptyBodyDuplicateIdsAndInvalidRules() {
        for (var inputs : List.of(
                List.of(input(Q1, "  ")),
                List.of(input(Q1, "题目"), input(Q1, "重复")),
                List.of(new QuestionInput(Q1, "题目", 0, 120, "标准")),
                List.of(new QuestionInput(Q1, "题目", 10, 0, "标准")),
                List.of(input("../foreign", "题目")),
                List.of(new QuestionInput(Q1, "题目", 10, 120, " ")))) {
            assertThrows(IllegalArgumentException.class, () -> service.save(4, 21, 0, inputs));
        }
        verify(repository, never()).replaceDraft(any(), anyLong(), anyInt());
    }

    @Test void emptyDraftIsAllowedButCannotBeApproved() {
        assertEquals(0, service.save(4, 21, 0, List.of()).questions().size());
        assertThrows(IllegalArgumentException.class, () -> service.approve(4, 21, 0, List.of()));
    }

    @Test void validatesBoundedContentAndQuestionCount() {
        assertThrows(IllegalArgumentException.class, () -> service.save(4, 21, 0, List.of(input(Q1, "x".repeat(10001)))));
        assertThrows(IllegalArgumentException.class, () -> service.save(4, 21, 0, java.util.Collections.nCopies(51, input(Q1, "题目"))));
        assertThrows(IllegalArgumentException.class, () -> service.save(4, 21, Long.MAX_VALUE, List.of(input(Q1, "题目"))));
    }

    @Test void draftQuestionListIsAnImmutableSnapshot() {
        var draft = service.save(4, 21, 0, List.of(input(Q1, "题目")));
        assertThrows(UnsupportedOperationException.class, () -> draft.questions().clear());
    }

    @Test void teacherAdoptsAiSuggestionsWithoutChangingReviewedManualQuestions() {
        Question manual = approved(Q1, 1);
        when(repository.loadForUpdate(21)).thenReturn(new Draft(21, 2, List.of(manual)));
        Draft next = service.appendAi(4, 21, 2, List.of(input(Q2, "AI 建议")));
        assertEquals(manual, next.questions().getFirst());
        assertEquals("AI", next.questions().get(1).source());
        assertFalse(next.questions().get(1).approved());
        assertEquals("PENDING", next.questions().get(1).audioState());
        assertEquals(3, next.revision());
    }

    @Test void adoptionDoesNotAllowDuplicateIdsEmptySelectionOrStaleOverwrite() {
        when(repository.loadForUpdate(21)).thenReturn(new Draft(21, 2, List.of(approved(Q1, 1))));
        assertThrows(BusinessException.class, () -> service.appendAi(4, 21, 1, List.of(input(Q2, "AI 建议"))));
        assertThrows(BusinessException.class, () -> service.appendAi(4, 21, 2, List.of(input(Q1, "重复"))));
        assertThrows(IllegalArgumentException.class, () -> service.appendAi(4, 21, 2, List.of()));
        verify(repository, never()).replaceDraft(any(), anyLong(), anyInt());
    }
    @Test void aiAppendValidatesSizeIdentityAndAllAnswerRulesBeforePersistence() {
        assertThrows(IllegalArgumentException.class,()->service.appendAi(4,21,0,null));
        assertThrows(IllegalArgumentException.class,()->service.appendAi(4,21,0,java.util.Collections.nCopies(21,input(Q2,"建议"))));
        for(var q:List.of(input("invalid","题目"),input(Q2," "),new QuestionInput(Q2,"题目",0,120,"标准"),
                new QuestionInput(Q2,"题目",10001,120,"标准"),new QuestionInput(Q2,"题目",10,0,"标准"),
                new QuestionInput(Q2,"题目",10,1801,"标准")))
            assertThrows(IllegalArgumentException.class,()->service.appendAi(4,21,0,List.of(q)));
        assertThrows(IllegalArgumentException.class,()->service.appendAi(4,21,0,java.util.Collections.singletonList(null)));
        verify(repository,never()).replaceDraft(any(),anyLong(),anyInt());
    }
}
