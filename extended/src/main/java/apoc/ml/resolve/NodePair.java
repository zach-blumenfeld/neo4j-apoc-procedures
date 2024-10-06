package apoc.ml.resolve;

import org.neo4j.graphdb.Node;

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

    @Override
    public int hashCode() {
        String pairId = first.getElementId() + " __ " + second.getElementId();
        return pairId.hashCode();
    }
}
