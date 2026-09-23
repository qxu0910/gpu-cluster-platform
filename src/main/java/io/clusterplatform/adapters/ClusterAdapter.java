package io.clusterplatform.adapters;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface ClusterAdapter {
    ObjectNode reconcile(String id,long version,ObjectNode specification,boolean delete);
}
