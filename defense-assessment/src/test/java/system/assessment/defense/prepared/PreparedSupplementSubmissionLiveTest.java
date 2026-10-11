package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Original native codec + real MySQL/outbox. Private storage uses explicit SDK doubles; not cloud/device QA. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedSupplementSubmissionLiveTest {
    final PreparedSubmissionLiveTest f=new PreparedSubmissionLiveTest();@TempDir Path folder;
    PreparedSupplementService grants;
    @BeforeEach void setup()throws Exception{
        f.folder=folder;f.setup();var q=f.m.f.f.ends.q;
        grants=new PreparedSupplementService(q.f.access,new PreparedAttemptStartRepository(q.f.jdbc),f.m.f.f.uploads,new PreparedAnswerEndRepository(q.f.jdbc),q.f.jdbc,q.f.tx.getTransactionManager(),
            new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return q.now.get();}});
    }
    @AfterEach void cleanup(){var q=f.m.f.f.ends.q;q.f.jdbc.update("DELETE FROM prepared_supplement_grant WHERE assessment_id=?",q.f.assessment);f.cleanup();}
    void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable work){assertThatThrownBy(work).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);}
    @Test void approvedOriginalsAreActuallyDecodedThenFinallySubmittedOnceWithoutResettingDeadlines()throws Exception {
        var q=f.m.f.f.ends.q;var hashes=new LinkedHashMap<String,String>();var originals=new HashMap<String,byte[]>();f.m.stagedCloudDouble();
        for(int ordinal=1;ordinal<=3;ordinal++){
            if(ordinal>1)f.m.f.f.grant=q.grant(ordinal);f.m.f.f.segment=UUID.randomUUID().toString();f.m.folder=Files.createDirectory(folder.resolve("q"+ordinal));
            String hash=f.m.nativeEnd(),answer=f.m.f.f.grant.answerId();hashes.put(answer,hash);originals.put(answer,f.m.f.f.bytes.clone());
        }
        var original=f.m.f.f.ends.attempt();q.now.set(Instant.parse(original.supplementalUploadDeadline()));conflict(()->f.submit(f.command()));
        doAnswer(i->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var scope=i.getArgument(0,PreparedPrivateOssStore.StoredBundle.class).scope();Files.write(i.getArgument(1,Path.class),originals.get(scope.chunks().getFirst().answerId()),StandardOpenOption.CREATE_NEW);return null;}).when(f.m.f.f.store).downloadAnswerBundle(any(),any());
        for(var entry:hashes.entrySet()){
            var s=grants.status(q.f.actor,q.student,q.f.assessment,q.attempt.id(),entry.getKey());
            var command=new PreparedSupplement.Command(UUID.randomUUID().toString(),s.approvalRevision(),s.attemptRevision(),s.manifestSha256(),q.now.get().plusSeconds(3600).toString(),"听审原清单后批准补传，不允许重答");
            grants.approve(q.f.actor,q.student,q.f.assessment,q.attempt.id(),entry.getKey(),command);
            var queued=f.m.service.request(q.student,q.f.assessment,q.attempt.id(),entry.getKey(),new PreparedAnswerMediaService.Command(q.client,entry.getValue()));assertThat(queued.serverSaved()).isFalse();
            new PreparedAnswerMediaWorker(f.m.jobs,f.m.service,f.m.runtime,f.m.f.f.store,f.m.f.f.policy).runOnce();
            var saved=f.m.service.status(q.student,q.f.assessment,q.attempt.id(),entry.getKey());assertThat(saved.serverSaved()).as("actual native media state %s",saved.phase()).isTrue();
        }
        assertThat(f.status().phase()).isEqualTo("READY");var command=f.command();var submitted=f.submit(command);assertThat(submitted.submitted()).isTrue();
        q.now.set(q.now.get().plusSeconds(86400*30));for(int n=0;n<100;n++)assertThat(f.submit(command)).isEqualTo(submitted);
        assertThat(f.m.f.f.ends.attempt().answeringEndedAt()).isEqualTo(original.answeringEndedAt());assertThat(f.m.f.f.ends.attempt().supplementalUploadDeadline()).isEqualTo(original.supplementalUploadDeadline());
        assertThat(f.m.f.f.ends.count("prepared_submission")).isEqualTo(1);assertThat(f.m.f.f.ends.count("prepared_supplement_grant")).isEqualTo(3);
        assertThat(q.f.jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=? AND kind IN ('TRANSCRIBE','SCORE')",Integer.class,q.f.assessment)).isEqualTo(6);
        assertThat(q.f.jdbc.queryForObject("SELECT requires_supplement_review FROM prepared_attempt WHERE id=?",Boolean.class,q.attempt.id())).isFalse();
    }
    @Test void completeFlagsAndExpiryReviewWithoutAnApprovalCannotBypassManualReview()throws Exception {
        f.savedAll();var q=f.m.f.f.ends.q;
        q.f.jdbc.update("UPDATE prepared_attempt SET state='REQUIRES_REVIEW',requires_supplement_review=1 WHERE id=?",q.attempt.id());
        q.now.set(Instant.parse(f.m.f.f.ends.attempt().supplementalUploadDeadline()));
        var view=grants.status(q.f.actor,q.student,q.f.assessment,q.attempt.id(),f.m.f.f.grant.answerId());assertThat(view.phase()).isEqualTo("COMPLETE");assertThat(view.mayApprove()).isFalse();
        assertThat(f.status().phase()).isEqualTo("REQUIRES_REVIEW");conflict(()->f.submit(f.command()));assertThat(f.m.f.f.ends.count("prepared_submission")).isZero();
    }
}
