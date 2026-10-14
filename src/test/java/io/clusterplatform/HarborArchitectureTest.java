package io.clusterplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.clusterplatform.adapters.DependencyException;
import io.clusterplatform.adapters.HarborAdapter;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HarborArchitectureTest {
    private final ObjectMapper json=new ObjectMapper();
    private final String digest="sha256:"+"a".repeat(64);
    @Test void acceptsSupportedIndexArchitecturesAndRejectsAmbiguousReferences() throws Exception {
        var index=json.readTree("""
            {"manifest_media_type":"application/vnd.oci.image.index.v1+json","references":[
              {"child_digest":"%s","platform":{"os":"linux","architecture":"arm64"}},
              {"child_digest":"%s","platform":{"os":"linux","architecture":"amd64"}},
              {"child_digest":"%s","platform":{"os":"unknown","architecture":"unknown"}}
            ]}
            """.formatted(digest,digest,digest));
        assertThat(HarborAdapter.architectures(index)).containsExactly("amd64","arm64");
        ((com.fasterxml.jackson.databind.node.ObjectNode)index.path("references").get(1).path("platform")).put("architecture","arm64");
        assertThatThrownBy(()->HarborAdapter.architectures(index)).isInstanceOf(DependencyException.class)
            .hasMessage("invalid_image_index");
    }
    @Test void rejectsIndexWithoutDeployableLinuxManifest() throws Exception {
        var index=json.readTree("""
            {"manifest_media_type":"application/vnd.docker.distribution.manifest.list.v2+json","references":[
              {"child_digest":"%s","platform":{"os":"windows","architecture":"amd64"}}
            ]}
            """.formatted(digest));
        assertThatThrownBy(()->HarborAdapter.architectures(index)).isInstanceOf(DependencyException.class)
            .hasMessage("unsupported_image_platform");
    }
    @Test void keepsSinglePlatformBehaviorForAmd64AndArm64() throws Exception {
        var artifact=json.readTree("""
            {"extra_attrs":{"os":"linux","architecture":"amd64"}}
            """);
        assertThat(HarborAdapter.architectures(artifact)).containsExactly("amd64");
        ((com.fasterxml.jackson.databind.node.ObjectNode)artifact.path("extra_attrs")).put("architecture","arm64");
        assertThat(HarborAdapter.architectures(artifact)).containsExactly("arm64");
        ((com.fasterxml.jackson.databind.node.ObjectNode)artifact.path("extra_attrs")).put("os","windows");
        assertThatThrownBy(()->HarborAdapter.architectures(artifact)).hasMessage("unsupported_image_platform");
    }
}
