package io.clusterplatform.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

public final class Contracts {
    private Contracts() {}
    public record Upload(@NotBlank @Pattern(regexp="[a-z0-9][a-z0-9-]{0,39}") String repository,
                         @NotBlank @Pattern(regexp="[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}") String tag) {}
    public record Complete(@Pattern(regexp="sha256:[a-f0-9]{64}") String digest) {}
    public record Resources(@Min(0) @Max(8) int gpuPerReplica, @DecimalMin("0.1") @DecimalMax("256") double cpuCores,
                            @DecimalMin("0.0625") @DecimalMax("2048") double memoryGib) {}
    public record Port(@Pattern(regexp="[a-z][a-z0-9-]{0,14}") @NotBlank String name, @Min(1) @Max(65535) int port,
                       @Pattern(regexp="TCP|UDP") @NotBlank String protocol) {}
    public record Probe(@NotBlank @Pattern(regexp="http|tcp|process") String type,
                        @Size(max=256) String path, @Min(0) @Max(65535) int port) {}
    public record Placement(@Pattern(regexp="[a-z0-9-]{1,63}") String nodePool,
                            @Size(max=64) List<@Pattern(regexp="[a-z0-9.-]{1,253}") String> allowedNodes) {}
    public record Workload(@NotBlank @Pattern(regexp="[a-z][a-z0-9-]{0,49}") String name,
        @NotBlank String imageId, @NotBlank String clusterId, @Pattern(regexp="service") @NotBlank String type,
        @Min(1) @Max(64) int replicas, @NotNull @Valid Resources resources, @Valid Placement placement,
        @Size(max=32) List<@NotBlank @Size(max=1024) String> command,
        @Size(max=64) List<@Size(max=4096) String> args,
        @Size(max=32) Map<@Pattern(regexp="[A-Za-z_][A-Za-z0-9_]{0,63}") String,@Size(max=4096) String> env,
        @NotEmpty @Size(max=16) List<@Valid Port> ports, @NotNull @Valid Probe readiness,
        @Valid Probe startup, @Min(1) @Max(600) int terminationGraceSeconds) {}
    public record Change(@Min(1) long expectedVersion, @Min(0) @Max(64) Integer replicas) {}
    public record Revision(@Min(1) long expectedVersion, @NotNull @Valid Workload configuration) {}
}
