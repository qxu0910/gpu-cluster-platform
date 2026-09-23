package io.clusterplatform.persistence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.clusterplatform.domain.ApiException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Store {
    public final JdbcTemplate jdbc;
    public final ObjectMapper json;
    public Store(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public ObjectNode parse(String value) { try { return (ObjectNode)json.readTree(value); } catch(Exception x) { throw new IllegalStateException("invalid_stored_json"); } }
    public ObjectNode object(Object value) { return json.valueToTree(value); }
    public ObjectNode resource(String project,String kind,String id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM resource WHERE id=? AND project=? AND kind=? AND NOT deleted"+(lock?" FOR UPDATE":""),
            (rs,n)-> { ObjectNode body=parse(rs.getString("body")); body.put("id",id); body.put("version",rs.getLong("version"));
                body.set("observed",parse(rs.getString("observed"))); if(rs.getTimestamp("observed_at")!=null) body.put("observed_at",rs.getTimestamp("observed_at").toInstant().toString()); return body; },id,project,kind);
        if(rows.isEmpty()) throw new ApiException(404,"resource_not_found"); return rows.getFirst();
    }
    public String insert(String kind,String project,ObjectNode body) {
        String id=kind+"-"+UUID.randomUUID(); jdbc.update("INSERT INTO resource(id,kind,project,body) VALUES (?,?,?,?::jsonb)",id,kind,project,body.toString()); return id;
    }
    public void save(String id,long version,ObjectNode body) {
        body.remove(List.of("id","version","observed","observed_at"));
        jdbc.update("UPDATE resource SET body=?::jsonb,version=? WHERE id=?",body.toString(),version,id);
    }
    public List<ObjectNode> list(String project,String kind,int offset,int limit) {
        if(offset<0 || limit<1 || limit>100) throw new ApiException(400,"invalid_pagination");
        return jdbc.query("SELECT id FROM resource WHERE project=? AND kind=? AND NOT deleted ORDER BY created_at,id LIMIT ? OFFSET ?",
            (rs,n)->rs.getString(1),project,kind,limit,offset).stream().map(id->resource(project,kind,id,false)).toList();
    }
    public ObjectNode operation(String project,String id) {
        var rows=jdbc.query("SELECT * FROM operation WHERE id=? AND project=?",(rs,n)-> {
            ObjectNode o=json.createObjectNode(); for(String field:List.of("id","target_id","action","status","stage")) o.put(field,rs.getString(field));
            o.put("desired_generation",rs.getLong("generation")); o.put("observed_at",rs.getTimestamp("updated_at").toInstant().toString());
            if(rs.getString("error_code")!=null) o.putObject("error").put("code",rs.getString("error_code")).put("retryable",!rs.getString("status").equals("failed")); return o;
        },id,project);
        if(rows.isEmpty()) throw new ApiException(404,"resource_not_found"); return rows.getFirst();
    }
}
