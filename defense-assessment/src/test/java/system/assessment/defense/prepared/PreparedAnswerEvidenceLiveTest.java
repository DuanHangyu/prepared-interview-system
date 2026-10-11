package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static system.assessment.defense.prepared.attempt.PreparedAnswerEnd.*;

/** Actual independent SQL and original declaration transactions. Metadata/audio assets are
 * synthetic fixtures, NOT actual audio bytes, private cloud or completed exception handling. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAnswerEvidenceLiveTest {
    final PreparedAnswerEndLiveTest f=new PreparedAnswerEndLiveTest();
    PreparedAnswerEvidenceService service;
    final List<Integer> users=new ArrayList<>();final List<String> historical=new ArrayList<>();
    String answer;
    @BeforeEach void setup(){
        f.setup();var q=f.q;answer=q.grant(1).answerId();
        service=new PreparedAnswerEvidenceService(q.f.access,new PreparedAttemptStartRepository(q.f.jdbc),
            new PreparedQuestionGrantRepository(q.f.jdbc),new PreparedAnswerEndRepository(q.f.jdbc),q.f.jdbc,q.f.tx.getTransactionManager());
    }
    @AfterEach void cleanup(){
        var q=f.q;q.f.jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=?",q.f.assessment);
        if(!historical.isEmpty()){
            q.f.jdbc.update("UPDATE prepared_attempt_anchor SET active_attempt_id=? WHERE assessment_id=? AND student_id=?",q.attempt.id(),q.f.assessment,q.student);
            for(String id:historical)q.f.jdbc.update("DELETE FROM prepared_attempt WHERE id=? AND assessment_id=?",id,q.f.assessment);
        }
        for(int id:users)q.f.jdbc.update("DELETE FROM `user` WHERE id=?",id);
        f.cleanup();
    }
    PreparedAnswerEvidenceService.Evidence read(int after,int size){return service.read(f.q.f.actor,f.q.f.assessment,f.q.attempt.id(),answer,after,size);}
    Manifest interrupted(int segments){
        var all=new ArrayList<Segment>();
        for(int i=1;i<=segments;i++)all.add(new Segment(UUID.randomUUID().toString(),i,"audio/webm;codecs=opus",(long)i*1000,(long)i*1000+500,
            "INTERRUPTED","DEVICE_ENDED",1,3,List.of(new Chunk(0,3,"a".repeat(64)))));
        return new Manifest(1,all);
    }
    Summary close(Manifest manifest){f.q.now.set(f.q.now.get().plusSeconds(10));return f.end(answer,f.command(manifest));}
    int user(int role){String account="prepared-evidence-"+UUID.randomUUID();f.q.f.jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('证据权限验证',?,'not-a-login-credential',?)",account,role);
        int id=f.q.f.jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);users.add(id);return id;}
    void forbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call){assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(403);}

    @Test void unendedAnswerHasNoInventedDeclarationEmptyAudioOrPermissionToFinish(){
        var v=read(0,20);assertThat(v.actorId()).isEqualTo(f.q.f.actor);assertThat(v.studentId()).isEqualTo(f.q.student);
        assertThat(v.attemptId()).isEqualTo(f.q.attempt.id());assertThat(v.answerId()).isEqualTo(answer);assertThat(v.current()).isTrue();
        assertThat(v.answerState()).isEqualTo("RECORDING");assertThat(v.declaration()).isNull();assertThat(v.segments()).isEmpty();assertThat(v.nextSegmentOrdinal()).isNull();
        assertThat(v.startedAt()).isEqualTo(f.q.now.get().toString());assertThat(v.answerDeadline()).isEqualTo(f.q.now.get().plusSeconds(120).toString());
        assertThat(v.question().body()).isEqualTo("题目 1");assertThat(v.question().rubric()).startsWith("不应泄漏的教师标准");
        assertThat(f.count("prepared_answer_end")).isZero();assertThat(f.count("prepared_submission")).isZero();
        assertThatThrownBy(()->read(1,20)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
    }
    @Test void originalInterruptionIsReadableWithoutCloudReceiptScoreMutationOrClearingItsReviewFlag()throws Exception{
        var original=close(interrupted(2));int audit=f.count("prepared_audit"),jobs=f.count("prepared_job");
        var v=read(0,20);assertThat(v.answerState()).isEqualTo("REQUIRES_REVIEW");assertThat(v.declaration().reviewReasons()).containsExactly("CAPTURE_INTERRUPTED");
        assertThat(v.declaration().manifestSha256()).isEqualTo(original.manifestSha256());assertThat(v.declaration().byteSize()).isEqualTo(6);
        assertThat(v.declaration().chunkCount()).isEqualTo(2);assertThat(v.declaration().segmentCount()).isEqualTo(2);
        assertThat(v.declaration().effectiveEndedAt()).isEqualTo(original.effectiveEndedAt());assertThat(v.declaration().acceptedAt()).isEqualTo(original.acceptedAt());
        assertThat(v.segments()).hasSize(2);assertThat(v.segments().getFirst().interruption()).isEqualTo("DEVICE_ENDED");
        String json=new ObjectMapper().writeValueAsString(v);assertThat(json).doesNotContain("objectKey","captureClientId",f.q.client,"chunks", "serverSaved", "submissionReceipt", "url", "a".repeat(64));
        assertThat(read(0,20)).isEqualTo(v);assertThat(f.count("prepared_audit")).isEqualTo(audit);assertThat(f.count("prepared_job")).isEqualTo(jobs);
        assertThat(f.count("prepared_answer_chunk")).isZero();assertThat(f.count("prepared_submission")).isZero();
        assertThat(f.q.f.jdbc.queryForObject("SELECT score FROM student_assessment_record WHERE assessment_id=?",Integer.class,f.q.f.assessment)).isNull();
        f.conflict(()->f.q.grant(2));assertThat(f.q.grant(1).mayRecord()).isFalse();
        assertThatThrownBy(()->v.segments().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->v.declaration().reviewReasons().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void boundedSegmentKeysetsPreserveTheSameOriginalDeclarationAndCannotUseForeignCursor(){
        close(interrupted(5));var first=read(0,2);var second=read(first.nextSegmentOrdinal(),2);var last=read(second.nextSegmentOrdinal(),2);
        assertThat(first.segments()).extracting(PreparedAnswerEvidenceService.SegmentEvidence::ordinal).containsExactly(1,2);assertThat(first.nextSegmentOrdinal()).isEqualTo(2);
        assertThat(second.segments()).extracting(PreparedAnswerEvidenceService.SegmentEvidence::ordinal).containsExactly(3,4);assertThat(last.segments()).extracting(PreparedAnswerEvidenceService.SegmentEvidence::ordinal).containsExactly(5);
        assertThat(last.nextSegmentOrdinal()).isNull();assertThat(second.declaration()).isEqualTo(first.declaration());assertThat(read(5,2).segments()).isEmpty();
        assertThatThrownBy(()->read(6,2)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
    }
    @Test void emptyInterruptedDeclarationIsExplicitlyUnknownNotZeroGradeOrCompleteAudio(){
        close(new Manifest(1,List.of(new Segment(UUID.randomUUID().toString(),1,"audio/webm",1000L,2000L,"INTERRUPTED","LOCAL_WRITE_FAILED",0,0,List.of()))));
        var v=read(0,20);assertThat(v.declaration().reviewReasons()).containsExactly("CAPTURE_INTERRUPTED","EMPTY_AUDIO");assertThat(v.declaration().byteSize()).isZero();assertThat(v.segments().getFirst().chunkCount()).isZero();
        assertThat(f.count("prepared_submission")).isZero();
    }
    @Test void normalEndingIsStillOnlyAnOriginalMetadataDeclaration(){
        close(f.manifest());var v=read(0,20);assertThat(v.declaration().state()).isEqualTo("UPLOAD_PENDING");assertThat(v.declaration().reviewReasons()).isEmpty();
        assertThat(v.segments().getFirst().state()).isEqualTo("SEALED");assertThat(v.segments().getFirst().interruption()).isNull();assertThat(f.count("prepared_answer_chunk")).isZero();
    }
    @Test void historicalEvidenceRemainsReadOnlyAfterCurrentAnchorChangesAndStudentAssignmentIsRevoked(){
        close(interrupted(1));String next=UUID.randomUUID().toString();var q=f.q;
        q.f.jdbc.update("INSERT INTO prepared_attempt(id,record_id,assessment_id,student_id,attempt_no,paper_version_id,started_at) VALUES (?,?,?,?,2,?,?)",next,q.attempt.recordId(),q.f.assessment,q.student,q.attempt.paperVersionId(),java.sql.Timestamp.from(q.now.get()));historical.add(next);
        q.f.jdbc.update("UPDATE prepared_attempt_anchor SET active_attempt_id=? WHERE assessment_id=? AND student_id=?",next,q.f.assessment,q.student);
        q.f.jdbc.update("DELETE FROM assessment_student_relation WHERE assessment_id=? AND student_id=?",q.f.assessment,q.student);
        var v=read(0,20);assertThat(v.current()).isFalse();assertThat(v.answerId()).isEqualTo(answer);assertThat(v.declaration().reviewReasons()).containsExactly("CAPTURE_INTERRUPTED");
        assertThat(q.f.jdbc.queryForObject("SELECT active_attempt_id FROM prepared_attempt_anchor WHERE assessment_id=?",String.class,q.f.assessment)).isEqualTo(next);
    }
    @Test void authorizationIsRecheckedForStudentsUnrelatedTeachersInvitedTeachersAdminAndRevocation(){
        close(interrupted(1));var q=f.q;int unrelated=user(1),admin=user(2);
        forbidden(()->service.read(q.student,q.f.assessment,q.attempt.id(),answer,0,20));forbidden(()->service.read(unrelated,q.f.assessment,q.attempt.id(),answer,0,20));
        q.f.jdbc.update("INSERT INTO assessment_invite(assessment_id,invitee_teacher_id,inviter_teacher_id) VALUES (?,?,?)",q.f.assessment,unrelated,q.f.actor);
        assertThat(service.read(unrelated,q.f.assessment,q.attempt.id(),answer,0,20).actorId()).isEqualTo(unrelated);
        q.f.jdbc.update("DELETE FROM assessment_invite WHERE assessment_id=?",q.f.assessment);forbidden(()->service.read(unrelated,q.f.assessment,q.attempt.id(),answer,0,20));
        assertThat(service.read(admin,q.f.assessment,q.attempt.id(),answer,0,20).actorId()).isEqualTo(admin);
        q.f.jdbc.update("UPDATE `user` SET type=0 WHERE id=?",q.f.actor);try{forbidden(()->read(0,20));}finally{q.f.jdbc.update("UPDATE `user` SET type=1 WHERE id=?",q.f.actor);}
        q.f.jdbc.update("UPDATE assessment SET deleted=1 WHERE id=?",q.f.assessment);try{forbidden(()->read(0,20));}finally{q.f.jdbc.update("UPDATE assessment SET deleted=0 WHERE id=?",q.f.assessment);}
    }
    @Test void unknownAttemptAnswerAndOtherAssessmentCannotReadOrRebindEvidence(){
        var q=f.q;forbidden(()->service.read(q.f.actor,q.f.assessment,UUID.randomUUID().toString(),answer,0,20));
        forbidden(()->service.read(q.f.actor,q.f.assessment,q.attempt.id(),UUID.randomUUID().toString(),0,20));
        forbidden(()->service.read(q.f.actor,Integer.MAX_VALUE,q.attempt.id(),answer,0,20));
        assertThat(f.count("prepared_answer_end")).isZero();
    }
    @Test void invalidBoundsAndIdentitiesFailBeforeAccessOrQueries(){
        var q=f.q;reset(q.f.access);
        for(int[] bounds:List.of(new int[]{-1,20},new int[]{1001,20},new int[]{0,0},new int[]{0,51}))assertThatThrownBy(()->read(bounds[0],bounds[1])).isInstanceOf(IllegalArgumentException.class);
        for(String bad:Arrays.asList(null,"bad",q.attempt.id().toUpperCase()))assertThatThrownBy(()->service.read(q.f.actor,q.f.assessment,bad,answer,0,20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.read(0,q.f.assessment,q.attempt.id(),answer,0,20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.read(q.f.actor,0,q.attempt.id(),answer,0,20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.read(q.f.actor,q.f.assessment,q.attempt.id(),"bad",0,20)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(q.f.access);
    }
    @ParameterizedTest @ValueSource(strings={"hash","bytes","reasons","state","revision","accepted","ended","json"})
    void corruptedDeclarationCannotBePresentedAsTrustworthyOriginalEvidence(String field){
        close(interrupted(1));var jdbc=f.q.f.jdbc;
        switch(field){
            case "hash"->jdbc.update("UPDATE prepared_answer_end SET manifest_sha256=? WHERE answer_id=?","c".repeat(64),answer);
            case "bytes"->jdbc.update("UPDATE prepared_answer_end SET byte_size=4 WHERE answer_id=?",answer);
            case "reasons"->jdbc.update("UPDATE prepared_answer_end SET review_reasons='[]' WHERE answer_id=?",answer);
            case "state"->jdbc.update("UPDATE prepared_answer_end SET declaration_state='UPLOAD_PENDING' WHERE answer_id=?",answer);
            case "revision"->jdbc.update("UPDATE prepared_answer_end SET answer_revision=2 WHERE answer_id=?",answer);
            case "accepted"->jdbc.update("UPDATE prepared_answer_end SET accepted_at=TIMESTAMPADD(SECOND,1,accepted_at) WHERE answer_id=?",answer);
            case "ended"->jdbc.update("UPDATE prepared_answer_end SET effective_ended_at=TIMESTAMPADD(SECOND,-1,effective_ended_at) WHERE answer_id=?",answer);
            case "json"->jdbc.update("UPDATE prepared_answer_end SET manifest_json='{}' WHERE answer_id=?",answer);
            default->throw new AssertionError();
        }
        assertThatThrownBy(()->read(0,20)).isInstanceOf(IllegalStateException.class);assertThat(f.count("prepared_answer_end")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"snapshot","paper","grant","missing_end","recording_with_end"})
    void damagedFrozenQuestionOrOriginalGrantFailsClosedWithoutRemovingEvidence(String field){
        close(interrupted(1));var q=f.q;
        switch(field){
            case "snapshot"->q.f.jdbc.update("UPDATE prepared_question_snapshot SET body='changed' WHERE paper_version_id=? AND ordinal=1",q.attempt.paperVersionId());
            case "paper"->q.f.jdbc.update("UPDATE prepared_paper_version SET content_sha256=? WHERE id=?","c".repeat(64),q.attempt.paperVersionId());
            case "grant"->q.f.jdbc.update("UPDATE prepared_answer SET started_at=TIMESTAMPADD(SECOND,-1,started_at),answer_deadline=TIMESTAMPADD(SECOND,-1,answer_deadline) WHERE id=?",answer);
            case "missing_end"->{q.f.jdbc.update("DELETE FROM prepared_answer_end_receipt WHERE answer_id=?",answer);q.f.jdbc.update("DELETE FROM prepared_answer_end WHERE answer_id=?",answer);}
            case "recording_with_end"->q.f.jdbc.update("UPDATE prepared_answer SET state='RECORDING' WHERE id=?",answer);
            default->throw new AssertionError();
        }
        assertThatThrownBy(()->read(0,20)).isInstanceOf(IllegalStateException.class);
    }
}
