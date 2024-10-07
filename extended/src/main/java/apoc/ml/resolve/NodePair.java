package apoc.ml.resolve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.graphdb.Node;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NodePair {
    private final Node first;
    private final Node second;

    public NodePair(Node node1, Node node2) {
        if(node1.getElementId().compareTo(node2.getElementId()) <= 0){
            this.first = node1;
            this.second = node2;
        } else {
            this.first = node2;
            this.second = node1;
        }
    }

    public Node getFirst() {
        return first;
    }

    public Node getSecond() {
        return second;
    }

    public String getComparisonString(List<String> compareProperties) throws JsonProcessingException {
        Map<String, Object> entityPairMap = new HashMap<>();
        String[] comparePropArray = compareProperties.stream().toArray(String[]::new);
        entityPairMap.put("entity1", first.getProperties(comparePropArray));
        entityPairMap.put("entity2", second.getProperties(comparePropArray));
        return new ObjectMapper().writeValueAsString(entityPairMap);
    }

    @Override
    public int hashCode() {
        String pairId = first.getElementId() + " __ " + second.getElementId();
        return pairId.hashCode();
    }
}
