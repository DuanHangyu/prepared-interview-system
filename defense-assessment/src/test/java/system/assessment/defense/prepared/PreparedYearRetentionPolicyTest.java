package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import system.assessment.defense.prepared.retention.PreparedYearRetentionPolicy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.*;
import static system.assessment.defense.prepared.retention.PreparedYearRetentionPolicy.*;

/** Pure date calculation and configuration contract, NOT cleanup/storage integration. */
class PreparedYearRetentionPolicyTest {
    private final ZoneId zone=ZoneId.of("Asia/Shanghai");
    private Instant instant(String local){return LocalDateTime.parse(local).atZone(zone).toInstant();}

    @ParameterizedTest @CsvSource({
        "2026-10-06T17:12:34.123456789,2027-10-06T17:12:34.123456789",
        "2023-10-06T17:12:34.123456789,2024-10-06T17:12:34.123456789",
        "2024-02-29T23:59:59.999999999,2025-02-28T23:59:59.999999999",
        "2024-02-29T00:00:00,2025-02-28T00:00:00",
        "2023-01-31T01:02:03,2024-01-31T01:02:03",
        "2023-03-01T00:00:00.000001,2024-03-01T00:00:00.000001",
        "2026-12-31T23:59:59.999999999,2027-12-31T23:59:59.999999999",
        "2027-01-01T00:00:00.000000001,2028-01-01T00:00:00.000000001"
    })
    void naturalAnniversaryKeepsBeijingWallTimeAndFullInputPrecision(String origin,String expected){
        Instant anchor=instant(origin),due=instant(expected);
        var before=inspect(anchor,due.minusNanos(1));
        assertThat(before.state()).isEqualTo(State.WITHIN_RETENTION);
        assertThat(before.dueAt()).isEqualTo(due);
        assertThat(before.protection()).isNull();
        assertThat(inspect(anchor,due).state()).isEqualTo(State.DUE);
        assertThat(inspect(anchor,due.plusNanos(1)).state()).isEqualTo(State.DUE);
        assertThat(inspect(anchor,anchor).state()).isEqualTo(State.WITHIN_RETENTION);
    }
    @Test void calendarYearMustNotBeReplacedBy365DaysOrUtcDateArithmetic(){
        Instant anchor=instant("2023-03-01T00:00:00"),due=instant("2024-03-01T00:00:00");
        assertThat(due.getEpochSecond()-anchor.getEpochSecond()).isEqualTo(366L*86400);
        assertThat(inspect(anchor,anchor.plusSeconds(365L*86400)).state()).isEqualTo(State.WITHIN_RETENTION);
        // Beijing Feb29 is still Feb28 UTC: UTC anniversary would retain the wrong local date.
        var leap=inspect(instant("2024-02-29T00:30:00"),instant("2025-02-28T00:30:00"));
        assertThat(leap.dueAt()).isEqualTo(Instant.parse("2025-02-27T16:30:00Z"));
    }
    @Test void missingAnchorIsProtectedRatherThanGuessedFromTheCurrentTime(){
        var decision=inspect(null,Instant.parse("2026-10-06T00:00:00Z"));
        assertThat(decision.state()).isEqualTo(State.PROTECTED);
        assertThat(decision.protection()).isEqualTo(Protection.NO_ANCHOR);
        assertThat(decision.dueAt()).isNull();
    }
    @Test void futureAnchorIsProtectedInsteadOfBecomingCleanupEligibility(){
        var now=Instant.parse("2026-10-06T00:00:00Z");
        var decision=inspect(now.plusNanos(1),now);
        assertThat(decision.state()).isEqualTo(State.PROTECTED);
        assertThat(decision.protection()).isEqualTo(Protection.INVALID_TIMELINE);
        assertThat(decision.dueAt()).isNull();
    }
    @Test void absentTrustedServerTimeIsRejected(){
        assertThatThrownBy(()->inspect(Instant.EPOCH,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->inspect(null,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={
        "-1000000000-01-01T00:00:00Z","+1000000000-12-31T23:59:59.999999999Z",
        "+999999999-12-30T00:00:00Z"
    })
    void unrepresentableOrOverflowingCalendarYearProtectsWithoutThrowing(String value){
        var decision=inspect(Instant.parse(value),Instant.MAX);
        assertThat(decision.state()).isEqualTo(State.PROTECTED);
        assertThat(decision.protection()).isEqualTo(Protection.INVALID_TIMELINE);
        assertThat(decision.dueAt()).isNull();
    }
    @ParameterizedTest @CsvSource({
        "1990-04-14T02:30:00,1991-04-14T02:30:00,0",
        "1990-09-15T01:30:00,1991-09-15T01:30:00,2"
    })
    void historicalAmbiguousOrMissingAnniversaryDoesNotSilentlyChangeWallTime(String origin,String target,int offsets){
        assertThat(zone.getRules().getValidOffsets(LocalDateTime.parse(target))).hasSize(offsets);
        var decision=inspect(instant(origin),instant("2026-10-06T00:00:00"));
        assertThat(decision.state()).isEqualTo(State.PROTECTED);
        assertThat(decision.protection()).isEqualTo(Protection.INVALID_TIMELINE);
        assertThat(decision.dueAt()).isNull();
    }
    @Test void machineDefaultTimezoneCannotAlterTheApprovedTimezone(){
        var original=TimeZone.getDefault();
        try{
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertThat(RETENTION_ZONE).isEqualTo(zone);
            assertThat(RETENTION_PERIOD).isEqualTo(Period.ofYears(1));
            Instant anchor=instant("2024-02-29T00:30:00"),due=instant("2025-02-28T00:30:00");
            assertThat(inspect(anchor,due).dueAt()).isEqualTo(due);
        }finally{TimeZone.setDefault(original);}
    }
    @Test void configurationDescribesCalendarPeriodNotDurationAndLeavesOldDeletionOff(){
        var yaml=new YamlPropertiesFactoryBean();yaml.setResources(new ClassPathResource("application-prepared-local.yml"));
        var properties=yaml.getObject();assertThat(properties).isNotNull();
        assertThat(properties.getProperty("prepared.retention.results")).isEqualTo("P1Y");
        assertThat(properties.getProperty("prepared.retention.cleanup-audit")).isEqualTo("P1Y");
        assertThat(properties.getProperty("prepared.retention.time-zone")).isEqualTo(RETENTION_ZONE.getId());
        assertThat(properties.getProperty("prepared.retention.audio")).isEqualTo("P180D");
        assertThat(properties.getProperty("data-retention.enabled")).isEqualTo("false");
    }
    @Test void dateDueIsNotAnAuthorityToDeleteNorAnOriginSelectionService(){
        assertThat(PreparedYearRetentionPolicy.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
            .doesNotContain("delete","execute","schedule","selectAnchor","deleteCandidate");
        var at=instant("2026-10-06T08:30:00");
        assertThat(inspect(at,at).dueAt()).isEqualTo(instant("2027-10-06T08:30:00"));
        // Result completion and cleanup-operation timestamps stay separate, not reset to now.
        assertThat(inspect(at.plusSeconds(3600),at.plusSeconds(3600)).dueAt())
            .isEqualTo(instant("2027-10-06T09:30:00"));
    }
}
