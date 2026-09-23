package io.clusterplatform.adapters;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface RegistryAdapter {
    ObjectNode authorize(String uploadId,ObjectNode upload);
    ObjectNode verify(ObjectNode upload);
    void revoke(ObjectNode upload);
}
