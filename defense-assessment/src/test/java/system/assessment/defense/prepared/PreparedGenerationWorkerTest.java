package system.assessment.defense.prepared;

import org.junit.jupiter.api.*;
import system.assessment.defense.prepared.generation.*;
import system.assessment.defense.prepared.jobs.PreparedJobLedger;
import system.assessment.defense.prepared.jobs.PreparedJobLedger.*;
import system.assessment.defense.application.service.ResourceAccessControlService;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PreparedGenerationWorkerTest {
    PreparedJobLedger ledger; PreparedGenerationPolicy policy; PreparedGenerationClient client;
    ResourceAccessControlService access; PreparedGenerationWorker worker; Lease lease;
    final String content = "{\"questions\":[{\"body\":\"问题\",\"rubric\":\"标准\",\"maxScore\":10,\"answerSeconds\":120}]}";
    @BeforeEach void setup() {
        ledger = mock(PreparedJobLedger.class); policy = mock(PreparedGenerationPolicy.class);
        client = mock(PreparedGenerationClient.class); access = mock(ResourceAccessControlService.class);
        var snapshot = new PreparedGenerationPolicyTest().configured();
        var actual = new PreparedGenerationPolicy(snapshot, new PreparedGenerationPolicyTest().clock).snapshot();
        var command = new PreparedGeneration.Command(0L, UUID.randomUUID().toString(), "教师要求", 1, 10, 120);
        var payload = PreparedGeneration.encode(new PreparedGeneration.Input(command, actual));
        lease = new Lease(new Job(UUID.randomUUID().toString(), 21, 4, Kind.QUESTIONS, 0, payload, null, State.RUNNING, 1), 1, "generation-worker");
        when(policy.workerEnabled()).thenReturn(true); when(policy.snapshot()).thenReturn(actual);
        when(policy.availability()).thenReturn(new PreparedGenerationPolicy.Availability(true, "可用"));
        when(policy.apiKey()).thenReturn("not-a-cloud-key");
        when(policy.budgetCap()).thenReturn(new BigDecimal("100"));when(policy.pauseAt()).thenReturn(new BigDecimal("80"));
        when(policy.actualCost(any(), anyLong(), anyLong())).thenReturn(new BigDecimal("0.01"));
        when(ledger.claim(Kind.QUESTIONS, "generation-worker", 300)).thenReturn(Optional.of(lease));
        when(ledger.reserve(eq(lease), anyString(), anyString(), any())).thenReturn(new Decision(true, ""));
        when(ledger.markDispatched(eq(lease), anyString())).thenReturn(true);
        worker = new PreparedGenerationWorker(ledger, policy, client, new PreparedGenerationCodec(), access);
    }
    @Test void disabledWorkerNeverClaimsOrCallsCloud() { when(policy.workerEnabled()).thenReturn(false); worker.runOnce(); verifyNoInteractions(ledger, client); }
    @Test void persistsDispatchThenSettlesKnownUsageBeforePublishingSuggestions() throws Exception {
        when(client.generate(any(), any(), anyString())).thenReturn(new PreparedGenerationClient.Reply("fixture-provider", 100, 100, content, "stop"));
        worker.runOnce();
        var order = inOrder(ledger, client);
        order.verify(ledger).claim(Kind.QUESTIONS, "generation-worker", 300);
        order.verify(ledger).initializeBudget(anyString(), any(), any());
        order.verify(ledger).reserve(eq(lease), anyString(), anyString(), any());
        order.verify(ledger).markDispatched(eq(lease), anyString());
        order.verify(client).generate(any(), any(), anyString());
        order.verify(ledger).settle(anyString(), eq(new BigDecimal("0.01")), eq("fixture-provider"));
        order.verify(ledger).succeed(eq(lease), eq(content));
    }
    @Test void unknownAfterSendKeepsReservationAndCannotRetryPaidCall() throws Exception {
        when(client.generate(any(), any(), anyString())).thenThrow(new java.io.IOException("private provider details"));
        worker.runOnce(); verify(ledger).uncertain(anyString()); verify(ledger).fail(lease, "AMBIGUOUS_CALL", false);
        verify(ledger, never()).cancelBeforeDispatch(anyString()); verify(ledger, never()).succeed(any(), anyString());
    }
    @Test void invalidButChargedOutputIsAccountedAndNeverMutatesDraft() throws Exception {
        when(client.generate(any(), any(), anyString())).thenReturn(new PreparedGenerationClient.Reply("fixture-provider", 100, 100, "{}", "stop"));
        worker.runOnce(); verify(ledger).settle(anyString(), any(), eq("fixture-provider"));
        verify(ledger).fail(lease, "INVALID_OUTPUT", false); verify(ledger, never()).uncertain(anyString());
    }
    @Test void noDispatchPermissionBudgetOrCurrentResourceAuthorizationMeansNoPaidCall() {
        when(ledger.markDispatched(eq(lease), anyString())).thenReturn(false);
        worker.runOnce(); verifyNoInteractions(client); verify(ledger).cancelBeforeDispatch(anyString());
        reset(ledger); when(ledger.claim(Kind.QUESTIONS,"generation-worker",300)).thenReturn(Optional.of(lease));
        doThrow(new IllegalArgumentException("revoked")).when(access).requireAssessmentManager(4,21);
        worker.runOnce(); verifyNoInteractions(client); verify(ledger).fail(lease,"CONFIG_OR_ACCESS_CHANGED",false);
    }
    @Test void emptyQueueAndDeniedReservationMakeNoCloudRequest() {
        when(ledger.claim(Kind.QUESTIONS,"generation-worker",300)).thenReturn(Optional.empty());worker.runOnce();verifyNoInteractions(client);
        when(ledger.claim(Kind.QUESTIONS,"generation-worker",300)).thenReturn(Optional.of(lease));
        when(ledger.reserve(eq(lease),anyString(),anyString(),any())).thenReturn(new Decision(false,"PAUSED"));
        worker.runOnce();verifyNoInteractions(client);verify(ledger).fail(lease,"BUDGET_PAUSED",false);
    }
    @Test void truncatedKnownUsageResponseIsChargedButNotShownAsCompleteSuggestions() throws Exception {
        when(client.generate(any(),any(),anyString())).thenReturn(new PreparedGenerationClient.Reply("fixture-provider",100,100,content,"length"));
        worker.runOnce();verify(ledger).settle(anyString(),any(),eq("fixture-provider"));verify(ledger).fail(lease,"INVALID_OUTPUT",false);
        verify(ledger,never()).succeed(any(),anyString());
    }
}
