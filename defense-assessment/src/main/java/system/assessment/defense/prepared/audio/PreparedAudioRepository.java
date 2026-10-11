package system.assessment.defense.prepared.audio;

import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import system.assessment.defense.prepared.media.PreparedWaveAudio;
import java.math.BigDecimal;
import java.util.*;
import static system.assessment.defense.prepared.audio.PreparedAudio.*;

@Repository
public class PreparedAudioRepository {
    private final JdbcTemplate jdbc;
    public PreparedAudioRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Receipt(String hash,String assetId) {}
    public record Asset(String id,int assessment,String question,String bodySha,String configSha,String jobId,
                        String storedState,String key,PreparedWaveAudio.Verified proof,View view) {}
    private static final String SELECT="""
        SELECT a.*,j.state AS job_state,j.error_code,
          (SELECT COUNT(*) FROM prepared_audio_part_ref r JOIN prepared_job c ON c.id=r.job_id
           WHERE r.asset_id=a.id AND c.state='SUCCEEDED') AS completed_parts
        FROM prepared_audio_asset a JOIN prepared_job j ON j.id=a.job_id AND j.assessment_id=a.assessment_id
        """;
    private static final RowMapper<Asset> ASSET=(rs,n)->{
        String state=rs.getString("state"),jobState=rs.getString("job_state");
        String visible=state.equals("READY") && jobState.equals("SUCCEEDED")?"READY":state.equals("FAILED") || jobState.equals("NEEDS_REVIEW")?"FAILED":"GENERATING";
        int count=rs.getInt("part_count"),complete=rs.getInt("completed_parts");
        var view=new View(rs.getString("id"),rs.getString("question_id"),rs.getString("body_sha256"),rs.getString("job_id"),visible,
                count,complete,rs.getInt("new_part_count"),rs.getBigDecimal("maximum_new_cost_cny"),rs.getString("error_code"),
                visible.equals("FAILED") && count>0 && complete==count,(Long)rs.getObject("byte_size"),(Long)rs.getObject("duration_ms"),rs.getTimestamp("created_at").toInstant().toString());
        PreparedWaveAudio.Verified proof=null;
        if(rs.getObject("sample_rate")!=null && rs.getObject("channels")!=null && rs.getObject("data_offset")!=null && rs.getObject("data_size")!=null
                && rs.getObject("byte_size")!=null && rs.getObject("duration_ms")!=null)
            proof=new PreparedWaveAudio.Verified(rs.getLong("byte_size"),rs.getString("content_sha256"),rs.getLong("duration_ms"),rs.getInt("sample_rate"),rs.getInt("channels"),rs.getLong("data_offset"),rs.getLong("data_size"));
        return new Asset(view.id(),rs.getInt("assessment_id"),view.questionId(),view.bodySha256(),rs.getString("config_sha256"),view.jobId(),state,rs.getString("object_key"),proof,view);
    };
    public Optional<Receipt> receipt(int actor,int assessment,String key){return jdbc.query(
            "SELECT request_sha256,asset_id FROM prepared_audio_command WHERE actor_id=? AND assessment_id=? AND idempotency_key=?",
            (rs,n)->new Receipt(rs.getString(1),rs.getString(2)),actor,assessment,key).stream().findFirst();}
    public Optional<Asset> get(int assessment,String id){return jdbc.query(SELECT+" WHERE a.assessment_id=? AND a.id=?",ASSET,assessment,id).stream().findFirst();}
    public Optional<Asset> active(int assessment,String question,String body,String config){return jdbc.query(SELECT+" WHERE a.assessment_id=? AND a.question_id=? AND a.body_sha256=? AND a.config_sha256=? AND a.state IN ('BUILDING','READY')",ASSET,assessment,question,body,config).stream().findFirst();}
    public List<Asset> history(int assessment){return jdbc.query(SELECT+" WHERE a.assessment_id=? ORDER BY a.created_at DESC,a.id DESC LIMIT 20",ASSET,assessment);}
    public Optional<Asset> latest(int assessment,String question,String body){return jdbc.query(SELECT+" WHERE a.assessment_id=? AND a.question_id=? AND a.body_sha256=? ORDER BY a.created_at DESC,a.id DESC LIMIT 1",ASSET,assessment,question,body).stream().findFirst();}
    public void insert(String id,int assessment,String question,String body,String config,String job,int partCount,int newCount,BigDecimal cost){
        jdbc.update("INSERT INTO prepared_audio_asset(id,assessment_id,question_id,body_sha256,config_sha256,job_id,part_count,new_part_count,maximum_new_cost_cny) VALUES (?,?,?,?,?,?,?,?,?)",id,assessment,question,body,config,job,partCount,newCount,cost);
    }
    public void receipt(int actor,int assessment,String key,String hash,String asset){jdbc.update("INSERT INTO prepared_audio_command(assessment_id,actor_id,idempotency_key,request_sha256,asset_id) VALUES (?,?,?,?,?)",assessment,actor,key,hash,asset);}
    public void part(String job,int assessment,String question,String body,String config,int index,int count){jdbc.update("INSERT INTO prepared_audio_part(job_id,assessment_id,question_id,body_sha256,config_sha256,part_index,part_count) VALUES (?,?,?,?,?,?,?)",job,assessment,question,body,config,index,count);}
    public void reference(int assessment,String asset,int index,String job){jdbc.update("INSERT INTO prepared_audio_part_ref(assessment_id,asset_id,part_index,job_id) VALUES (?,?,?,?)",assessment,asset,index,job);}
    public List<String> partJobs(int assessment,String asset){return jdbc.queryForList("SELECT job_id FROM prepared_audio_part_ref WHERE assessment_id=? AND asset_id=? ORDER BY part_index",String.class,assessment,asset);}
    public List<String> cached(int assessment,String question,String body,String config,int index){return jdbc.queryForList("""
            SELECT p.job_id FROM prepared_audio_part p JOIN prepared_job j ON j.id=p.job_id AND j.assessment_id=p.assessment_id
            WHERE p.assessment_id=? AND p.question_id=? AND p.body_sha256=? AND p.config_sha256=? AND p.part_index=? AND j.kind='TTS' AND j.state='SUCCEEDED'
            ORDER BY j.updated_at DESC,j.id DESC LIMIT 50
            """,String.class,assessment,question,body,config,index);}
    /** A terminal parent or sibling stops additional queued charges, even before explicit retry. */
    public boolean partIsWanted(String job){return Objects.requireNonNull(jdbc.queryForObject("""
            SELECT COUNT(*) FROM prepared_audio_part_ref r JOIN prepared_audio_asset a ON a.id=r.asset_id AND a.assessment_id=r.assessment_id
            JOIN prepared_job p ON p.id=a.job_id WHERE r.job_id=? AND a.state='BUILDING' AND p.state IN ('QUEUED','RUNNING','RETRY_WAIT')
            AND NOT EXISTS(SELECT 1 FROM prepared_audio_part_ref other JOIN prepared_job c ON c.id=other.job_id
                           WHERE other.asset_id=a.id AND c.state='NEEDS_REVIEW')
            """,Integer.class,job))>0;}
    public void failed(int assessment,String id){jdbc.update("UPDATE prepared_audio_asset SET state='FAILED' WHERE assessment_id=? AND id=? AND state='BUILDING'",assessment,id);}
    public void ready(int assessment,String id,String key,PreparedWaveAudio.Verified proof){
        if(jdbc.update("""
                UPDATE prepared_audio_asset SET state='READY',object_key=?,content_sha256=?,byte_size=?,duration_ms=?,media_type='audio/wav',
                  sample_rate=?,channels=?,data_offset=?,data_size=?,verified_at=CURRENT_TIMESTAMP(3)
                WHERE assessment_id=? AND id=? AND state='BUILDING'
                """,key,proof.sha256(),proof.byteSize(),proof.durationMs(),proof.sampleRate(),proof.channels(),proof.dataOffset(),proof.dataSize(),assessment,id)!=1)
            throw new system.assessment.defense.infrastructure.exception.BusinessException(409,"播报任务已结束或被替代");
    }
}
