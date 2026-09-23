package io.clusterplatform.worker;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulingConfiguration {
    @Bean public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("platform-reconcile-"); scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
