package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import system.assessment.defense.prepared.jobs.PreparedWorkerSchedulers;
import system.assessment.defense.prepared.processing.*;
import system.assessment.defense.prepared.attempt.PreparedAnswerMediaWorker;
import system.assessment.defense.prepared.speech.PreparedTtsWorker;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class PreparedWorkerSchedulerTest {
    @Test void slowModelDoesNotBlockOriginalMediaReceiptWork()throws Exception {
        try(var context=new AnnotationConfigApplicationContext(PreparedWorkerSchedulers.class)){
            var score=context.getBean("preparedScoreScheduler",ThreadPoolTaskScheduler.class);var media=context.getBean("preparedMediaScheduler",ThreadPoolTaskScheduler.class);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var saved=new CountDownLatch(1);var thread=new AtomicReference<String>();
            score.schedule(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException interruption){Thread.currentThread().interrupt();}},Instant.now());
            try{assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();media.schedule(()->{thread.set(Thread.currentThread().getName());saved.countDown();},Instant.now());
                assertThat(saved.await(2,TimeUnit.SECONDS)).isTrue();assertThat(thread.get()).startsWith("prepared-media-");}
            finally{release.countDown();}
            assertThat(context.getBean("taskScheduler")).isNotSameAs(score);assertThat(score.getPoolSize()).isEqualTo(1);assertThat(media.getPoolSize()).isEqualTo(1);
        }
    }
    @Test void actualWorkerAnnotationsUseIsolatedNamedSchedulers()throws Exception {
        assertThat(PreparedScoreWorker.class.getMethod("runOnce").getAnnotation(Scheduled.class).scheduler()).isEqualTo("preparedScoreScheduler");
        assertThat(PreparedAsrWorker.class.getMethod("runOnce").getAnnotation(Scheduled.class).scheduler()).isEqualTo("preparedAsrScheduler");
        assertThat(PreparedAnswerMediaWorker.class.getMethod("runOnce").getAnnotation(Scheduled.class).scheduler()).isEqualTo("preparedMediaScheduler");
        assertThat(PreparedTtsWorker.class.getMethod("runOnce").getAnnotation(Scheduled.class).scheduler()).isEqualTo("preparedPreparationScheduler");
    }
}
