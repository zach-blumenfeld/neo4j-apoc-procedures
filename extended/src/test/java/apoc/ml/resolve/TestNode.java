package apoc.ml.resolve;

import apoc.result.VirtualRelationship;
import apoc.util.Util;
import apoc.util.collection.FilteringIterable;
import apoc.util.collection.Iterables;
import org.neo4j.graphdb.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

public class TestNode implements Node {

    private final Set<String> labels = new LinkedHashSet<>();
    private final Map<String, Object> props = new HashMap<>();
    private final List<Relationship> rels = new ArrayList<>();
    private final long id;
    private final String elementId;

    public TestNode(String elementId, Label[] labels, Map<String, Object> props) {
        this.id = 0;
        addLabels(asList(labels));
        this.props.putAll(props);
        this.elementId = elementId;
    }

    public TestNode(String elementId, Label[] labels) {
        this.id = 0;
        addLabels(asList(labels));
        this.elementId = elementId;
    }

    public TestNode(String elementId) {
        this.id = 0;
        this.elementId = elementId;
    }

    @Override
    public long getId(){return this.id;}

    @Override
    public String getElementId() {
        return this.elementId;
    }

    @Override
    public void delete() {
        for (Relationship rel : rels) {
            rel.delete();
        }
    }

    @Override
    public ResourceIterable<Relationship> getRelationships() {
        return Iterables.asResourceIterable(rels);
    }

    @Override
    public boolean hasRelationship() {
        return !rels.isEmpty();
    }

    @Override
    public ResourceIterable<Relationship> getRelationships(RelationshipType... relationshipTypes) {
        return Iterables.asResourceIterable(new FilteringIterable<>(rels, (r) -> isType(r, relationshipTypes)));
    }

    private boolean isType(Relationship r, RelationshipType... relationshipTypes) {
        for (RelationshipType type : relationshipTypes) {
            if (r.isType(type)) return true;
        }
        return false;
    }

    @Override
    public ResourceIterable<Relationship> getRelationships(Direction direction, RelationshipType... relationshipTypes) {
        return Iterables.asResourceIterable(
                new FilteringIterable<>(rels, (r) -> isType(r, relationshipTypes) && isDirection(r, direction)));
    }

    private boolean isDirection(Relationship r, Direction direction) {
        return direction == Direction.BOTH
                || direction == Direction.OUTGOING && r.getStartNode().equals(this)
                || direction == Direction.INCOMING && r.getEndNode().equals(this);
    }

    @Override
    public boolean hasRelationship(RelationshipType... relationshipTypes) {
        return getRelationships(relationshipTypes).iterator().hasNext();
    }

    @Override
    public boolean hasRelationship(Direction direction, RelationshipType... relationshipTypes) {
        return getRelationships(direction, relationshipTypes).iterator().hasNext();
    }

    @Override
    public ResourceIterable<Relationship> getRelationships(Direction direction) {
        return Iterables.asResourceIterable(new FilteringIterable<>(rels, (r) -> isDirection(r, direction)));
    }

    @Override
    public boolean hasRelationship(Direction direction) {
        return getRelationships(direction).iterator().hasNext();
    }

    @Override
    public Relationship getSingleRelationship(RelationshipType relationshipType, Direction direction) {
        return Iterables.single(getRelationships(direction, relationshipType));
    }

    @Override
    public VirtualRelationship createRelationshipTo(Node node, RelationshipType relationshipType) {
        return new VirtualRelationship(this, node, relationshipType);
    }

    @Override
    public Iterable<RelationshipType> getRelationshipTypes() {
        return rels.stream().map(Relationship::getType).collect(Collectors.toList());
    }

    @Override
    public int getDegree() {
        return rels.size();
    }

    @Override
    public int getDegree(RelationshipType relationshipType) {
        return (int) Iterables.count(getRelationships(relationshipType));
    }

    @Override
    public int getDegree(Direction direction) {
        return (int) Iterables.count(getRelationships(direction));
    }

    @Override
    public int getDegree(RelationshipType relationshipType, Direction direction) {
        return (int) Iterables.count(getRelationships(direction, relationshipType));
    }

    @Override
    public void addLabel(Label label) {
        labels.add(label.name());
    }

    public void addLabels(Iterable<Label> labels) {
        for (Label label : labels) {
            addLabel(label);
        }
    }

    @Override
    public void removeLabel(Label label) {
        labels.remove(label.name());
    }

    @Override
    public boolean hasLabel(Label label) {
        return labels.contains(label.name());
    }

    @Override
    public Iterable<Label> getLabels() {
        return labels.stream().map(Label::label).collect(Collectors.toList());
    }

    @Override
    public boolean hasProperty(String s) {
        return props.containsKey(s);
    }

    @Override
    public Object getProperty(String s) {
        return props.get(s);
    }

    @Override
    public Object getProperty(String s, Object o) {
        Object value = props.get(s);
        return value == null ? o : value;
    }

    @Override
    public void setProperty(String s, Object o) {
        props.put(s, o);
    }

    @Override
    public Object removeProperty(String s) {
        return props.remove(s);
    }

    @Override
    public Iterable<String> getPropertyKeys() {
        return props.keySet();
    }

    @Override
    public Map<String, Object> getProperties(String... strings) {
        HashMap<String, Object> res = new HashMap<>(props);
        res.keySet().retainAll(asList(strings));
        return res;
    }

    @Override
    public Map<String, Object> getAllProperties() {
        return props;
    }


    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Node && Objects.equals(getElementId(), ((Node) o).getElementId());
    }

    @Override
    public int hashCode() {
        return elementId.hashCode();
    }

    @Override
    public String toString() {
        return "Node{" + "ElementId=" + elementId + ", labels=" + labels + ", props=" + props + '}';
    }
}
