package system.assessment.defense.prepared.jobs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Slow paid IO must not occupy the original-receipt media lane. Small fixed pools are local
 * safety bounds, NOT certified vendor quotas/100-student processing capacity. Jobs stay in DB;
 * no in-memory per-student executor queue is used as a persistence substitute. */
@Configuration
public class PreparedWorkerSchedulers {
    @Bean("taskScheduler") public ThreadPoolTaskScheduler defaultScheduler(){return pool("background-",1);}
    @Bean("preparedMediaScheduler") public ThreadPoolTaskScheduler mediaScheduler(){return pool("prepared-media-",1);}
    @Bean("preparedAsrScheduler") public ThreadPoolTaskScheduler asrScheduler(){return pool("prepared-asr-",1);}
    @Bean("preparedScoreScheduler") public ThreadPoolTaskScheduler scoreScheduler(){return pool("prepared-score-",1);}
    @Bean("preparedPreparationScheduler") public ThreadPoolTaskScheduler preparationScheduler(){return pool("prepared-preparation-",2);}
    private static ThreadPoolTaskScheduler pool(String prefix,int size){
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(size);scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);return scheduler;
    }
}
