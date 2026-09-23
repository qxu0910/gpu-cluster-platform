package io.clusterplatform.worker;

import io.clusterplatform.persistence.Store;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

@Component
public class OperationMetrics {
    public OperationMetrics(Store store,MeterRegistry registry) {
        for(String status:new String[]{"pending","running","blocked","unknown","failed"}) {
            Gauge.builder("platform.operations",store,s->s.jdbc.queryForObject("SELECT count(*) FROM operation WHERE status=?",Double.class,status)).tag("status",status).register(registry);
        }
        Gauge.builder("platform.observation.oldest.seconds",store,s->s.jdbc.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM now()-min(observed_at)),0) FROM resource WHERE kind='workload' AND NOT deleted",Double.class)).register(registry);
    }
}
