package io.clusterplatform;

import com.fasterxml.jackson.databind.*;
import io.clusterplatform.application.PlatformService;
import io.clusterplatform.domain.Contracts;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ContractsTest {
    private final ObjectMapper json=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    @Test void canonicalHashPreservesTypesOrderAndDelimiterBoundaries() throws Exception {
        assertThat(PlatformService.canonical(json.readTree("{\"b\":2,\"a\":1}"))).isEqualTo(PlatformService.canonical(json.readTree("{\"a\":1,\"b\":2}")));
        assertThat(PlatformService.canonical(json.readTree("{\"x\":\"1, y=2\"}"))).isNotEqualTo(PlatformService.canonical(json.readTree("{\"x\":1,\"y\":2}")));
        assertThat(PlatformService.canonical(json.readTree("[1,2]"))).isNotEqualTo(PlatformService.canonical(json.readTree("[2,1]")));
    }
    @Test void refusesUnknownFieldsAndOutOfRangeResources() {
        assertThatThrownBy(()->json.readValue("{\"repository\":\"x\",\"tag\":\"v1\",\"admin\":true}",Contracts.Upload.class)).isInstanceOf(Exception.class);
        try(var validator=Validation.buildDefaultValidatorFactory()) {
            assertThat(validator.getValidator().validate(new Contracts.Resources(-1,0,9000))).hasSize(3);
            assertThat(validator.getValidator().validate(new Contracts.Upload("../../secret","v1"))).isNotEmpty();
        }
    }
}
