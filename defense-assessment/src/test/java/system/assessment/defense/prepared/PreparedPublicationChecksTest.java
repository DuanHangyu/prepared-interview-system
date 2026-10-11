package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import java.util.*;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.paper.PreparedPaper;
import system.assessment.defense.prepared.publication.*;
import static org.assertj.core.api.Assertions.*;

class PreparedPublicationChecksTest {
    final int assessment=42;
    final String id=UUID.randomUUID().toString(), assetId=UUID.randomUUID().toString();
    final String key="prepared/prepared-local/assessments/42/questions/"+id+"/audio/"+assetId+".wav";
    PreparedPaper.Question q(String body, boolean approved, String state, String object) {
        return new PreparedPaper.Question(id,"MANUAL",body,1,10,120,"评分要求",approved,state,object);
    }
    PreparedPublicationRepository.Asset asset(String body, String object) {
        return new PreparedPublicationRepository.Asset(assetId,42,id,PreparedJobInput.digest(body),"a".repeat(64),object,"b".repeat(64),48044,1000,"audio/wav");
    }
    List<PreparedPublication.Issue> issues(PreparedPaper.Question question, PreparedPublication.Rules rules,
                                         PreparedPublicationRepository.Asset audio,boolean frozen) {
        return PreparedPublicationChecks.inspect(new PreparedPaper.Draft(42,3,List.of(question)),rules,frozen,
                (a,q,k)->Optional.ofNullable(audio),"prepared-local").issues();
    }
    PreparedPublication.Rules validRules(){return new PreparedPublication.Rules("面试",10,6,1,false);}
    @Test void verifiedExactAudioAndReviewedQuestionProduceImmutableSnapshot() {
        var question=q("题目",true,"READY",key);
        var check=PreparedPublicationChecks.inspect(new PreparedPaper.Draft(42,3,List.of(question)),validRules(),false,
                (a,q,k)->Optional.of(asset("题目",key)),"prepared-local");
        assertThat(check.issues()).isEmpty();
        assertThat(check.snapshot().questions().getFirst().audio().assetId()).isEqualTo(assetId);
        assertThat(check.snapshot().questions().getFirst().rubric()).isEqualTo("评分要求");
        assertThat(PreparedPublication.decode(PreparedPublication.encode(check.snapshot()))).isEqualTo(check.snapshot());
        assertThatThrownBy(()->check.snapshot().questions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void missingReviewMissingAudioAndLateDifferentTextNeverPass() {
        assertThat(issues(q("题目",false,"PENDING",null),validRules(),null,false)).extracting("code").containsExactly("NOT_APPROVED","AUDIO_NOT_READY");
        assertThat(issues(q("已改题目",true,"READY",key),validRules(),asset("旧题",key),false)).extracting("code").contains("AUDIO_MISMATCH");
        assertThat(issues(q("题目",true,"READY",key),validRules(),null,false)).extracting("code").contains("AUDIO_NOT_VERIFIED");
    }
    @Test void foreignQuestionAssessmentAndArbitraryObjectPathAreRejected() {
        var original=asset("题目",key);
        for(var audio:List.of(
                new PreparedPublicationRepository.Asset(assetId,43,id,original.bodySha256(),original.configSha256(),key,original.contentSha256(),48044,1000,"audio/wav"),
                new PreparedPublicationRepository.Asset(assetId,42,UUID.randomUUID().toString(),original.bodySha256(),original.configSha256(),key,original.contentSha256(),48044,1000,"audio/wav"),
                asset("题目","https://public.invalid/audio.wav"),asset("题目","prepared/prepared-other/assessments/42/questions/"+id+"/audio/"+assetId+".wav"))) {
            assertThat(issues(q("题目",true,"READY",key),validRules(),audio,false)).extracting("code").contains("AUDIO_MISMATCH");
        }
    }
    @Test void malformedHashesMediaAndResourceBoundsAreNotReadyEvidence() {
        var original=asset("题目",key);
        for(var audio:List.of(
                new PreparedPublicationRepository.Asset("not-uuid",42,id,original.bodySha256(),"a".repeat(64),key,"b".repeat(64),48044,1000,"audio/wav"),
                new PreparedPublicationRepository.Asset(assetId,42,id,original.bodySha256(),"bad",key,"b".repeat(64),48044,1000,"audio/wav"),
                new PreparedPublicationRepository.Asset(assetId,42,id,original.bodySha256(),"a".repeat(64),key,"bad",48044,1000,"audio/wav"),
                new PreparedPublicationRepository.Asset(assetId,42,id,original.bodySha256(),"a".repeat(64),key,"b".repeat(64),0,1000,"audio/wav"),
                new PreparedPublicationRepository.Asset(assetId,42,id,original.bodySha256(),"a".repeat(64),key,"b".repeat(64),67108865,1000,"audio/wav"),
                new PreparedPublicationRepository.Asset(assetId,42,id,original.bodySha256(),"a".repeat(64),key,"b".repeat(64),48044,0,"audio/wav"),
                new PreparedPublicationRepository.Asset(assetId,42,id,original.bodySha256(),"a".repeat(64),key,"b".repeat(64),48044,1800001,"audio/wav"),
                new PreparedPublicationRepository.Asset(assetId,42,id,original.bodySha256(),"a".repeat(64),key,"b".repeat(64),48044,1000,"audio/mp3"))) {
            assertThat(issues(q("题目",true,"READY",key),validRules(),audio,false)).extracting("code").contains("AUDIO_MISMATCH");
        }
    }
    @Test void invalidTotalPassCountEmptyPaperAndStartedAssessmentHaveSpecificReasons() {
        var question=q("题目",true,"READY",key);var audio=asset("题目",key);
        for(var rules:List.of(new PreparedPublication.Rules("面试",null,null,null,false),
                new PreparedPublication.Rules("面试",0,-1,0,false),new PreparedPublication.Rules("面试",11,12,2,true))) {
            assertThat(issues(question,rules,audio,false)).extracting("code").contains("TOTAL_SCORE_MISMATCH","INVALID_PASS_SCORE","QUESTION_COUNT_MISMATCH");
        }
        assertThat(issues(question,validRules(),audio,true)).extracting("code").containsExactly("PAPER_FROZEN");
        var check=PreparedPublicationChecks.inspect(new PreparedPaper.Draft(42,0,List.of()),validRules(),false,(a,q,k)->Optional.empty(),"prepared-local");
        assertThat(check.issues()).extracting("code").contains("EMPTY_PAPER");
        assertThat(check.snapshot()).isNull();
    }
    @Test void malformedBodyRulesOrderAndIdentityCannotBePublishedEvenWithForgedReadyFlag() {
        var qs=List.of(new PreparedPaper.Question(id,"MANUAL"," ",1,10,120,"r",true,"READY",key),
                new PreparedPaper.Question(id,"MANUAL","题目",2,0,1801,"",true,"READY",key),
                new PreparedPaper.Question("bad","MANUAL","题目",1,10,120,"r",true,"READY",key));
        for(var q:qs) assertThat(issues(q,validRules(),asset("题目",key),false)).extracting("code").contains("INVALID_QUESTION");
    }

    @Test void allTextRuleAndDuplicateBoundariesFailClosed() {
        for(var question:List.of(
                new PreparedPaper.Question(id,"MANUAL",null,1,10,120,"r",true,"READY",key),
                new PreparedPaper.Question(id,"MANUAL","x".repeat(10001),1,10,120,"r",true,"READY",key),
                new PreparedPaper.Question(id,"MANUAL","题目",1,10,120,null,true,"READY",key),
                new PreparedPaper.Question(id,"MANUAL","题目",1,10,120," ",true,"READY",key),
                new PreparedPaper.Question(id,"MANUAL","题目",1,10,120,"x".repeat(20001),true,"READY",key),
                new PreparedPaper.Question(id,"MANUAL","题目",1,0,120,"r",true,"READY",key),
                new PreparedPaper.Question(id,"MANUAL","题目",1,10001,120,"r",true,"READY",key),
                new PreparedPaper.Question(id,"MANUAL","题目",1,10,0,"r",true,"READY",key),
                new PreparedPaper.Question(id,"MANUAL","题目",1,10,1801,"r",true,"READY",key)))
            assertThat(issues(question,validRules(),asset("题目",key),false)).extracting("code").contains("INVALID_QUESTION");
        var q=q("题目",true,"READY",key);
        var duplicate=new PreparedPaper.Question(id,"MANUAL","题目",2,10,120,"r",true,"READY",key);
        assertThat(PreparedPublicationChecks.inspect(new PreparedPaper.Draft(42,1,List.of(q,duplicate)),
                new PreparedPublication.Rules("面试",20,0,2,false),false,(a,b,c)->Optional.of(asset("题目",key)),"prepared-local").issues())
                .extracting("code").contains("INVALID_QUESTION");
        assertThat(issues(q("题目",true,"READY",null),validRules(),null,false)).extracting("code").contains("AUDIO_NOT_READY");
        assertThat(issues(q, new PreparedPublication.Rules("面试",null,6,1,false),asset("题目",key),false)).extracting("code").contains("INVALID_PASS_SCORE");
        var nullable=new PreparedPublicationRepository.Asset(assetId,42,id,PreparedJobInput.digest("题目"),null,key,null,48044,1000,"audio/wav");
        assertThat(issues(q,validRules(),nullable,false)).extracting("code").contains("AUDIO_MISMATCH");
        assertThatThrownBy(()->new PreparedPublicationService(null,null,null,"unscoped/path")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedPublicationService(null,null,null,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void commandValidationAndCorruptSnapshotFailClosed() {
        new PreparedPublication.Command(0L,UUID.randomUUID().toString(),"a".repeat(64)).validate();
        for(Long rev:Arrays.asList(null,-1L,Long.MAX_VALUE)) assertThatThrownBy(()->new PreparedPublication.Command(rev,UUID.randomUUID().toString(),"a".repeat(64)).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedPublication.Command(0L,"bad","a".repeat(64)).validate()).isInstanceOf(IllegalArgumentException.class);
        for(String hash:Arrays.asList(null,"", "bad", "A".repeat(64))) assertThatThrownBy(()->new PreparedPublication.Command(0L,UUID.randomUUID().toString(),hash).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedPublication.decode("{broken}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedPublication.decode("{\"assessmentId\":1,\"assessmentId\":2}")).isInstanceOf(IllegalArgumentException.class);
    }
}
