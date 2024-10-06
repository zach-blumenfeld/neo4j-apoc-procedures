package apoc.ml.resolve;

import org.neo4j.graphdb.Node;

import java.util.*;


public class ConnectedComponents {
    private final Map<Node, Integer> nodeComponentMap = new HashMap<>();
    private final Map<Integer, Set<Node>> components = new HashMap<>();
    private int nextComponentId = 0;

    public ConnectedComponents() {
    }

    public void addNodePair(NodePair nodePair) {
        //look for nodes
        int firstComponentId = this.nodeComponentMap.getOrDefault(nodePair.getFirst(), -1);
        int secondComponentId = this.nodeComponentMap.getOrDefault(nodePair.getSecond(), -1);
        boolean containsFirst = firstComponentId > -1;
        boolean containsSecond = secondComponentId > -1;

        if(containsFirst && containsSecond){ //join components by merging second into first
            mergeComponents(firstComponentId, secondComponentId);
        }else if(containsFirst){ //add second node to existing component
            insertNode(nodePair.getSecond(), firstComponentId);
        }else if(containsSecond){//add first node to existing component
            insertNode(nodePair.getFirst(), secondComponentId);
        }else{//create new component and add both nodes
            insertNewComponent(nodePair);
        }
    }

    public void addNodePairs(Collection<NodePair> nodePairs) {
        System.out.println("====== APOC =======");
        System.out.println("adding node pairs: " + nodePairs.size());
        System.out.println("====== APOC =======");
        for (NodePair nodePair : nodePairs) {
            System.out.println("====== APOC =======");
            System.out.println("adding node pair: " + nodePair.getFirst().getElementId() + "  __  " + nodePair.getSecond().getElementId());
            System.out.println("====== APOC =======");
            addNodePair(nodePair);
        }
    }

    private void insertNode(Node node, int componentId){
        this.nodeComponentMap.put(node, componentId);
        this.components.get(componentId).add(node);
    }

    private void mergeComponents(int firstComponentId, int secondComponentId){
        if (firstComponentId != secondComponentId){
            //get current second component
            Set<Node> secondComponent = this.components.get(secondComponentId);
            //update node map
            for (Node node : secondComponent) {
                this.nodeComponentMap.put(node, firstComponentId);
            }
            //merge second component
            this.components.get(firstComponentId).addAll(secondComponent);
            //remove second component
            this.components.remove(secondComponentId);
        }

    }

    private void insertNewComponent(NodePair nodePair){
        //new component
        this.components.put(this.nextComponentId, new HashSet<>(Arrays.asList(nodePair.getFirst(), nodePair.getSecond())));
        //insert both nodes in map
        this.nodeComponentMap.put(nodePair.getFirst(), this.nextComponentId);
        this.nodeComponentMap.put(nodePair.getSecond(), this.nextComponentId);
        //iterate component id
        this.nextComponentId++;
    }

    public Map<Integer, Set<Node>> getComponents() {
        return components;
    }
}
