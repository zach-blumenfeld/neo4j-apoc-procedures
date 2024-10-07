package apoc.ml.resolve;

import com.google.common.collect.Lists;
import org.junit.Test;
import org.neo4j.graphdb.Node;

import java.util.*;

public class ConnectedComponentsTest {
    @Test
    public void test() {

        ConnectedComponents cc = new ConnectedComponents();
        Set<Node> nodes = new HashSet<>();
        nodes.add(new TestNode("e1"));
        nodes.add(new TestNode("e2"));
        // create cartisian product for comparing (user should use blocking strategy in Cypher for large comparisons)
        Set<NodePair> nodePairs = makeNodePairs(nodes);

        // Perform entity linking in batches and resolve into weakly connected components (WCC)
        ConnectedComponents entityComponents = new ConnectedComponents();
        for (List<NodePair> partition : Lists.partition(new ArrayList<NodePair>(nodePairs),  5)) {
            entityComponents.addNodePairs(findEntityLinks(partition, new ArrayList<>())); //batched entity linking
        }

    }

    private Set<NodePair> makeNodePairs(Collection<Node> nodes) {
        Set<NodePair> pairs = new HashSet<NodePair>();
        for (Node node : nodes) {
            for(Node innerNode : nodes) {
                pairs.add(new NodePair(node, innerNode));
            }
        }
        return pairs;
    }

    private Set<NodePair> findEntityLinks(Collection<NodePair> nodePairs, List<String> resolutionProperties) {
        //TODO: Make a Real LLM Call Here
        Set<NodePair> entityLinkPairs = new HashSet<>();
        for (NodePair nodePair : nodePairs) {
            if (Math.random() < 1.0){ //always match
                entityLinkPairs.add(nodePair);
            }
        }
        return entityLinkPairs;
    }
}
