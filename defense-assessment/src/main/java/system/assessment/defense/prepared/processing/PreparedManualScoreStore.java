package system.assessment.defense.prepared.processing;

import org.springframework.jdbc.core.JdbcTemplate;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.publication.PreparedPublication;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static system.assessment.defense.prepared.processing.PreparedProcessing.*;
import static system.assessment.defense.prepared.processing.PreparedProcessingCodec.*;

/** Used only inside the processing transaction after original/assessment/head scope locks.
 * Canonical frozen input binds human reasons/score to the original audio and question. */
final class PreparedManualScoreStore {
    private record Input(int schemaVersion,PreparedSubmission.Work original,PreparedPublication.Question question,
                         int actor,AdjustCommand command,String transcriptId,String scoreId,String previousManualScoreId,int version) {}
    private final JdbcTemplate jdbc;
    PreparedManualScoreStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    static void validate(AdjustCommand command){
        if(command==null||command.expectedRevision()<0||command.expectedRevision()>=9007199254740991L)throw new IllegalArgumentException("人工调分须提供有效的原评阅版本");
        PreparedJobInput.uuid(command.idempotencyKey());text(command.reason(),1000);
        if(command.score()==null||command.score().signum()<0||command.score().scale()>2||command.score().compareTo(BigDecimal.valueOf(1000000))>0)throw new IllegalArgumentException("人工分数须非负且最多两位小数");
    }
    String save(PreparedSubmission.Work work,PreparedSubmissionService.ProcessingSource original,int actor,AdjustCommand command,
                     String transcript,String score,String previous){
        var versions=jdbc.queryForList("SELECT version_no FROM prepared_manual_score WHERE answer_id=? ORDER BY version_no DESC LIMIT 1 FOR UPDATE",Integer.class,work.answer());
        int version=versions.isEmpty()?1:versions.getFirst()+1;
        if(version>100000)throw new BusinessException(409,"人工评分历史达到上限，请联系管理员");
        String id=UUID.nameUUIDFromBytes(("prepared-manual-score-v1:"+actor+":"+work.answer()+":"+command.idempotencyKey()).getBytes(StandardCharsets.UTF_8)).toString();
        var question=original.question();String input=encodeInput(new Input(1,work,question,actor,command,transcript,score,previous,version));
        jdbc.update("""
            INSERT INTO prepared_manual_score(id,assessment_id,student_id,attempt_id,answer_id,version_no,result_revision,question_id,
                max_score,score_value,reason,recorded_by,audio_sha256,transcript_id,score_id,previous_manual_score_id,input_json,input_sha256)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,work.assessment(),work.student(),work.attempt(),work.answer(),version,command.expectedRevision()+1,question.questionId(),
            question.maxScore(),command.score(),command.reason(),actor,original.media().proof().sha256(),transcript,score,previous,input,PreparedJobInput.digest(input));
        return id; // The caller records the command in this same transaction before validated read.
    }
    ManualScore read(PreparedSubmission.Work work,PreparedSubmissionService.ProcessingSource original,String id){
        PreparedJobInput.uuid(id);
        // Current read: a concurrent replay can see the committed command through FOR UPDATE
        // while its earlier permission read established an older repeatable-read snapshot.
        var rows=jdbc.queryForList("SELECT * FROM prepared_manual_score WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? AND id=? FOR UPDATE",work.assessment(),work.student(),work.attempt(),work.answer(),id);
        if(rows.size()!=1)throw new BusinessException(409,"人工评分不属于原提交回答");var row=rows.getFirst();
        var input=decodeInput((String)row.get("input_json"),Input.class);validate(input.command());
        var value=new ManualScore((String)row.get("id"),(String)row.get("answer_id"),((Number)row.get("version_no")).intValue(),((Number)row.get("result_revision")).longValue(),
            (String)row.get("question_id"),((Number)row.get("max_score")).intValue(),(BigDecimal)row.get("score_value"),(String)row.get("reason"),((Number)row.get("recorded_by")).intValue(),
            Objects.requireNonNull(jdbc.queryForObject("SELECT recorded_at FROM prepared_manual_score WHERE id=? FOR UPDATE",java.sql.Timestamp.class,id)).toInstant().toString(),(String)row.get("audio_sha256"),(String)row.get("transcript_id"),(String)row.get("score_id"),(String)row.get("previous_manual_score_id"));
        var question=original.question();
        String expectedId=UUID.nameUUIDFromBytes(("prepared-manual-score-v1:"+input.actor()+":"+work.answer()+":"+input.command().idempotencyKey()).getBytes(StandardCharsets.UTF_8)).toString();
        if(input.schemaVersion()!=1||!work.equals(input.original())||!question.equals(input.question())||!expectedId.equals(id)
            ||!PreparedJobInput.digest(encodeInput(input)).equals(row.get("input_sha256"))||input.actor()!=value.recordedBy()||input.version()!=value.version()
            ||input.command().expectedRevision()+1!=value.revision()||!input.command().score().equals(value.score())||!input.command().reason().equals(value.reason())
            ||!question.questionId().equals(value.questionId())||question.maxScore()!=value.maxScore()||value.score().compareTo(BigDecimal.valueOf(question.maxScore()))>0
            ||!original.media().proof().sha256().equals(value.audioSha256())||!Objects.equals(input.transcriptId(),value.transcriptId())
            ||!Objects.equals(input.scoreId(),value.scoreId())||!Objects.equals(input.previousManualScoreId(),value.previousManualScoreId()))throw new IllegalStateException("原人工评分内容或输入绑定损坏");
        if(value.previousManualScoreId()!=null){
            var revisions=jdbc.queryForList("SELECT result_revision FROM prepared_manual_score WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? AND id=? AND version_no<? FOR UPDATE",Long.class,
                work.assessment(),work.student(),work.attempt(),work.answer(),value.previousManualScoreId(),value.version());
            if(revisions.size()!=1||revisions.getFirst()>=value.revision())throw new IllegalStateException("人工评分历史来源损坏，拒绝循环引用");
        }
        var commands=jdbc.queryForList("""
            SELECT request_json,request_sha256,receipt_json FROM prepared_result_command
            WHERE assessment_id=? AND actor_id=? AND answer_id=? AND action='ADJUST' AND idempotency_key=? FOR UPDATE
            """,work.assessment(),input.actor(),work.answer(),input.command().idempotencyKey());
        if(commands.size()!=1)throw new IllegalStateException("原人工评分操作证明缺失");var command=commands.getFirst();
        String request=PreparedJobInput.json((String)command.get("request_json"),16384);
        var receipt=decode((String)command.get("receipt_json"),PreparedProcessingService.AdjustReceipt.class);
        if(!encode(input.command()).equals(request)||!PreparedJobInput.digest(request).equals(command.get("request_sha256"))
            ||!work.answer().equals(receipt.answerId())||!id.equals(receipt.manualScoreId())||value.revision()!=receipt.revision())throw new IllegalStateException("原人工评分命令或回执与历史不一致");
        return value;
    }
    List<String> ids(PreparedSubmission.Work work,String after,int limit){
        if(after==null)return jdbc.queryForList("SELECT id FROM prepared_manual_score WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? ORDER BY version_no DESC LIMIT ? FOR UPDATE",String.class,work.assessment(),work.student(),work.attempt(),work.answer(),limit+1);
        Integer version=jdbc.queryForObject("SELECT version_no FROM prepared_manual_score WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? AND id=? FOR UPDATE",Integer.class,work.assessment(),work.student(),work.attempt(),work.answer(),after);
        return jdbc.queryForList("SELECT id FROM prepared_manual_score WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? AND version_no<? ORDER BY version_no DESC LIMIT ? FOR UPDATE",String.class,work.assessment(),work.student(),work.attempt(),work.answer(),version,limit+1);
    }
}
