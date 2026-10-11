package system.assessment.defense.prepared.processing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.config.PreparedLegacyRecordGuard;
import java.util.*;

/** Bounded metadata only: prepared attempts must never masquerade as legacy integer grades.
 * Fresh server-side scope checks precede mode disclosure. No model/storage IO or writes. */
@Component
public class PreparedRecordBridge {
    public record Info(int assessmentId,boolean prepared,String state){}
    private final JdbcTemplate jdbc;
    private final PreparedLegacyRecordGuard guard;
    public PreparedRecordBridge(JdbcTemplate jdbc,PreparedLegacyRecordGuard guard){this.jdbc=jdbc;this.guard=guard;}

    public void requireManagerIdentity(int actor){
        if(actor<1)throw new IllegalArgumentException("身份标识无效");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id=? AND type IN (1,2)",Integer.class,actor)!=1)throw denied();
    }
    public Map<Integer,Info> managerRecords(int actor,List<Integer> records){
        if(actor<1||records==null||records.size()>1000||records.stream().anyMatch(id->id==null||id<1))throw new IllegalArgumentException("记录范围无效，单次最多1000条");
        requireManagerIdentity(actor);if(records.isEmpty())return Map.of();
        var ids=new LinkedHashSet<>(records);var args=new ArrayList<Object>();args.add(actor);args.addAll(ids);
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var rows=jdbc.query("""
            SELECT r.id,r.assessment_id,EXISTS(SELECT 1 FROM prepared_attempt p WHERE p.record_id=r.id) is_prepared,t.state
            FROM student_assessment_record r JOIN assessment a ON a.id=r.assessment_id AND a.deleted=0
            JOIN `user` actor ON actor.id=? AND actor.type IN (1,2)
            LEFT JOIN prepared_attempt_anchor h ON h.record_id=r.id AND h.assessment_id=r.assessment_id AND h.student_id=r.student_id
            LEFT JOIN prepared_attempt t ON t.id=h.active_attempt_id AND t.record_id=r.id AND t.assessment_id=r.assessment_id AND t.student_id=r.student_id
            WHERE (actor.type=2 OR (actor.type=1 AND (a.create_id=actor.id OR EXISTS
                (SELECT 1 FROM assessment_invite i WHERE i.assessment_id=a.id AND i.invitee_teacher_id=actor.id))))
            AND r.id IN (
            """+placeholders+")",(rs,n)->Map.entry(rs.getInt("id"),new Info(rs.getInt("assessment_id"),rs.getBoolean("is_prepared"),rs.getString("state"))),args.toArray());
        if(rows.size()!=ids.size())throw denied();
        var result=new HashMap<Integer,Info>();
        for(var row:rows){var info=row.getValue();if(info.prepared()&&info.state()==null)throw new BusinessException(409,"原预置尝试状态无法核验，请保留原内容并由管理员处理");result.put(row.getKey(),info);}
        return Map.copyOf(result);
    }
    public void requireLegacyExport(int actor,List<Integer> records){
        var modes=managerRecords(actor,records);
        if(modes.values().stream().anyMatch(Info::prepared))throw new BusinessException(409,"预置面试不能使用旧报告导出，请在预置面试评阅页核验原回答；新版报告尚未开放");
        // The caller's export transaction retains each parent lock through report construction.
        // Recheck current role/invitation and prepared mode after waiting; never trust a snapshot.
        var bindings=new HashMap<Integer,Integer>();modes.forEach((record,info)->bindings.put(record,info.assessmentId()));
        guard.records(actor,bindings,()->true);
    }
    private static BusinessException denied(){return new BusinessException(403,"无权限或资源不存在");}
}
