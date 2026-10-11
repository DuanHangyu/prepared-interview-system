package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.prepared.processing.*;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.jobs.PreparedJobLedger.*;

/** Legitimate escaped full text must fit frozen provenance without silently truncating it. */
@EnabledIfEnvironmentVariable(named="PREPARED_LIVE_TESTS",matches="true")
class PreparedScoreFullInputLiveTest {
    final PreparedProcessingLiveTest f=new PreparedProcessingLiveTest();@TempDir Path folder;
    @BeforeEach void setup()throws Exception{f.folder=folder;f.setup();}
    @AfterEach void cleanup(){f.cleanup();}
    @Test void largeValidFullTranscriptPersistsAndReadsItsExactFrozenScoringSource(){
        var lease=f.claim(Kind.TRANSCRIBE);var plan=f.service.plan(lease);var media=plan.original().media().proof();
        assertThat(media.durationMs()).isGreaterThanOrEqualTo(1000);
        var sentences=java.util.stream.IntStream.range(0,1000).mapToObj(n->new PreparedProcessing.Sentence(n,n+1,"\\".repeat(60))).toList();
        var source=f.service.completeTranscript(lease,PreparedProcessingCodec.encode(new PreparedProcessing.Transcript(1,f.asr.provider(),f.asr.model(),media.sha256(),media.durationMs(),sentences)),f.asr);
        var scoring=f.claim(Kind.SCORE);var s=f.service.plan(scoring);var q=s.original().question();
        var content=PreparedProcessingCodec.encode(new PreparedProcessing.Score(1,q.questionId(),source.id(),source.contentSha256(),q.maxScore(),new BigDecimal("5"),"明确标记的边界合成评分，不代表真实识别质量",List.of(new PreparedProcessing.Evidence(1000,"\\"))));
        var result=f.service.completeScore(scoring,content,f.scorer);assertThat(f.view(source.answerId()).scoreId()).isEqualTo(result.id());assertThat(f.view(source.answerId()).history()).containsExactly(source,result);
        String frozen=f.jdbc.queryForObject("SELECT input_json FROM prepared_processing_result WHERE id=?",String.class,result.id());assertThat(frozen.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(262144);
        assertThat(f.f.status().submitted()).isTrue();
    }
}
