package system.assessment.defense.prepared.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.util.*;
import java.util.function.Supplier;

/** Parent-lock protocol shared with publication/start, including the delegated legacy write. */
@Component
public class PreparedLegacyRecordGuard {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PreparedLegacyRecordGuard(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
    }

    public <T> T record(int actor, int recordId, Supplier<T> operation) {
        if (actor < 1 || recordId < 1) throw new IllegalArgumentException("身份或记录标识无效");
        return tx.execute(status -> {
            var rows = jdbc.queryForList("SELECT assessment_id FROM student_assessment_record WHERE id=?", Integer.class, recordId);
            if (rows.isEmpty()) throw denied();
            int assessment = rows.getFirst();
            int owner = parent(assessment);
            var current = jdbc.queryForList("SELECT assessment_id FROM student_assessment_record WHERE id=? FOR UPDATE", Integer.class, recordId);
            if (current.isEmpty() || current.getFirst() != assessment) throw denied();
            requireManager(actor, assessment, owner);
            if (!jdbc.queryForList("SELECT id FROM prepared_attempt WHERE record_id=? FOR UPDATE", String.class, recordId).isEmpty()) throw incompatible();
            return operation.get();
        });
    }

    /** Acquire all parents before actor/record locks. Otherwise two batch exports with the
     * same manager can deadlock by retaining the actor lock while waiting on another parent. */
    public <T> T records(int actor, Map<Integer,Integer> bindings, Supplier<T> operation) {
        if (actor < 1 || bindings == null || bindings.size() > 1000 || bindings.entrySet().stream()
                .anyMatch(e -> e.getKey() == null || e.getKey() < 1 || e.getValue() == null || e.getValue() < 1)) throw new IllegalArgumentException("身份或记录范围无效");
        return tx.execute(status -> {
            var owners = new HashMap<Integer,Integer>();
            if (bindings.isEmpty()) { int currentRole = role(actor); if (currentRole != 1 && currentRole != 2) throw denied(); }
            bindings.values().stream().distinct().sorted().forEach(assessment -> owners.put(assessment, parent(assessment)));
            for (int assessment : owners.keySet().stream().sorted().toList()) requireManager(actor, assessment, owners.get(assessment));
            for (var entry : bindings.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                var current = jdbc.queryForList("SELECT assessment_id FROM student_assessment_record WHERE id=? FOR UPDATE", Integer.class, entry.getKey());
                if (current.size() != 1 || !entry.getValue().equals(current.getFirst())) throw denied();
                if (!jdbc.queryForList("SELECT id FROM prepared_attempt WHERE record_id=? FOR UPDATE", String.class, entry.getKey()).isEmpty()) throw incompatible();
            }
            return operation.get();
        });
    }

    public <T> T material(int student, int assessment, Supplier<T> operation) {
        if (student < 1 || assessment < 1) throw new IllegalArgumentException("身份或考核标识无效");
        return tx.execute(status -> {
            parent(assessment);
            if (role(student) != 0 || jdbc.queryForList("SELECT id FROM assessment_student_relation WHERE assessment_id=? AND student_id=? FOR UPDATE",
                    Integer.class, assessment, student).isEmpty()) throw denied();
            // An empty prepared draft still declares the mode. No old PDF command may create/overwrite its anchor.
            if (!jdbc.queryForList("SELECT assessment_id FROM prepared_paper_draft WHERE assessment_id=? FOR UPDATE", Integer.class, assessment).isEmpty()
                    || !jdbc.queryForList("SELECT assessment_id FROM prepared_paper_gate WHERE assessment_id=? FOR UPDATE", Integer.class, assessment).isEmpty()) throw incompatible();
            return operation.get();
        });
    }

    /** Serialize with prepared start/publication, and check current student scope before mode.
     * Report construction/signed legacy URLs remain inside this same guarded transaction. */
    public <T> T studentDetail(int student, int assessment, Supplier<T> operation) {
        if (student < 1 || assessment < 1) throw new IllegalArgumentException("身份或考核标识无效");
        return tx.execute(status -> {
            parent(assessment);
            if (role(student) != 0 || jdbc.queryForList("SELECT id FROM assessment_student_relation WHERE assessment_id=? AND student_id=? FOR UPDATE",
                    Integer.class, assessment, student).isEmpty()) throw denied();
            var records = jdbc.queryForList("SELECT id FROM student_assessment_record WHERE assessment_id=? AND student_id=? FOR UPDATE", Integer.class, assessment, student);
            for (int record : records) if (!jdbc.queryForList("SELECT id FROM prepared_attempt WHERE record_id=? FOR UPDATE", String.class, record).isEmpty()) throw incompatible();
            return operation.get();
        });
    }

    private int parent(int assessment) {
        var owners = jdbc.queryForList("SELECT create_id FROM assessment WHERE id=? AND deleted=0 FOR UPDATE", Integer.class, assessment);
        if (owners.isEmpty()) throw denied();
        return owners.getFirst();
    }

    private int role(int actor) {
        var roles = jdbc.queryForList("SELECT type FROM `user` WHERE id=? FOR UPDATE", Integer.class, actor);
        if (roles.isEmpty()) throw denied();
        return roles.getFirst();
    }

    private void requireManager(int actor, int assessment, int owner) {
        int role = role(actor);
        if (role == 2 || (role == 1 && (actor == owner || !jdbc.queryForList(
                "SELECT id FROM assessment_invite WHERE assessment_id=? AND invitee_teacher_id=? FOR UPDATE",
                Integer.class, assessment, actor).isEmpty()))) return;
        throw denied();
    }

    private static BusinessException denied() { return new BusinessException(403, "无权限或资源不存在"); }
    private static BusinessException incompatible() { return new BusinessException(409, "预置考核不能使用旧答辩流程，请使用新版流程并保留原内容；新版报告尚未开放"); }
}
