package system.assessment.defense.infrastructure.config;

import java.lang.annotation.*;

/** Only a successful binary handler bypasses the JSON success envelope. Exceptions still
 * use their unannotated global exception handlers and retain safe JSON/status responses. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BinaryResponse {}
