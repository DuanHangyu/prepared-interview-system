package system.assessment.defense.prepared.attempt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore.AnswerChunk;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore.AnswerBundle;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import static system.assessment.defense.prepared.attempt.PreparedAnswerUpload.*;

/** Every method is scoped; caller holds current authorization and parent/answer locks for writes. */
@Repository
public class PreparedAnswerUploadRepository {
    private final JdbcTemplate jdbc;
    public PreparedAnswerUploadRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Row(AnswerChunk scope,int ordinal,String deployment,String objectKey,Instant reserved,Instant confirmed) {
        public Receipt receipt(){return new Receipt(scope.answerId(),scope.segmentId(),scope.index(),scope.byteSize(),scope.sha256(),confirmed!=null,confirmed==null?null:confirmed.toString());}
    }
    public record Location(Row row,String bundleId,Integer offset) {}
    /** Internal whole-answer reader retains physical layout; never expose it as a student DTO. */
    public List<Location> locations(int student,int assessment,String attempt,String answer) {
        return jdbc.query("""
            SELECT c.*,s.ordinal,s.mime_type FROM prepared_answer_chunk c JOIN prepared_answer_segment s ON s.id=c.segment_id
            WHERE c.assessment_id=? AND c.student_id=? AND c.attempt_id=? AND c.answer_id=?
            ORDER BY s.ordinal,c.chunk_index LIMIT 10001 FOR UPDATE
            """,(rs,n)->new Location(row(rs),rs.getString("bundle_id"),(Integer)rs.getObject("bundle_offset")),assessment,student,attempt,answer);
    }
    public List<Row> rows(int student,int assessment,String attempt,String answer) {
        return jdbc.query("""
            SELECT c.*,s.ordinal,s.mime_type FROM prepared_answer_chunk c JOIN prepared_answer_segment s ON s.id=c.segment_id
            WHERE c.assessment_id=? AND c.student_id=? AND c.attempt_id=? AND c.answer_id=? ORDER BY s.ordinal,c.chunk_index FOR UPDATE
            """,(rs,n)->row(rs),assessment,student,attempt,answer);
    }
    private Optional<Row> row(AnswerChunk scope) {
        return jdbc.query("""
            SELECT c.*,s.ordinal,s.mime_type FROM prepared_answer_chunk c JOIN prepared_answer_segment s ON s.id=c.segment_id
            WHERE c.assessment_id=? AND c.student_id=? AND c.attempt_id=? AND c.answer_id=? AND c.segment_id=? AND c.chunk_index=? FOR UPDATE
            """,(rs,n)->row(rs),scope.assessmentId(),scope.studentId(),scope.attemptId(),scope.answerId(),scope.segmentId(),scope.index()).stream().findFirst();
    }
    private static Row row(ResultSet rs)throws SQLException {
        return new Row(new AnswerChunk(rs.getInt("assessment_id"),rs.getInt("student_id"),rs.getString("attempt_id"),rs.getString("answer_id"),rs.getString("segment_id"),rs.getInt("chunk_index"),rs.getInt("byte_size"),rs.getString("sha256"),rs.getString("mime_type")),
                rs.getInt("ordinal"),rs.getString("deployment_id"),rs.getString("object_key"),rs.getTimestamp("reserved_at").toInstant(),rs.getTimestamp("confirmed_at")==null?null:rs.getTimestamp("confirmed_at").toInstant());
    }
    public Row reserve(AnswerChunk scope,int ordinal,String deployment,Instant now) {
        return reserve(scope,ordinal,deployment,null,null,now);
    }
    public Row reserveBundle(AnswerChunk scope,int ordinal,String deployment,String bundleId,int offset,Instant now) {
        PreparedAnswerBundle.id(bundleId);return reserve(scope,ordinal,deployment,bundleId,offset,now);
    }
    /** One current total/segment read and one bounded insert batch, never an aggregate for every callback.
     * Caller already holds the authorized parent/answer lock, and inserts this new header atomically. */
    public void reserveBundle(AnswerBundle bundle,int ordinal,String deployment,Instant now) {
        var first=bundle.chunks().getFirst();
        String selection=String.join(",",Collections.nCopies(bundle.chunks().size(),"?"));
        var args=new ArrayList<Object>(List.of(first.assessmentId(),first.studentId(),first.attemptId(),first.answerId(),first.segmentId()));
        for(var chunk:bundle.chunks())args.add(chunk.index());
        record Located(Row row,String bundleId,Integer offset){}
        var existing=jdbc.query("""
            SELECT c.*,s.ordinal,s.mime_type FROM prepared_answer_chunk c JOIN prepared_answer_segment s ON s.id=c.segment_id
            WHERE c.assessment_id=? AND c.student_id=? AND c.attempt_id=? AND c.answer_id=? AND c.segment_id=? AND c.chunk_index IN (
            """+selection+") FOR UPDATE",(rs,n)->new Located(row(rs),rs.getString("bundle_id"),(Integer)rs.getObject("bundle_offset")),args.toArray());
        var prior=new HashMap<Integer,Located>();for(var location:existing)prior.put(location.row.scope().index(),location);
        var additions=new ArrayList<Object[]>();int offset=0,bytes=0;
        for(var chunk:bundle.chunks()) {
            String key=chunk.key(deployment);var location=prior.get(chunk.index());
            if(location!=null) {
                var row=location.row();
                if(!row.scope().equals(chunk)||row.ordinal()!=ordinal||!row.deployment().equals(deployment)||!row.objectKey().equals(key)
                        ||!Objects.equals(location.bundleId(),bundle.id())||!Objects.equals(location.offset(),offset))throw conflict();
            }else {
                bytes+=chunk.byteSize();additions.add(new Object[]{chunk.assessmentId(),chunk.studentId(),chunk.attemptId(),chunk.answerId(),chunk.segmentId(),chunk.index(),chunk.byteSize(),chunk.sha256(),deployment,key,Timestamp.from(now),bundle.id(),offset});
            }
            offset+=chunk.byteSize();
        }
        if(additions.isEmpty())return;
        var totals=jdbc.query("SELECT COUNT(*),COALESCE(SUM(byte_size),0) FROM prepared_answer_chunk WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? FOR UPDATE",
                (rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},first.assessmentId(),first.studentId(),first.attemptId(),first.answerId()).getFirst();
        if(totals[0]+additions.size()>10000||totals[1]+bytes>67108864)throw new BusinessException(413,"本题录音已超过大小限制，请保留原内容并联系教师");
        var segments=jdbc.query("SELECT id,ordinal,mime_type FROM prepared_answer_segment WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? FOR UPDATE",
                (rs,n)->List.of(rs.getString(1),Integer.toString(rs.getInt(2)),rs.getString(3)),first.assessmentId(),first.studentId(),first.attemptId(),first.answerId());
        boolean present=false;
        for(var segment:segments) {
            if(segment.get(0).equals(first.segmentId())){present=true;if(!segment.get(1).equals(Integer.toString(ordinal))||!segment.get(2).equals(first.mimeType()))throw conflict();}
            else if(segment.get(1).equals(Integer.toString(ordinal)))throw conflict();
        }
        try {
            if(!present) {
                if(segments.size()>=1000)throw new BusinessException(413,"本题录音分段过多，请保留原内容并联系教师");
                jdbc.update("INSERT INTO prepared_answer_segment(id,assessment_id,student_id,attempt_id,answer_id,ordinal,mime_type) VALUES (?,?,?,?,?,?,?)",first.segmentId(),first.assessmentId(),first.studentId(),first.attemptId(),first.answerId(),ordinal,first.mimeType());
            }
            jdbc.batchUpdate("INSERT INTO prepared_answer_chunk(assessment_id,student_id,attempt_id,answer_id,segment_id,chunk_index,byte_size,sha256,deployment_id,object_key,reserved_at,bundle_id,bundle_offset) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",additions);
        }catch(org.springframework.dao.DuplicateKeyException collision){throw conflict();}
    }
    private Row reserve(AnswerChunk scope,int ordinal,String deployment,String bundleId,Integer offset,Instant now) {
        var existing=row(scope);
        if(existing.isPresent()) {
            var row=existing.orElseThrow();
            if(!row.scope().equals(scope)||row.ordinal()!=ordinal||!row.deployment().equals(deployment)||!row.objectKey().equals(scope.key(deployment)))throw conflict();
            var location=jdbc.query("SELECT bundle_id,bundle_offset FROM prepared_answer_chunk WHERE segment_id=? AND chunk_index=? FOR UPDATE",
                (rs,n)->new Object[]{rs.getString(1),rs.getObject(2)},scope.segmentId(),scope.index()).getFirst();
            if(!Objects.equals(bundleId,location[0])||!Objects.equals(offset,location[1]))throw conflict();
            return row;
        }
        // Locking current read avoids an earlier ACL snapshot hiding a just-committed concurrent reservation.
        var totals=jdbc.query("SELECT COUNT(*),COALESCE(SUM(byte_size),0) FROM prepared_answer_chunk WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? FOR UPDATE",
                (rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},scope.assessmentId(),scope.studentId(),scope.attemptId(),scope.answerId()).getFirst();
        if(totals[0]>=10000||totals[1]+scope.byteSize()>67108864)throw new BusinessException(413,"本题录音已超过大小限制，请保留原内容并联系教师");
        var segments=jdbc.query("SELECT id,ordinal,mime_type FROM prepared_answer_segment WHERE answer_id=? FOR UPDATE",(rs,n)->List.of(rs.getString(1),Integer.toString(rs.getInt(2)),rs.getString(3)),scope.answerId());
        boolean exists=false;
        for(var segment:segments) {
            if(segment.get(0).equals(scope.segmentId())){exists=true;if(!segment.get(1).equals(Integer.toString(ordinal))||!segment.get(2).equals(scope.mimeType()))throw conflict();}
            else if(segment.get(1).equals(Integer.toString(ordinal)))throw conflict();
        }
        if(!exists) {
            if(segments.size()>=1000)throw new BusinessException(413,"本题录音分段过多，请保留原内容并联系教师");
            try{jdbc.update("INSERT INTO prepared_answer_segment(id,assessment_id,student_id,attempt_id,answer_id,ordinal,mime_type) VALUES (?,?,?,?,?,?,?)",scope.segmentId(),scope.assessmentId(),scope.studentId(),scope.attemptId(),scope.answerId(),ordinal,scope.mimeType());}
            catch(org.springframework.dao.DuplicateKeyException conflict){throw conflict();}
        }
        String key=scope.key(deployment);
        jdbc.update("INSERT INTO prepared_answer_chunk(assessment_id,student_id,attempt_id,answer_id,segment_id,chunk_index,byte_size,sha256,deployment_id,object_key,reserved_at,bundle_id,bundle_offset) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                scope.assessmentId(),scope.studentId(),scope.attemptId(),scope.answerId(),scope.segmentId(),scope.index(),scope.byteSize(),scope.sha256(),deployment,key,Timestamp.from(now),bundleId,offset);
        return new Row(scope,ordinal,deployment,key,now,null);
    }
    public List<Row> bundleRows(int student,int assessment,String attempt,String answer,String bundleId) {
        var offset=new java.util.concurrent.atomic.AtomicInteger();
        return jdbc.query("""
            SELECT c.*,s.ordinal,s.mime_type FROM prepared_answer_chunk c JOIN prepared_answer_segment s ON s.id=c.segment_id
            WHERE c.assessment_id=? AND c.student_id=? AND c.attempt_id=? AND c.answer_id=? AND c.bundle_id=? ORDER BY c.bundle_offset FOR UPDATE
            """,(rs,n)->{
                var chunk=row(rs);
                if(rs.getInt("bundle_offset")!=offset.getAndAdd(chunk.scope().byteSize())||!chunk.objectKey().equals(chunk.scope().key(chunk.deployment())))
                    throw new IllegalStateException("原批次分片位置无法核验");
                return chunk;
            },assessment,student,attempt,answer,bundleId);
    }
    public Row confirm(Row original,Instant now) {
        var scope=original.scope();if(now.isBefore(original.reserved()))throw new BusinessException(503,"服务器时间暂不可核验，请保留原分片");
        jdbc.update("UPDATE prepared_answer_chunk SET confirmed_at=COALESCE(confirmed_at,?) WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? AND segment_id=? AND chunk_index=?",
                Timestamp.from(now),scope.assessmentId(),scope.studentId(),scope.attemptId(),scope.answerId(),scope.segmentId(),scope.index());
        return row(scope).orElseThrow();
    }
    /** Caller validated the exact immutable group and current authorization in this same short transaction. */
    public void confirmBundle(AnswerBundle bundle,Instant now) {
        var first=bundle.chunks().getFirst();var rows=bundleRows(first.studentId(),first.assessmentId(),first.attemptId(),first.answerId(),bundle.id());
        if(rows.size()!=bundle.chunks().size())throw new IllegalStateException("原批次分片绑定缺失");
        for(int index=0;index<rows.size();index++) {
            var row=rows.get(index);
            if(!row.scope().equals(bundle.chunks().get(index)))throw new IllegalStateException("原批次分片绑定变化");
            if(now.isBefore(row.reserved()))throw new BusinessException(503,"服务器时间暂不可核验，请保留原批次");
            if(row.confirmed()!=null&&!row.confirmed().equals(now))throw new IllegalStateException("原批次确认时间不一致");
        }
        int matched=jdbc.update("UPDATE prepared_answer_chunk SET confirmed_at=COALESCE(confirmed_at,?) WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? AND bundle_id=?",
                Timestamp.from(now),first.assessmentId(),first.studentId(),first.attemptId(),first.answerId(),bundle.id());
        if(matched!=rows.size())throw new IllegalStateException("原批次分片确认未完整匹配");
    }
    private static BusinessException conflict(){return new BusinessException(409,"原录音分片绑定或内容不一致，不能覆盖，请保留原内容");}
}
