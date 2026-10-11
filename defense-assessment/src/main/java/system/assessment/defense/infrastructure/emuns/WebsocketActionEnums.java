package system.assessment.defense.infrastructure.emuns;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Objects;
import java.util.Set;

@Getter
@AllArgsConstructor
public enum WebsocketActionEnums{
    PING("ping"),
    START_DEFENSE("start-defense"),
    END_DEFENSE("end-defense"),
    START_ASSESSMENT("start-assessment"),
    END_ASSESSMENT("end-assessment"),
    START_ANSWER("start-answer"),
    END_ANSWER("end-answer"),
    GENERATE_QUESTION("generate_question"),
    STOP("stop"),
    ;

    private final String action;

    private static final Set<String> ASSESSMENT_ACTIONS = Set.of(
            START_DEFENSE.action,
            END_DEFENSE.action,
            START_ASSESSMENT.action,
            END_ASSESSMENT.action,
            START_ANSWER.action,
            END_ANSWER.action,
            GENERATE_QUESTION.action
    );

    public static boolean isPing(String action){
        return Objects.equals(PING.action, action);
    }

    public static boolean isAssessmentAction(String action) {
        return ASSESSMENT_ACTIONS.contains(action);
    }
}
