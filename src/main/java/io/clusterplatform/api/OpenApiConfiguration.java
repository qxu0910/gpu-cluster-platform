package io.clusterplatform.api;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfiguration {
    @Bean OpenAPI apiContract() {
        return new OpenAPI().info(new Info().title("GPU Cluster Platform").version("v1"))
            .components(new Components().addSecuritySchemes("bearer",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
            .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
    @Bean OpenApiCustomizer mutations() {
        return api->api.getPaths().forEach((path,item)->item.readOperationsMap().forEach((method,operation)->{
            if(!path.startsWith("/v1")) return;
            operation.getResponses().addApiResponse("401",new ApiResponse().description("Missing or invalid authentication"));
            operation.getResponses().addApiResponse("403",new ApiResponse().description("Caller or scope not permitted"));
            if((method==PathItem.HttpMethod.POST || method==PathItem.HttpMethod.DELETE) && !path.endsWith("/credentials")) {
                operation.addParametersItem(new Parameter().name("Idempotency-Key").in("header").required(true).description("Caller/method/path scoped; retained 7 days").schema(new StringSchema().maxLength(128)));
                operation.getResponses().remove("200");
                operation.getResponses().addApiResponse("202",new ApiResponse().description("Persisted operation accepted; poll Location").content(new Content().addMediaType("application/json",new MediaType().schema(new ObjectSchema().addProperty("operation_id",new StringSchema()).addProperty("target_id",new StringSchema()).addProperty("status",new StringSchema())))));
                operation.getResponses().addApiResponse("400",new ApiResponse().description("Invalid or unknown fields"));
                operation.getResponses().addApiResponse("409",new ApiResponse().description("Idempotency, state or version conflict"));
                operation.getResponses().addApiResponse("503",new ApiResponse().description("Persistence unavailable; operation not accepted"));
            }
        }));
    }
}
