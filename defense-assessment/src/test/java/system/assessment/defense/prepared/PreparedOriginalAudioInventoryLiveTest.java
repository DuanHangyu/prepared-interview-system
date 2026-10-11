package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import system.assessment.defense.prepared.retention.*;
import system.assessment.defense.prepared.attempt.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Native MySQL and original metadata. Explicit media/provider doubles are NOT
 * proof of real cloud inventory, all backups, physical capture or deletion. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedOriginalAudioInventoryLiveTest {
    final PreparedRetentionEvidenceLiveTest f=new PreparedRetentionEvidenceLiveTest();
    @TempDir Path folder;
    PreparedOriginalAudioInventory inventory;
    @BeforeEach void setup()throws Exception{f.folder=folder;f.setup();inventory=new PreparedOriginalAudioInventory(f.p.f.service,f.p.jdbc);}
    @AfterEach void cleanup(){f.cleanup();}
    PreparedOriginalAudioInventory.Snapshot read(){return f.p.f.m.f.f.ends.q.f.tx.execute(s->inventory.read(f.p.student,f.p.assessment,f.p.attempt));}

    @RepeatedTest(3) void batchLogicalChunksProduceOnePhysicalBundleAndOneWholeFilePerAnswerWithoutChangingResultsOrContent(){
        f.completed();var before=f.rows();int io=org.mockito.Mockito.mockingDetails(f.p.f.m.f.f.store).getInvocations().size();var plan=read();
        assertThat(plan.objects()).hasSize(6);assertThat(plan.objects().stream().map(PreparedOriginalAudioInventory.Item::kind)).containsExactly("BUNDLE","ANSWER","BUNDLE","ANSWER","BUNDLE","ANSWER");
        assertThat(plan.objects().stream().map(PreparedOriginalAudioInventory.Item::key)).doesNotHaveDuplicates().allMatch(k->!k.contains("/chunks/")&&!k.contains("/questions/"));
        assertThat(plan.sha256()).matches("[a-f0-9]{64}");assertThat(plan.byteSize()).isGreaterThan(0);
        inventory=new PreparedOriginalAudioInventory(f.p.f.service,f.p.jdbc);assertThat(read()).isEqualTo(plan);
        assertThat(plan.toString()).doesNotContain("prepared/prepared-","/students/","objectKey","fixture-secret");
        for(var object:plan.objects())assertThat(object.toString()).doesNotContain(object.key());
        assertThatThrownBy(()->plan.objects().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(f.rows()).isEqualTo(before);assertThat(org.mockito.Mockito.mockingDetails(f.p.f.m.f.f.store).getInvocations()).hasSize(io);
    }
    @Test void aStandaloneOriginalIsNotConfusedWithABundleOrAnUnusedLogicalChunkKey(){
        f.completed();f.rollback(()->{
            // Independent physical-layout fixture, not a real cloud rewrite.
            f.p.jdbc.update("UPDATE prepared_answer_chunk SET bundle_id=NULL,bundle_offset=NULL WHERE answer_id=?",f.answer());
            f.p.jdbc.update("DELETE FROM prepared_answer_upload_bundle WHERE answer_id=?",f.answer());
            var plan=read();assertThat(plan.objects()).hasSize(7);
            assertThat(plan.objects().stream().filter(v->v.kind().equals("CHUNK"))).hasSize(2);
            assertThat(plan.objects().stream().filter(v->v.kind().equals("CHUNK"))).allMatch(v->v.key().contains("/chunks/")&&v.byteSize()>0);
        });
    }
    @ParameterizedTest @ValueSource(strings={"chunk-missing","chunk-key","chunk-deployment","chunk-unconfirmed","chunk-offset","bundle-missing","bundle-key","bundle-json","bundle-digest","bundle-unconfirmed","bundle-confirm-time","segment-extra","segment-mime","end-json","media-deployment"})
    void damagedOrIncompleteOriginalObjectBindingsFailClosedRatherThanReturningAPartialList(String damage){
        f.completed();f.rollback(()->{
            String answer=f.answer();String sql=switch(damage){
                case "chunk-missing"->"DELETE FROM prepared_answer_chunk WHERE answer_id=? AND chunk_index=0";
                case "chunk-key"->"UPDATE prepared_answer_chunk SET object_key='arbitrary-private-key' WHERE answer_id=?";
                case "chunk-deployment"->"UPDATE prepared_answer_chunk SET deployment_id='prepared-other' WHERE answer_id=?";
                case "chunk-unconfirmed"->"UPDATE prepared_answer_chunk SET confirmed_at=NULL WHERE answer_id=?";
                case "chunk-offset"->"UPDATE prepared_answer_chunk SET bundle_offset=bundle_offset+1 WHERE answer_id=?";
                case "bundle-missing"->"DELETE FROM prepared_answer_upload_bundle WHERE answer_id=?";
                case "bundle-key"->"UPDATE prepared_answer_upload_bundle SET object_key='arbitrary-private-key' WHERE answer_id=?";
                case "bundle-json"->"UPDATE prepared_answer_upload_bundle SET command_json='{}' WHERE answer_id=?";
                case "bundle-digest"->"UPDATE prepared_answer_upload_bundle SET command_sha256=REPEAT('f',64) WHERE answer_id=?";
                case "bundle-unconfirmed"->"UPDATE prepared_answer_upload_bundle SET confirmed_at=NULL WHERE answer_id=?";
                case "bundle-confirm-time"->"UPDATE prepared_answer_upload_bundle SET confirmed_at=DATE_ADD(confirmed_at,INTERVAL 1 SECOND) WHERE answer_id=?";
                case "segment-mime"->"UPDATE prepared_answer_segment SET mime_type='audio/mp4' WHERE answer_id=?";
                case "end-json"->"UPDATE prepared_answer_end SET manifest_json='{}' WHERE answer_id=?";
                case "media-deployment"->"UPDATE prepared_answer_media SET deployment_id='prepared-other' WHERE answer_id=?";
                case "segment-extra"->null;
                default->throw new AssertionError();
            };
            if(damage.equals("bundle-missing"))f.p.jdbc.update("DELETE FROM prepared_answer_chunk WHERE answer_id=?",answer);
            if(sql!=null)f.p.jdbc.update(sql,answer);
            else f.p.jdbc.update("INSERT INTO prepared_answer_segment(id,assessment_id,student_id,attempt_id,answer_id,ordinal,mime_type) VALUES (?,?,?,?,?,2,'audio/webm')",UUID.randomUUID().toString(),f.p.assessment,f.p.student,f.p.attempt,answer);
            assertThatThrownBy(this::read).isInstanceOf(RuntimeException.class);
        });assertThat(read().objects()).hasSize(6);
    }
    @Test void unboundExtraBundleAndOtherOriginalCannotLeakIntoTheTargetInventory(){
        f.completed();f.rollback(()->{
            f.otherOriginal();var current=read();assertThat(current.objects()).hasSize(6);
            f.p.jdbc.update("""
                INSERT INTO prepared_answer_upload_bundle(id,assessment_id,student_id,attempt_id,answer_id,command_json,
                    command_sha256,byte_size,sha256,deployment_id,object_key,reserved_at,confirmed_at)
                SELECT REPEAT('e',64),assessment_id,student_id,attempt_id,answer_id,command_json,command_sha256,
                    byte_size,sha256,deployment_id,object_key,reserved_at,confirmed_at FROM prepared_answer_upload_bundle WHERE answer_id=? LIMIT 1
                """,f.answer());
            assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);
        });
    }
    @Test void scopeAndTransactionAreMandatoryAndPendingAttemptCannotInventAnInventory(){
        assertThatThrownBy(()->inventory.read(f.p.student,f.p.assessment,f.p.attempt)).isInstanceOf(IllegalStateException.class);
        // This fixture is already submitted with saved audio but ungraded.
        // Inventory is not a deletion permit: grading protection is separate.
        assertThat(read().objects()).hasSize(6);assertThat(f.inspect().audioDeleteCandidate()).isFalse();
        f.rollback(()->{
            String pending=f.otherOriginal()[0];
            assertThatThrownBy(()->inventory.read(f.p.student,f.p.assessment,pending)).isInstanceOf(IllegalStateException.class);
        });f.completed();var before=f.rows();
        // Expected nested-service rejections mark the ORIGINAL parent transaction
        // rollback-only. Explicit rollback is required; never try to commit it.
        f.rollback(()->{
            assertThatThrownBy(()->inventory.read(0,f.p.assessment,f.p.attempt)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->inventory.read(f.p.student,0,f.p.attempt)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->inventory.read(f.p.student,f.p.assessment,"bad")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->inventory.read(f.p.student+1,f.p.assessment,f.p.attempt)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->inventory.read(f.p.student,f.p.assessment,UUID.randomUUID().toString())).isInstanceOf(RuntimeException.class);
        });
        assertThat(read().objects()).hasSize(6);assertThat(f.rows()).isEqualTo(before);
    }
    @Test void nativeMysqlJsonFormattingDoesNotChangeTheCanonicalOriginalManifestOrItsDigest(){
        f.completed();String stored=f.p.jdbc.queryForObject("SELECT manifest_json FROM prepared_answer_end WHERE answer_id=?",String.class,f.answer());
        String canonical=system.assessment.defense.prepared.jobs.PreparedJobInput.json(stored,2*1024*1024);
        assertThat(stored).isNotEqualTo(canonical);
        assertThat(PreparedAnswerEnd.inspect(PreparedAnswerEnd.decode(stored),120).json()).isEqualTo(canonical);
        assertThat(read().objects()).hasSize(6);
    }
    @Test void duplicatedOriginalSourcesCannotBeMergedIntoASingleApparentlyCompleteObjectList(){
        f.completed();f.p.f.m.f.f.ends.q.f.tx.executeWithoutResult(s->{
            var original=f.p.f.service.retentionSources(f.p.student,f.p.assessment,f.p.attempt);
            var repeated=new ArrayList<>(original.sources());repeated.add(original.sources().getFirst());
            // Deliberately contradictory internal source double. The same real
            // original parent transaction and physical rows remain unchanged.
            var faulty=org.mockito.Mockito.spy(f.p.f.service);
            org.mockito.Mockito.doReturn(new PreparedSubmissionService.ReviewSources(original.attempt(),original.paper(),repeated))
                .when(faulty).retentionSources(f.p.student,f.p.assessment,f.p.attempt);
            var closed=new PreparedOriginalAudioInventory(faulty,f.p.jdbc);
            assertThatThrownBy(()->closed.read(f.p.student,f.p.assessment,f.p.attempt)).isInstanceOf(IllegalStateException.class);
        });assertThat(read().objects()).hasSize(6);
    }
}
