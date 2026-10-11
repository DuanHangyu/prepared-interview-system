package system.assessment.defense.prepared.attempt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore.AnswerBundle;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static system.assessment.defense.prepared.attempt.PreparedAnswerBundle.*;

/** Parent authorization lock precedes these current reads; metadata is permanently immutable. */
@Repository
public class PreparedAnswerBundleRepository {
    private final JdbcTemplate jdbc;
    public PreparedAnswerBundleRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Row(Command command,AnswerBundle scope,String commandSha,String deployment,String key,Instant reserved,Instant confirmed){}
    public Optional<Row> find(int student,int assessment,String attempt,String answer,String id) {
        PreparedAnswerBundle.id(id);
        return jdbc.query("""
            SELECT * FROM prepared_answer_upload_bundle
            WHERE student_id=? AND assessment_id=? AND attempt_id=? AND answer_id=? AND id=? FOR UPDATE
            """,(rs,n)->{
                String original=rs.getString("command_json");var command=decode(original);var scope=command.scope(student,assessment,attempt,answer);
                String deployment=rs.getString("deployment_id"),key=rs.getString("object_key"),hash=rs.getString("command_sha256");
                if(!encode(command).equals(original)||!PreparedJobInput.digest(original).equals(hash)||!id.equals(scope.id())
                        ||scope.byteSize()!=rs.getInt("byte_size")||!scope.sha256().equals(rs.getString("sha256"))||!scope.key(deployment).equals(key))
                    throw new IllegalStateException("原录音批次绑定无法核验");
                return new Row(command,scope,hash,deployment,key,rs.getTimestamp("reserved_at").toInstant(),rs.getTimestamp("confirmed_at")==null?null:rs.getTimestamp("confirmed_at").toInstant());
            },student,assessment,attempt,answer,id).stream().findFirst();
    }
    public Row reserve(int student,int assessment,String attempt,String answer,Command command,String deployment,Instant now) {
        var scope=command.scope(student,assessment,attempt,answer);var previous=find(student,assessment,attempt,answer,scope.id());
        if(previous.isPresent()) {
            var row=previous.orElseThrow();if(!row.command().equals(command)||!row.deployment().equals(deployment))throw conflict();return row;
        }
        String json=encode(command),sha=PreparedJobInput.digest(json),key=scope.key(deployment);
        jdbc.update("""
            INSERT INTO prepared_answer_upload_bundle(id,assessment_id,student_id,attempt_id,answer_id,command_json,command_sha256,byte_size,sha256,deployment_id,object_key,reserved_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,scope.id(),assessment,student,attempt,answer,json,sha,scope.byteSize(),scope.sha256(),deployment,key,Timestamp.from(now));
        return new Row(command,scope,sha,deployment,key,now,null);
    }
    public void confirm(Row row,Instant now) {
        if(now.isBefore(row.reserved()))throw new BusinessException(503,"服务器时间暂不可核验，请保留原批次");
        var first=row.scope().chunks().getFirst();
        int changed=jdbc.update("""
            UPDATE prepared_answer_upload_bundle SET confirmed_at=COALESCE(confirmed_at,?)
            WHERE student_id=? AND assessment_id=? AND attempt_id=? AND answer_id=? AND id=?
            """,Timestamp.from(now),first.studentId(),first.assessmentId(),first.attemptId(),first.answerId(),row.scope().id());
        if(changed!=1)throw new IllegalStateException("原录音批次不存在");
    }
    private static BusinessException conflict(){return new BusinessException(409,"原录音批次绑定不一致，请保留原内容");}
}
