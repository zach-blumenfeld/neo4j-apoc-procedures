/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package apoc.refactor;

import static apoc.ml.resolve.util.RefactorUtil.*;
import static dev.langchain4j.model.openai.OpenAiChatModelName.GPT_4_O_MINI;

import apoc.Pools;
import apoc.ml.resolve.ConnectedComponents;
import apoc.ml.resolve.NodePair;
import apoc.ml.resolve.AINodeResolver;
import apoc.ml.resolve.EntityLinkEnum;
import apoc.ml.resolve.util.PropertiesManager;
import apoc.ml.resolve.util.RefactorConfig;
import apoc.util.Util;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.Lists;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;
import org.neo4j.procedure.*;

public class GraphResolution {
    @Context
    public Transaction tx;

    @Context
    public GraphDatabaseService db;

    @Context
    public Log log;

    @Context
    public Pools pools;


    public record ResolvedNodesResult(@Description("The merged nodes.") List<Node> nodes) {}

    /**
     * Resolve nodes in a list based on node properties
     */
    @Procedure(name = "apoc.ml.resolve.nodes", mode = Mode.WRITE, eager = true)
    @Description("Resolve given `LIST<NODE>` based on node properties.")
    public Stream<ResolvedNodesResult> resolveNodes(
            @Name(value = "nodes", description = "The nodes to be resolved.") List<Node> nodes,
            @Name(
                    value = "mergeConfig",
                    defaultValue = "{}",
                    description =
                            """
            {
                resolutionProperties :: LIST<STRING>,
                openAIKey :: STRING,
                mergeRels :: BOOLEAN,
                batchSize :: LONG,
                selfRef :: BOOLEAN,
                produceSelfRef = true :: BOOLEAN,
                preserveExistingSelfRels = true :: BOOLEAN,
                countMerge = true :: BOOLEAN,
                collapsedLabel :: BOOLEAN,
                singleElementAsArray = false :: BOOLEAN,
                avoidDuplicates = false :: BOOLEAN,
                relationshipSelectionStrategy = "incoming" :: ["incoming", "outgoing", "merge"]
                properties :: ["overwrite", ""discard", "combine"]
            }
            """) Map<String, Object> mergeConfig) throws JsonProcessingException {
        if (nodes == null || nodes.isEmpty()) return Stream.empty();

        System.out.println("====== APOC =======");
        System.out.println("initial nodes: " + nodes.size());
        System.out.println("====== APOC =======");
        int batchSize = ((Long) mergeConfig.get("batchSize")).intValue();
        List<String> resolutionProperties = (List<String>) mergeConfig.get("resolutionProperties");
        String openAIKey = (String) mergeConfig.get("openAIKey");

        RefactorConfig conf = new RefactorConfig(mergeConfig);

        // create cartisian product for comparing (user should use blocking strategy in Cypher for large comparisons)
        Set<NodePair> nodePairs = makeNodePairs(nodes);

        //create model and AI assistant for entity linking
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(openAIKey)
                .modelName(GPT_4_O_MINI)
                .temperature(0.0)
                .build();
        AINodeResolver aiNodeResolver = AiServices.create(AINodeResolver.class, model);;

        // Perform entity linking in batches and resolve into weakly connected components (WCC)
        ConnectedComponents entityComponents = new ConnectedComponents();
        for (List<NodePair> partition : Lists.partition(new ArrayList<>(nodePairs), batchSize)) {
            entityComponents.addNodePairs(findEntityLinks(partition, resolutionProperties, aiNodeResolver)); //batched entity linking
        }

        //Merge Nodes in each component
        List<Node> mergedNodes = new ArrayList<>();
        for (Set<Node> resolvedNodes : entityComponents.getComponents().values()){
            mergedNodes.add(mergeNodes(new ArrayList<Node>(resolvedNodes), mergeConfig));
        }

        return Stream.of(new ResolvedNodesResult(mergedNodes));
    }


