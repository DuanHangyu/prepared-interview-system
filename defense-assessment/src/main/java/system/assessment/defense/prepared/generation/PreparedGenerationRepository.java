package system.assessment.defense.prepared.generation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static system.assessment.defense.prepared.generation.PreparedGeneration.*;

/** Caller holds the assessment parent lock for all commands. */
@Repository
public class PreparedGenerationRepository {
    public record Receipt(String requestHash, long revision) {}
    private final JdbcTemplate jdbc;
    public PreparedGenerationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<String> previousJob(int actor,int assessment,String key) {
        return jdbc.queryForList("SELECT id FROM prepared_job WHERE created_by=? AND assessment_id=? AND kind='QUESTIONS' AND idempotency_key=?",
                String.class,actor,assessment,key).stream().findFirst();
    }
    public boolean active(int assessment) {
        return !jdbc.queryForList("SELECT id FROM prepared_job WHERE assessment_id=? AND kind='QUESTIONS' AND state IN ('QUEUED','RUNNING','RETRY_WAIT') LIMIT 1",
                String.class,assessment).isEmpty();
    }
    public List<Summary> list(int assessment) {
        return jdbc.query("SELECT id,state,input_version,error_code,created_at FROM prepared_job WHERE assessment_id=? AND kind='QUESTIONS' ORDER BY created_at DESC,id DESC LIMIT 20",
                (rs,n)->new Summary(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getTimestamp(5).toInstant().toString()),assessment);
    }
    public Summary summary(String job) {
        return jdbc.query("SELECT id,state,input_version,error_code,created_at FROM prepared_job WHERE id=? AND kind='QUESTIONS'",
                (rs,n)->new Summary(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getTimestamp(5).toInstant().toString()),job).getFirst();
    }
    public List<String> adopted(String job) {
        return jdbc.queryForList("SELECT question_id FROM prepared_generation_adopted WHERE job_id=? ORDER BY ordinal",String.class,job);
    }
    public Optional<Receipt> receipt(int assessment,int actor,String key) {
        return jdbc.query("SELECT request_sha256,adopted_revision FROM prepared_generation_adoption WHERE assessment_id=? AND actor_id=? AND idempotency_key=?",
                (rs,n)->new Receipt(rs.getString(1),rs.getLong(2)),assessment,actor,key).stream().findFirst();
    }
    public void record(int actor,int assessment,String job,AdoptCommand command,String hash,long revision) {
        jdbc.update("INSERT INTO prepared_generation_adoption(assessment_id,actor_id,idempotency_key,job_id,request_sha256,adopted_revision) VALUES (?,?,?,?,?,?)",
                assessment,actor,command.idempotencyKey(),job,hash,revision);
        int index=adopted(job).size();
        for(String id:command.questionIds()) jdbc.update("INSERT INTO prepared_generation_adopted(job_id,question_id,assessment_id,actor_id,adopted_revision,ordinal) VALUES (?,?,?,?,?,?)",
                job,id,assessment,actor,revision,++index);
        audit(actor,assessment,"AI_SUGGESTIONS_ADOPTED",revision);
    }
    public void audit(int actor,int assessment,String action,long revision) {
        jdbc.update("INSERT INTO prepared_audit(assessment_id,actor_id,action,resource_version) VALUES (?,?,?,?)",assessment,actor,action,revision);
    }
}
