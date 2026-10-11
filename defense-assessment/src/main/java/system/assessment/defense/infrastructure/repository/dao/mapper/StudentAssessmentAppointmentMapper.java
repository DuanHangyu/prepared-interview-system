package system.assessment.defense.infrastructure.repository.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentAppointmentPO;

import java.util.List;

public interface StudentAssessmentAppointmentMapper extends BaseMapper<StudentAssessmentAppointmentPO> {

    @Select("""
            <script>
            select saa.*, aas.location 
            from student_assessment_appointment saa
            left join assessment_appointment_setting aas on (saa.assessment_id = aas.assessment_id and saa.time_period = aas.time_period)
            where saa.student_id = #{studentId}
            </script>
            """)
    List<StudentAssessmentAppointmentPO> selectWithLocationByStudentId(@Param("studentId") Integer studentId);

    @Select("""
            <script>
            select saa.*, aas.location 
            from student_assessment_appointment saa
            left join assessment_appointment_setting aas on (saa.assessment_id = aas.assessment_id and saa.time_period = aas.time_period)
            where saa.student_id = #{studentId}
            and saa.assessment_id = #{assessmentId}
            </script>
            """)
    StudentAssessmentAppointmentPO getWithLocationByStudentIdAndAssessmentId(
            @Param("studentId") Integer studentId,
            @Param("assessmentId") Integer assessmentId
    );
}