    /**
     * Merges the nodes onto the first node.
     * The other nodes are deleted and their relationships moved onto that first node.
     */
    public Node mergeNodes(
            @Name(value = "nodes", description = "The nodes to be merged onto the first node.") List<Node> nodes,
            @Name(
                    value = "config",
                    defaultValue = "{}",
                    description =
                            """
            {
                mergeRels :: BOOLEAN,
                selfRef :: BOOLEAN,
                produceSelfRef = true :: BOOLEAN,
                preserveExistingSelfRels = true :: BOOLEAN,
                countMerge = true :: BOOLEAN,
                collapsedLabel :: BOOLEAN,
                singleElementAsArray = false :: BOOLEAN,
                avoidDuplicates = false :: BOOLEAN,
                relationshipSelectionStrategy = "incoming" :: ["incoming", "outgoing", "merge"]
                properties :: ["overwrite", ""discard", "combine"]
            }
            """)
            Map<String, Object> config) {
        if (nodes == null || nodes.isEmpty()) return null;
        RefactorConfig conf = new RefactorConfig(config);
        Set<Node> nodesSet = new LinkedHashSet<>(nodes);
        // grab write locks upfront consistently ordered
        nodesSet.stream().sorted(Comparator.comparing(Node::getElementId)).forEach(tx::acquireWriteLock);

        final Node first = nodes.get(0);
        final List<String> existingSelfRelIds = conf.isPreservingExistingSelfRels()
                ? StreamSupport.stream(first.getRelationships().spliterator(), false)
                .filter(Util::isSelfRel)
                .map(Entity::getElementId)
                .collect(Collectors.toList())
                : Collections.emptyList();

        nodesSet.stream().skip(1).forEach(node -> mergeNodes(node, first, conf, existingSelfRelIds));
        return first;
    }



    private void mergeNodes(Node source, Node target, RefactorConfig conf, List<String> excludeRelIds) {
        try {
            Map<String, Object> properties = source.getAllProperties();
            final Iterable<Label> labels = source.getLabels();

            copyRelationships(source, target, true, conf.isCreatingNewSelfRel());
            if (conf.getMergeRelsAllowed()) {
                mergeRelationshipsWithSameTypeAndDirection(target, conf, Direction.OUTGOING, excludeRelIds);
                mergeRelationshipsWithSameTypeAndDirection(target, conf, Direction.INCOMING, excludeRelIds);
            }
            source.delete();
            labels.forEach(target::addLabel);
            PropertiesManager.mergeProperties(properties, target, conf);
        } catch (NotFoundException e) {
            log.warn("skipping a node for merging: " + e.getCause().getMessage());
        }
    }

    private void copyRelationships(Node source, Node target, boolean delete, boolean createNewSelfRel) {
        for (Relationship rel : source.getRelationships()) {
            copyRelationship(rel, source, target, createNewSelfRel);
            if (delete) rel.delete();
        }
    }

    private Node copyLabels(Node source, Node target) {
        for (Label label : source.getLabels()) {
            if (!target.hasLabel(label)) {
                target.addLabel(label);
            }
        }
        return target;
    }

    private void copyRelationship(Relationship rel, Node source, Node target, boolean createNewSelfRelf) {
        Node startNode = rel.getStartNode();
        Node endNode = rel.getEndNode();

        if (startNode.getElementId().equals(endNode.getElementId()) && !createNewSelfRelf) {
            return;
        }

        if (startNode.getElementId().equals(source.getElementId())) {
            startNode = target;
        }

        if (endNode.getElementId().equals(source.getElementId())) {
            endNode = target;
        }

        Relationship newrel = startNode.createRelationshipTo(endNode, rel.getType());
        copyProperties(rel, newrel);
    }

    private Set<NodePair> makeNodePairs(Collection<Node> nodes) {
        Set<NodePair> pairs = new HashSet<NodePair>();
        for (Node node : nodes) {
            for(Node innerNode : nodes) {
                if(node.getElementId().compareTo(innerNode.getElementId()) < 0){
                    System.out.println("====== APOC =======");
                    System.out.println("creating node pair: " + node.getElementId() + " __ " + innerNode.getElementId());
                    System.out.println("====== APOC =======");
                    pairs.add(new NodePair(node, innerNode));
                }
            }
        }
        return pairs;
    }

    private Set<NodePair> findEntityLinks(List<NodePair> nodePairs, List<String> resolutionProperties, AINodeResolver aiNodeResolver) throws JsonProcessingException {
        //TODO: Make a Real LLM Call Here
        Set<NodePair> entityLinkPairs = new HashSet<>();

        String nodePairsInput = "";
        for (NodePair nodePair : nodePairs) {
            String nodePairString = nodePair.getComparisonString(resolutionProperties);
            nodePairsInput = nodePairsInput + nodePairString + "\n";
        }
        System.out.println("====== APOC =======");
        System.out.println("Context for AI:\n" + nodePairsInput);
        System.out.println("====== APOC =======");
        List<EntityLinkEnum> linkEnums = aiNodeResolver.resolve(nodePairsInput);
        System.out.println("====== APOC =======");
        System.out.println("Link Enums:\n" + linkEnums);
        System.out.println("====== APOC =======");
        int entitiesCompared = Math.min(linkEnums.size(), nodePairs.size());
        for(int i = 0; i < entitiesCompared; i++){
            if (linkEnums.get(i) == EntityLinkEnum.SAME){
                entityLinkPairs.add(nodePairs.get(i));
            }
        }
        System.out.println("====== APOC =======");
        System.out.println("Entity Link Pairs:\n" + entityLinkPairs);
        System.out.println("====== APOC =======");
        return entityLinkPairs;
    }
}


