package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @USER taoHouChao
 * @DATE 19:42 2025/8/14
 */
@Data
@Schema(description = "学生答辩统计信息")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StudentDefenseStatisticDTO {

    @Schema(description = "全部考试")
    private Integer all;

    @Schema(description = "待预约")
    private Integer toAppoint;

    @Schema(description = "待完成")
    private Integer todo;

    @Schema(description = "分析中")
    private Integer analysis;

    @Schema(description = "已完成")
    private Integer done;

    public static StudentDefenseStatisticDTO empty(){
        return StudentDefenseStatisticDTO.builder()
                .all(0)
                .todo(0)
                .analysis(0)
                .done(0)
                .build();
    }
}
