package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.flywaydb.core.Flyway;
import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.audio.*;
import system.assessment.defense.prepared.jobs.*;
import system.assessment.defense.prepared.jobs.PreparedJobLedger.*;
import system.assessment.defense.prepared.paper.*;
import system.assessment.defense.prepared.publication.*;
import system.assessment.defense.prepared.speech.*;
import system.assessment.defense.prepared.storage.*;
import system.assessment.defense.prepared.media.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real independent MySQL commands/receipts. Media is explicitly synthetic, never cloud acceptance. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedAudioLiveTest {
    @TempDir Path folder;
    JdbcTemplate jdbc;TransactionTemplate tx;PreparedJobLedger jobs;PreparedPaperRepository drafts;PreparedPaperService papers;
    PreparedAudioRepository repository;PreparedAudioService service;PreparedTtsPolicy policy;PreparedStoragePolicy storage;
    ResourceAccessControlService access;PreparedSpeechSpool spool;int actor,assessment;String qid=UUID.randomUUID().toString();
    @BeforeEach void setup() throws Exception {
        folder=folder.toRealPath();var ds=new DriverManagerDataSource(PreparedLiveDatabase.jdbcUrl(),
                Objects.requireNonNull(System.getenv("PREPARED_DB_USERNAME")),Objects.requireNonNull(System.getenv("PREPARED_DB_PASSWORD")));
        jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("prepared_interview");
        Flyway.configure().dataSource(ds).cleanDisabled(true).load().migrate();var manager=new JdbcTransactionManager(ds);tx=new TransactionTemplate(manager);jobs=new PreparedJobLedger(jdbc,manager);
        String account="prepared-audio-"+UUID.randomUUID();jdbc.update("INSERT INTO `user`(name,account,password,type) VALUES ('合成播报测试',?,'not-a-login-credential',1)",account);
        actor=jdbc.queryForObject("SELECT id FROM `user` WHERE account=?",Integer.class,account);
        jdbc.update("INSERT INTO assessment(theme,create_id,deleted,question_count,total_score,pass_score) VALUES ('合成播报独立测试',?,0,1,10,6)",actor);
        assessment=jdbc.queryForObject("SELECT id FROM assessment WHERE create_id=?",Integer.class,actor);
        access=mock(ResourceAccessControlService.class);drafts=new PreparedPaperRepository(jdbc);papers=new PreparedPaperService(access,drafts);
        policy=new PreparedTtsAdapterTest().policy();var env=new MockEnvironment().withProperty("prepared.storage.enabled","true")
                .withProperty("prepared.storage.endpoint","https://oss-cn-beijing.aliyuncs.com").withProperty("prepared.storage.bucket","prepared-interview-fixture")
                .withProperty("prepared.storage.access-key-id","fixture-id").withProperty("prepared.storage.access-key-secret","fixture-secret")
                .withProperty("prepared.deployment-id","prepared-fixture").withProperty("prepared.speech.assembly-worker-enabled","true").withProperty("prepared.speech.spool-root",folder.resolve("prepared-tts-spool").toString());
        storage=new PreparedStoragePolicy(env);spool=new PreparedSpeechSpool(env);repository=new PreparedAudioRepository(jdbc);
        service=new PreparedAudioService(access,drafts,new PreparedPublicationRepository(jdbc),repository,jobs,policy,storage,spool);
        tx.execute(s->papers.save(actor,assessment,0,List.of(new PreparedPaper.QuestionInput(qid,"题目。".repeat(220),10,120,"教师标准"))));
        tx.execute(s->papers.approve(actor,assessment,1,List.of(qid)));
    }
    @AfterEach void cleanOwnedRows() {
        if(jdbc==null)return;
        jdbc.update("DELETE FROM prepared_audio_command WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_audio_part_ref WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_audio_part WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_audio_asset WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_job_dependency WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_job WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_question_review WHERE assessment_id=?",assessment);jdbc.update("DELETE FROM prepared_question_draft WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM prepared_audit WHERE assessment_id=?",assessment);jdbc.update("DELETE FROM prepared_paper_draft WHERE assessment_id=?",assessment);
        jdbc.update("DELETE FROM assessment WHERE id=?",assessment);jdbc.update("DELETE FROM `user` WHERE id=?",actor);
    }
    PreparedAudio.Command command(){return new PreparedAudio.Command(drafts.load(assessment).revision(),UUID.randomUUID().toString(),qid,service.list(actor,assessment).availability().confirmationSha256());}
    PreparedAudio.View prepare(){return tx.execute(s->service.prepare(actor,assessment,command()));}
    PreparedSpeech.PartOutput completePart(String jobId) throws Exception {
        Lease lease=jobs.claim(Kind.TTS,"fixture-speech",300).orElseThrow();assertThat(lease.job().id()).isEqualTo(jobId);
        var input=PreparedSpeech.part(lease.job().inputJson());Path p=folder.resolve(UUID.randomUUID()+".wav");Files.write(p,new PreparedSpeechMediaTest().wave());
        var scope=new PreparedPrivateOssStore.Broadcast(assessment,qid,input.assetId());
        var output=new PreparedSpeech.PartOutput(1,qid,input.assetId(),scope.key("prepared-fixture"),PreparedWaveAudio.inspect(p));
        assertThat(jobs.succeed(lease,PreparedSpeech.encode(output))).isTrue();return output;
    }
    void completeAllParts(PreparedAudio.View asset) throws Exception {
        while(repository.get(assessment,asset.id()).orElseThrow().view().completedParts()<asset.partCount()) {
            Lease lease=jobs.claim(Kind.TTS,"fixture-speech",300).orElseThrow();var in=PreparedSpeech.part(lease.job().inputJson());
            Path p=folder.resolve(UUID.randomUUID()+".wav");Files.write(p,new PreparedSpeechMediaTest().wave());var scope=new PreparedPrivateOssStore.Broadcast(assessment,qid,in.assetId());
            assertThat(jobs.succeed(lease,PreparedSpeech.encode(new PreparedSpeech.PartOutput(1,qid,in.assetId(),scope.key("prepared-fixture"),PreparedWaveAudio.inspect(p))))).isTrue();
        }
    }
    @Test void commandAtomicallySplitsApprovedTextAndQueuesOneCompleteAssembly() {
        var v=prepare();assertThat(v.partCount()).isEqualTo(2);assertThat(v.newPartCount()).isEqualTo(2);assertThat(v.state()).isEqualTo("GENERATING");
        assertThat(v.maximumNewCostCny()).isEqualByComparingTo("0.096");assertThat(jobs.claim(Kind.ASSEMBLE,"fixture-assembly",300)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=? AND kind='TTS'",Integer.class,assessment)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job_dependency WHERE job_id=?",Integer.class,v.jobId())).isEqualTo(2);
        assertThat(drafts.load(assessment).questions().getFirst().audioState()).isNotEqualTo("READY");
    }
    @Test void hundredSameKeyRetriesAndConfigChangesReturnTheOriginalCommand() {
        var c=command();var first=tx.execute(s->service.prepare(actor,assessment,c));
        for(int i=0;i<100;i++)assertThat(tx.execute(s->service.prepare(actor,assessment,c)).id()).isEqualTo(first.id());
        service=new PreparedAudioService(access,drafts,new PreparedPublicationRepository(jdbc),repository,jobs,new PreparedTtsPolicy(new MockEnvironment()),storage,spool);
        assertThat(tx.execute(s->service.prepare(actor,assessment,c)).id()).isEqualTo(first.id());
        assertThatThrownBy(()->tx.execute(s->service.prepare(actor,assessment,new PreparedAudio.Command(3L,c.idempotencyKey(),qid,c.expectedConfigurationSha256())))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audio_asset WHERE assessment_id=?",Integer.class,assessment)).isEqualTo(1);
    }
    @Test void noPermissionUnavailableConfigStaleRevisionOrUnreviewedTextNeverCreatesAPaidJob() {
        var c=command();doThrow(new BusinessException(403,"拒绝")).when(access).requireAssessmentManager(actor,assessment);
        assertThatThrownBy(()->service.list(actor,assessment)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->tx.execute(s->service.prepare(actor,assessment,c))).isInstanceOf(BusinessException.class);reset(access);
        assertThatThrownBy(()->tx.execute(s->service.prepare(actor,assessment,new PreparedAudio.Command(0L,c.idempotencyKey(),qid,c.expectedConfigurationSha256())))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->tx.execute(s->service.prepare(actor,assessment,new PreparedAudio.Command(2L,c.idempotencyKey(),qid,"0".repeat(64))))).isInstanceOf(BusinessException.class);
        tx.execute(s->papers.save(actor,assessment,2,List.of(new PreparedPaper.QuestionInput(qid,"未经审核的新题",10,120,"标准"))));
        assertThatThrownBy(()->prepare()).isInstanceOf(BusinessException.class);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=?",Integer.class,assessment)).isZero();
    }
    @Test void activePreparationCannotBeDuplicatedUnderAnotherKey() {
        prepare();assertThatThrownBy(this::prepare).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_audio_asset WHERE assessment_id=?",Integer.class,assessment)).isEqualTo(1);
    }
    @Test void disabledComposerCannotAcceptAnEventuallyStuckPaidPreparation(){
        var command=command();var unavailable=new PreparedSpeechSpool(new MockEnvironment().withProperty("prepared.speech.spool-root",folder.resolve("prepared-tts-spool").toString()));
        service=new PreparedAudioService(access,drafts,new PreparedPublicationRepository(jdbc),repository,jobs,policy,storage,unavailable);
        assertThat(service.list(actor,assessment).availability().enabled()).isFalse();
        assertThatThrownBy(()->tx.execute(s->service.prepare(actor,assessment,command))).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=?",Integer.class,assessment)).isZero();
    }
    @Test void previewRequiresReadyCompleteProofAndCannotCrossAnAssessment() throws Exception{
        var asset=prepare();assertThatThrownBy(()->service.previewAsset(actor,assessment,asset.id())).isInstanceOf(BusinessException.class);
        completeAllParts(asset);var lease=jobs.claim(Kind.ASSEMBLE,"fixture-composer",300).orElseThrow();tx.executeWithoutResult(s->service.complete(lease,wholeUnchecked(asset)));
        assertThat(service.previewAsset(actor,assessment,asset.id()).scope().assetId()).isEqualTo(asset.id());
        assertThatThrownBy(()->service.previewAsset(actor,assessment+1000000,asset.id())).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE prepared_audio_asset SET sample_rate=NULL WHERE id=?",asset.id());
        assertThatThrownBy(()->service.previewAsset(actor,assessment,asset.id())).isInstanceOf(BusinessException.class);
    }
    @Test void compositionReadsOrderedPersistedProofAndRejectsCorruptOrForeignReferences() throws Exception{
        var asset=prepare();completeAllParts(asset);var lease=jobs.claim(Kind.ASSEMBLE,"fixture-composer",300).orElseThrow();
        var proofs=service.assemblyParts(lease);assertThat(proofs).hasSize(2);assertThat(proofs).allMatch(p->p.scope().assessmentId()==assessment && p.scope().questionId().equals(qid));
        String child=repository.partJobs(assessment,asset.id()).getFirst();String original=jobs.get(child).orElseThrow().outputJson();
        jdbc.update("UPDATE prepared_job SET output_json='{}' WHERE id=?",child);assertThatThrownBy(()->service.assemblyParts(lease)).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE prepared_job SET output_json=? WHERE id=?",original,child);jdbc.update("UPDATE prepared_job SET state='NEEDS_REVIEW' WHERE id=?",child);
        assertThatThrownBy(()->service.assemblyParts(lease)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void retryReusesSuccessfulPartsAndStopsOldUnsentPartsWithoutDuplicatingCharges() throws Exception {
        var original=prepare();var first=jobs.claim(Kind.TTS,"fixture-part",300).orElseThrow();var input=PreparedSpeech.part(first.job().inputJson());
        Path p=folder.resolve("reused.wav");Files.write(p,new PreparedSpeechMediaTest().wave());var scope=new PreparedPrivateOssStore.Broadcast(assessment,qid,input.assetId());
        jobs.succeed(first,PreparedSpeech.encode(new PreparedSpeech.PartOutput(1,qid,input.assetId(),scope.key("prepared-fixture"),PreparedWaveAudio.inspect(p))));
        jdbc.update("UPDATE prepared_job SET state='NEEDS_REVIEW',error_code='AUDIO_SAVE_FAILED' WHERE id=?",original.jobId());
        var retry=prepare();assertThat(retry.id()).isNotEqualTo(original.id());assertThat(retry.newPartCount()).isEqualTo(1);
        assertThat(retry.maximumNewCostCny()).isEqualByComparingTo("0.048");
        assertThat(repository.get(assessment,original.id()).orElseThrow().storedState()).isEqualTo("FAILED");
        assertThat(repository.partJobs(assessment,retry.id())).contains(first.job().id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=? AND kind='TTS' AND state='QUEUED'",Integer.class,assessment)).isEqualTo(1);
        assertThat(repository.partIsWanted(first.job().id())).isTrue();
    }
    @Test void assemblyRecoveryIsZeroNewPaidJobsAndWorksWithModelsDisabled() throws Exception {
        var original=prepare();completeAllParts(original);var lease=jobs.claim(Kind.ASSEMBLE,"fixture-composer",300).orElseThrow();jobs.fail(lease,"ASSEMBLY_SAVE_FAILED",false);
        service=new PreparedAudioService(access,drafts,new PreparedPublicationRepository(jdbc),repository,jobs,new PreparedTtsPolicy(new MockEnvironment()),storage,spool);
        var c=new PreparedAudio.Recovery(2L,UUID.randomUUID().toString());var recovered=tx.execute(s->service.recover(actor,assessment,original.id(),c));
        assertThat(recovered.newPartCount()).isZero();assertThat(recovered.maximumNewCostCny()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prepared_job WHERE assessment_id=? AND kind='TTS'",Integer.class,assessment)).isEqualTo(2);
        assertThat(tx.execute(s->service.recover(actor,assessment,original.id(),c)).id()).isEqualTo(recovered.id());
        assertThatThrownBy(()->tx.execute(s->service.recover(actor,assessment,original.id(),new PreparedAudio.Recovery(3L,c.idempotencyKey())))).isInstanceOf(BusinessException.class);
    }
    @Test void verifiedWholeCompletionAtomicallyAttachesDraftAudioAndKeepsTheVersionedBody() throws Exception {
        var asset=prepare();completeAllParts(asset);var lease=jobs.claim(Kind.ASSEMBLE,"fixture-composer",300).orElseThrow();
        var saved=whole(asset);tx.executeWithoutResult(s->service.complete(lease,saved));
        assertThat(service.get(actor,assessment,asset.id()).state()).isEqualTo("READY");assertThat(jobs.get(asset.jobId()).orElseThrow().state()).isEqualTo(State.SUCCEEDED);
        assertThat(drafts.load(assessment).revision()).isEqualTo(3);assertThat(drafts.load(assessment).questions().getFirst().audioObjectKey()).isEqualTo(saved.objectKey());
        var newCommand=command();assertThat(tx.execute(s->service.prepare(actor,assessment,newCommand)).id()).isEqualTo(asset.id());
        assertThat(drafts.load(assessment).revision()).isEqualTo(3);
    }
    @Test void lateOldAudioNeverOverwritesEditedQuestionAndExpiredLeaseCannotPublishProof() throws Exception {
        var asset=prepare();completeAllParts(asset);var lease=jobs.claim(Kind.ASSEMBLE,"fixture-composer",300).orElseThrow();
        tx.execute(s->papers.save(actor,assessment,2,List.of(new PreparedPaper.QuestionInput(qid,"修改后的题目",10,120,"新的标准"))));
        tx.executeWithoutResult(s->service.complete(lease,wholeUnchecked(asset)));
        assertThat(drafts.load(assessment).revision()).isEqualTo(3);assertThat(drafts.load(assessment).questions().getFirst().audioObjectKey()).isNull();
        assertThat(service.get(actor,assessment,asset.id()).state()).isEqualTo("READY");
        jdbc.update("UPDATE prepared_audio_asset SET state='BUILDING' WHERE id=?",asset.id());
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.complete(lease,wholeUnchecked(asset)))).isInstanceOf(BusinessException.class);
        assertThat(repository.get(assessment,asset.id()).orElseThrow().storedState()).isEqualTo("BUILDING");
    }
    PreparedPrivateOssStore.Stored whole(PreparedAudio.View asset) throws Exception {
        Path p=folder.resolve(asset.id()+".wav");Files.write(p,new PreparedSpeechMediaTest().wave());var scope=new PreparedPrivateOssStore.Broadcast(assessment,qid,asset.id());
        return new PreparedPrivateOssStore.Stored(scope,scope.key("prepared-fixture"),PreparedWaveAudio.inspect(p));
    }
    PreparedPrivateOssStore.Stored wholeUnchecked(PreparedAudio.View asset){try{return whole(asset);}catch(Exception e){throw new IllegalStateException(e);}}
    @Test void unfinishedOrUnreviewedOrDeletedQuestionCannotUseFreeAssemblyRecovery() throws Exception{
        var asset=prepare();var c=new PreparedAudio.Recovery(2L,UUID.randomUUID().toString());
        assertThatThrownBy(()->tx.execute(s->service.recover(actor,assessment,asset.id(),c))).isInstanceOf(BusinessException.class);
        completeAllParts(asset);var lease=jobs.claim(Kind.ASSEMBLE,"fixture-composer",300).orElseThrow();jobs.fail(lease,"ASSEMBLY_SAVE_FAILED",false);
        jdbc.update("UPDATE prepared_question_draft SET approved=FALSE WHERE assessment_id=?",assessment);
        assertThatThrownBy(()->tx.execute(s->service.recover(actor,assessment,asset.id(),c))).isInstanceOf(BusinessException.class);
        jdbc.update("DELETE FROM prepared_question_draft WHERE assessment_id=?",assessment);
        assertThatThrownBy(()->tx.execute(s->service.recover(actor,assessment,asset.id(),c))).isInstanceOf(BusinessException.class);
        assertThat(repository.get(assessment,asset.id()).orElseThrow().storedState()).isEqualTo("BUILDING");
    }
    @Test void aRunningOldPartCannotBeDuplicatedAfterItsWholeTaskFails(){
        var asset=prepare();var part=jobs.claim(Kind.TTS,"fixture-part",300).orElseThrow();
        jdbc.update("UPDATE prepared_job SET state='NEEDS_REVIEW' WHERE id=?",asset.jobId());
        assertThatThrownBy(this::prepare).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThat(repository.get(assessment,asset.id()).orElseThrow().storedState()).isEqualTo("BUILDING");assertThat(jobs.get(part.job().id()).orElseThrow().state()).isEqualTo(State.RUNNING);
    }
}
